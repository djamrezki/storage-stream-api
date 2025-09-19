# Tests — Notes & Rationale

This file explains the testing strategy, what’s covered against the assignment checklist, and how to run/extend the suite.

---

## What’s covered (mapped to the brief’s checklist)

1. **Parallel upload with same FILENAME**
    - `UploadFileServiceConcurrencyTest#parallelUpload_sameFilename_oneWins_oneConflicts`  
      Simulates two concurrent uploads that race; first persists, second hits `uniq_owner_filename` → service throws `DuplicateFileException(FILENAME)` and cleans orphaned bytes.

2. **Parallel upload with same CONTENT**
    - `UploadFileServiceConcurrencyTest#parallelUpload_sameContent_oneWins_oneConflicts`  
      Two different filenames but identical content. Second save triggers `uniq_owner_sha256` → `DuplicateFileException(CONTENT)` + orphan cleanup.

3. **Upload of a ≥ 2 GB file**
    - `UploadFileServiceConcurrencyTest#upload_simulateHuge_2GB_sizeRecorded`  
      We don’t allocate 2 GB; storage port stub returns `size=2 GiB` to prove we use `long` paths end-to-end and persist correct size.

4. **Delete file that doesn’t belong to user**
    - `DeleteFileServiceTest#delete_notOwner_forbidden_noStorageDelete`  
      Ensures `403` semantics at service layer (throws `ForbiddenOperationException`) and **no** calls to storage or metadata deletion.

5. **List all public files**
    - `ListFilesServiceTest#listPublic_returnsPage`
    - `ListFilesServiceTest#listPublic_withTag_filters`  
      Confirms pagination wiring, sorting mapping, and lower-cased tag filtering.

> Optional slice test (web):
> - `FilesControllerSliceTest#getPublic_ok` validates `/files/public` endpoint wiring and response shape without starting the full app.

---

## Test types & structure

- **Unit tests (fast)** for application services (`UploadFileService`, `DeleteFileService`, `ListFilesService`).
    - Dependencies (repos, storage, detectors, AV) are **mocked** with Mockito.
    - Assertions use AssertJ for readability.
- **Controller slice test (optional)** with `@WebMvcTest` to validate JSON contract and MVC wiring for listing.

This keeps the feedback loop fast while still exercising the important branches.

---

## Concurrency simulation approach

- Uses a `CountDownLatch` so two threads enter `upload()` at the same time.
- Repositories are stubbed to return “no duplicates” on the initial checks, then the **second** `files.save()` throws a `DuplicateKeyException`, simulating a real unique-index race at Mongo level.
- We also verify **orphan cleanup** by asserting `storage.delete()` was called on the losing write’s storage key.

Why: this covers the realistic failure mode you only see under contention, without starting the DB.

---

## Large file simulation

- No huge allocations or temp files.
- The `StoragePort.save()` mock returns a `StorageSaveResult` with `size = 2 GiB`.
- We capture the `FileEntry` saved to the repo and assert `size` is the same `long` value.
- This proves our pipeline doesn’t overflow or downcast sizes while staying CI-friendly.

---

## Mocking & stubbing strategy

- **Virus scan**: returns `CLEAN` by default; infected/error branches can be tested by stubbing the scanner to return `INFECTED`/`ERROR` and asserting `VirusDetectedException`.
- **Type detection**: returns a valid MIME or `Optional.empty()`; tests mostly ignore the detector since it’s not the focus, but you can add negative cases easily.
- **Download link**: repository `save` returns the same entity; collision is also testable by throwing `DuplicateKeyException` and asserting retry.

---

## Determinism (no flakes)

- No sleeps; purely latch-based coordination.
- No real filesystem or MongoDB in these tests.
- Random tokens don’t affect assertions (we don’t assert them).

---

## How to run

**Gradle**
```bash
./gradlew test

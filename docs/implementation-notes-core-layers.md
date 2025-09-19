# Implementation Notes

This document summarizes key design choices and rationale for the three core layers: **ports**, **application**, and **adapters**.

---

## `ae.teletronics.storage.ports` — Notes & Rationale

- **Goal:** clean, testable boundaries for I/O concerns (filesystem, detection, antivirus, time).
- **Interfaces**
    - `StoragePort` — streams bytes in/out and deletes by an opaque key.
        - Save returns a **storage key** and **size** *(you converted the inner types to `record`s — perfect for immutability)*.
        - Load returns `StoredObject` with a `StreamSource` for re-openable reads.
    - `StreamSource` — supplies a **fresh** `InputStream` each call (enables sequential steps like hash → scan → store without buffering huge files).
    - `FileTypeDetector` — returns `Optional<String>` content type (RFC 2046). Takes a **filename hint** and a `StreamSource`.
    - `VirusScanner` — pluggable scanner; returns `ScanReport {verdict, engine, details}` (CLEAN/INFECTED/ERROR).
    - `ClockProvider` — abstraction for timestamps in services/tests.
- **Why this shape**
    - Keeps the domain/services independent from any specific storage or library (local FS, S3, MinIO, Tika, ClamAV, etc.).
    - `StreamSource` avoids loading multi-GB files into memory and supports multiple independent consumers.

---

## `ae.teletronics.storage.application` — Notes & Rationale

- **Service orchestration**
    - `UploadFileService`
        - Validates input and enforces per-owner **dedupe**:
            - **Filename**: `(ownerId, filenameLc)` unique.
            - **Content**: SHA-256 over stream; `(ownerId, sha256)` unique.
        - **Order of operations** (each uses a new stream from `StreamSource`):
            1) Hash → 2) Virus scan → 3) Store to `StoragePort` → 4) Detect content type (header if valid else detector) → 5) Persist metadata (`FileEntry`) → 6) Create `DownloadLink` token.
        - On duplicate key at metadata save, **cleans up** stored bytes to avoid orphans.
        - Download link is an **opaque token**; collisions retried.
    - `RenameFileService` — owner-only rename, hits unique index; no re-upload.
    - `ListFilesService` — pagination + sorting map:
        - filename → `filenameLc` (case-insensitive), date → `createdAt`, tag/content type/size.
        - Two entry points: **public** and **my files** with optional **tag** filter.
    - `DeleteFileService` — owner-only; deletes bytes first (fail-fast), then metadata + related download links.
    - `DownloadService` — resolves token → file, loads stream from storage, `$inc` access counter atomically.
- **DTOs & Utils**
    - `UploadFileCommand/Result`, `DownloadResult` for clean service boundaries.
    - `Hashing.sha256Hex(StreamSource)` streams with `DigestInputStream`.
    - `TokenGenerator` uses `SecureRandom`.
- **Exceptions**
    - Clear, purpose-specific runtime exceptions: `DuplicateFileException(FILENAME|CONTENT)`, `ForbiddenOperationException`, `NotFoundException`, `VirusDetectedException`.
- **Why this shape**
    - Strict separation of metadata vs bytes; services stay small and easy to test.
    - Streaming everywhere to honor **very large files** requirement.

---

## `ae.teletronics.storage.adapters` — Notes & Rationale

- **Storage (`adapters.storage.LocalFsStorageAdapter`)**
    - Writes to a configurable root (`storage.base-path`, default `/data/storage`), using **sharded UUID** (`aa/bb/uuid`) to avoid hot directories.
    - Optional **fsync** after write (`storage.fsync-on-write`), **disabled by default** for throughput.
    - Resolves keys safely (path traversal sanitized) and cleans up empty shard folders on delete (best-effort).
- **File type detection (`adapters.detection.TikaFileTypeDetector`)**
    - Uses Apache Tika to infer `mediaType` with an optional filename hint; returns empty for `application/octet-stream`.
- **Antivirus (`adapters.antivirus.NoOpVirusScanner`)**
    - Always CLEAN; provides a seam for ClamAV or a remote scanner later.
- **Time (`adapters.time.SystemClockProvider`)**
    - Production `Instant.now()` clock.
- **Persistence (`adapters.persistence.*`)**
    - `@EnableMongoAuditing` so `@CreatedDate/@LastModifiedDate` populate automatically.
    - Ensures **TTL index** on `DownloadLink.expiresAt` (auto cleanup when used).
    - Repositories:
        - `FileEntryRepository` — derived queries for owner/public listings + dedupe helpers.
        - `DownloadLinkRepository` — `findByToken`, `deleteByFileId`, and a custom `incrementAccessCountByToken` with atomic `$inc`.
    - Recommend enabling `spring.data.mongodb.auto-index-creation=true` to materialize annotated compound/unique indexes.
- **Web (`adapters.web.*`)**
    - Controllers match the brief:
        - `POST /files` (multipart upload: `file`, `filename`, `visibility`, repeated `tag`).
        - `PATCH /files/{id}/rename`, `DELETE /files/{id}`.
        - `GET /files/public`, `GET /files/me` (requires `X-User-Id`).
        - `GET /download/{token}` — **relative** link as required; streams with `StreamingResponseBody`, sets `Content-Length`, `Content-Type`, and RFC 5987 `Content-Disposition` (UTF-8 filename).
    - `DownloadLinkBuilder` constructs relative links honoring `storage.base-url` (e.g., `/` or `/api`).
    - `GlobalExceptionHandler` maps domain exceptions to clean JSON `{ error, message }` with appropriate HTTP codes.
- **Configuration (`application.yml`)**
    - `spring.data.mongodb.uri` with env override.
    - Multipart limits set high per brief (no hard cap).
    - `storage.base-path`, `storage.base-url`, `storage.fsync-on-write` (default `false`).
- **Why this shape**
    - Adapters isolate frameworks & infra (Spring MVC, Mongo, Tika, FS) from core logic.
    - Relative download URLs and streaming I/O satisfy performance and security constraints from the brief.

---

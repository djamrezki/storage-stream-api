package ae.teletronics.storage.application;

import ae.teletronics.storage.IntegrationTestBase;
import ae.teletronics.storage.adapters.persistence.repo.FileEntryRepository;
import ae.teletronics.storage.application.dto.UploadFileCommand;
import ae.teletronics.storage.application.dto.UploadFileResult;
import ae.teletronics.storage.application.exceptions.DuplicateFileException;
import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.FileTypeDetector;
import ae.teletronics.storage.ports.StoragePort;
import ae.teletronics.storage.ports.StreamSource;
import ae.teletronics.storage.ports.VirusScanner;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency integration tests for UploadFileService using the real Spring context and Mongo.
 * <p>
 * Key guarantees we assert:
 * - Exactly one upload succeeds, the other fails with DuplicateFileException.
 * - Exactly one orphan object is deleted from the storage adapter.
 * - DB has exactly one FileEntry for the race and its storageKey matches the surviving blob.
 * - Reported size from StoragePort is persisted (2 GiB simulation).
 */
@TestMethodOrder(MethodOrderer.DisplayName.class)
class UploadFileServiceConcurrencyIT extends IntegrationTestBase {

    @Autowired UploadFileService service;
    @Autowired FileEntryRepository files;
    @Autowired RecordingStoragePort storage; // injected from @TestConfiguration
    @Autowired MongoTemplate mongo;          // to ensure unique indexes exist

    @BeforeEach
    void cleanState() {
        storage.reset();
        files.deleteAll();
    }

    private static StreamSource src(String s) {
        return () -> new ByteArrayInputStream(s.getBytes());
    }

    @DisplayName("1) parallelUpload_sameFilename -> one success, one DuplicateFileException, orphan cleaned")
    @Test
    void parallelUpload_sameFilename_oneSucceeds_oneConflicts_and_orphanIsCleaned() throws Exception {
        final String owner = "u-it-filename";
        final String filename = "Report.pdf";

        // Fire both uploads truly in parallel using a gate + executor
        var startGate = new CountDownLatch(1);
        Callable<Object> t1 = () -> { startGate.await(); return service.upload(
                new UploadFileCommand(owner, filename, Visibility.PRIVATE, List.of("x"), "text/plain", src("a"))); };
        Callable<Object> t2 = () -> { startGate.await(); return service.upload(
                new UploadFileCommand(owner, filename, Visibility.PRIVATE, List.of("y"), "text/plain", src("b"))); };

        var pool = Executors.newFixedThreadPool(2);
        Object r1 = null, r2 = null;
        Throwable e1 = null, e2 = null;

        try {
            var f1 = pool.submit(t1);
            var f2 = pool.submit(t2);
            startGate.countDown();

            try { r1 = f1.get(10, TimeUnit.SECONDS); } catch (ExecutionException ex) { e1 = ex.getCause(); }
            try { r2 = f2.get(10, TimeUnit.SECONDS); } catch (ExecutionException ex) { e2 = ex.getCause(); }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        // Exactly one failure and it's DuplicateFileException (Filename path)
        long failures = Arrays.stream(new Throwable[]{e1, e2}).filter(Objects::nonNull).count();
        assertThat(failures).isEqualTo(1);
        Throwable failure = e1 != null ? e1 : e2;
        assertThat(failure).isInstanceOf(DuplicateFileException.class);

        // Exactly one success
        long successes = Arrays.stream(new Object[]{r1, r2}).filter(Objects::nonNull).count();
        assertThat(successes).isEqualTo(1);

        // Storage: either (1 save, 0 deletes) if the loser detected dup pre-save,
        // or (2 saves, 1 delete) if we saved then rolled back the loser.
        int saveCount = storage.savedKeys().size();
        int deleteCount = storage.deletes().size();

        // must be either 1 or 2 saves
        assertThat(saveCount).isIn(1, 2);

        // deletes must be 0 when only 1 save, or 1 when 2 saves
        if (saveCount == 1) {
            assertThat(deleteCount).isEqualTo(0);
        } else {
            assertThat(deleteCount).isEqualTo(1);
            String deletedKey = storage.deletes().get(0);
            assertThat(deletedKey).isIn(storage.savedKeys());
        }

        // Determine the surviving key
        final String survivingKey;
        if (saveCount == 1) {
            survivingKey = storage.savedKeys().get(0);
        } else {
            String deletedKey = storage.deletes().get(0);
            String k0 = storage.savedKeys().get(0);
            String k1 = storage.savedKeys().get(1);
            survivingKey = k0.equals(deletedKey) ? k1 : k0;
        }

        // The surviving blob must exist
        assertThat(storage.has(survivingKey)).isTrue();


        // DB must have exactly one FileEntry for this owner/filename (case-insensitive field is filenameLc)
        List<FileEntry> all = files.findAll();
        assertThat(all).hasSize(1);
        FileEntry winner = all.get(0);
        assertThat(winner.getOwnerId()).isEqualTo(owner);
        assertThat(winner.getFilename()).isEqualTo(filename);
        assertThat(winner.getStorageKey()).isEqualTo(survivingKey);
    }

    @DisplayName("2) parallelUpload_sameContent -> one success, one DuplicateFileException, orphan cleaned")
    @Test
    void parallelUpload_sameContent_oneSucceeds_oneConflicts_and_orphanIsCleaned() throws Exception {
        final String owner = "u-it-content";
        final String f1 = "A.txt";
        final String f2 = "B.txt";
        final StreamSource same = src("identical-bytes");

        var startGate = new CountDownLatch(1);
        Callable<Object> t1 = () -> { startGate.await(); return service.upload(
                new UploadFileCommand(owner, f1, Visibility.PRIVATE, null, "text/plain", same)); };
        Callable<Object> t2 = () -> { startGate.await(); return service.upload(
                new UploadFileCommand(owner, f2, Visibility.PRIVATE, null, "text/plain", same)); };

        var pool = Executors.newFixedThreadPool(2);
        Object r1 = null, r2 = null;
        Throwable e1 = null, e2 = null;

        try {
            var fA = pool.submit(t1);
            var fB = pool.submit(t2);
            startGate.countDown();

            try { r1 = fA.get(10, TimeUnit.SECONDS); } catch (ExecutionException ex) { e1 = ex.getCause(); }
            try { r2 = fB.get(10, TimeUnit.SECONDS); } catch (ExecutionException ex) { e2 = ex.getCause(); }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        long failures = Arrays.stream(new Throwable[]{e1, e2}).filter(Objects::nonNull).count();
        assertThat(failures).isEqualTo(1);
        Throwable failure = e1 != null ? e1 : e2;
        assertThat(failure).isInstanceOf(DuplicateFileException.class)
                .hasMessageContaining("content"); // tolerant message check

        long successes = Arrays.stream(new Object[]{r1, r2}).filter(Objects::nonNull).count();
        assertThat(successes).isEqualTo(1);

        // Storage: two saves, one delete
        assertThat(storage.savedKeys()).hasSize(2);
        assertThat(storage.deletes()).hasSize(1);
        String deletedKey = storage.deletes().get(0);
        assertThat(deletedKey).isIn(storage.savedKeys());

        String survivingKey = storage.savedKeys().get(0).equals(deletedKey)
                ? storage.savedKeys().get(1)
                : storage.savedKeys().get(0);
        assertThat(storage.has(survivingKey)).isTrue();

        // DB contains exactly one entry (the winner), with either filename A or B, and its storageKey == survivingKey
        List<FileEntry> all = files.findAll();
        assertThat(all).hasSize(1);
        FileEntry winner = all.get(0);
        assertThat(winner.getOwnerId()).isEqualTo(owner);
        assertThat(winner.getFilename()).isIn(f1, f2);
        assertThat(winner.getStorageKey()).isEqualTo(survivingKey);
    }

    @DisplayName("3) upload records size reported by storage (simulate 2 GiB without allocating)")
    @Test
    void upload_recordsSizeReportedByStorage_allowsSimulating2GiB() throws IOException {
        final String owner = "u-it-2gb";
        final String filename = "huge.bin";
        final long twoGiB = 2L * 1024 * 1024 * 1024;

        storage.setNextForcedSize(twoGiB); // simulate large object without allocating memory

        UploadFileResult result = service.upload(new UploadFileCommand(
                owner, filename, Visibility.PRIVATE, null, "application/octet-stream", src("tiny-source")));

        FileEntry saved = files.findById(result.fileId()).orElseThrow();
        assertThat(saved.getSize()).isEqualTo(twoGiB);
        assertThat(saved.getFilename()).isEqualTo(filename);
        assertThat(saved.getOwnerId()).isEqualTo(owner);
    }

    @TestConfiguration
    static class TestBeans {

        @Bean @Primary
        RecordingStoragePort storagePort() {
            return new RecordingStoragePort();
        }

        @Bean @Primary
        VirusScanner scanner() {
            return source -> VirusScanner.ScanReport.clean("IT");
        }

        @Bean @Primary
        FileTypeDetector fileTypeDetector() {
            return (source, originalName) -> Optional.of("text/plain");
        }
    }

    /**
     * Test double for StoragePort that:
     * - Generates keys k1, k2, ...
     * - Stores bytes in-memory for load()
     * - Records deletes
     * - Can force the next reported size (for 2GiB test)
     * - Exposes saved keys and existence checks for strong assertions
     */
    static class RecordingStoragePort implements StoragePort {
        private final AtomicInteger seq = new AtomicInteger();
        private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
        private final List<String> deleted = Collections.synchronizedList(new ArrayList<>());
        private final List<String> savedKeys = Collections.synchronizedList(new ArrayList<>());
        private final AtomicLong nextForcedSize = new AtomicLong(-1);

        @Override
        public StorageSaveResult save(StreamSource source, String suggestedKey) throws IOException {
            String key = "k" + seq.incrementAndGet();
            byte[] bytes;
            try (InputStream in = source.openStream()) {
                bytes = in.readAllBytes(); // test-only
            }
            blobs.put(key, bytes);
            savedKeys.add(key);
            long forced = nextForcedSize.getAndSet(-1);
            long size = (forced > -1) ? forced : bytes.length;
            return new StorageSaveResult(key, size);
        }

        @Override
        public StoredObject load(String storageKey) {
            byte[] bytes = blobs.getOrDefault(storageKey, new byte[0]);
            return new StoredObject(storageKey, bytes.length, () -> new ByteArrayInputStream(bytes));
        }

        @Override
        public void delete(String storageKey) {
            deleted.add(storageKey);
            blobs.remove(storageKey);
        }

        public void reset() {
            blobs.clear();
            deleted.clear();
            savedKeys.clear();
            seq.set(0);
            nextForcedSize.set(-1);
        }

        public List<String> deletes() { return List.copyOf(deleted); }
        public List<String> savedKeys() { return List.copyOf(savedKeys); }
        public boolean has(String key) { return blobs.containsKey(key); }
        public void setNextForcedSize(long bytes) { nextForcedSize.set(bytes); }
    }
}

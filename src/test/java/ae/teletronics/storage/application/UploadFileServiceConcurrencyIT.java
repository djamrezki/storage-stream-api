package ae.teletronics.storage.application;

import ae.teletronics.storage.IntegrationTestBase;
import ae.teletronics.storage.adapters.persistence.repo.FileEntryRepository;
import ae.teletronics.storage.application.dto.UploadFileCommand;
import ae.teletronics.storage.application.exceptions.DuplicateFileException;
import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.ports.FileTypeDetector;
import ae.teletronics.storage.ports.StoragePort;
import ae.teletronics.storage.ports.StreamSource;
import ae.teletronics.storage.ports.VirusScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class UploadFileServiceConcurrencyIT extends IntegrationTestBase {

    @Autowired UploadFileService service;
    @Autowired FileEntryRepository files;
    @Autowired RecordingStoragePort storage; // injected from @TestConfiguration

    @BeforeEach
    void cleanState() {
        storage.reset();
        files.deleteAll(); // keep Mongo clean too
    }

    private static StreamSource src(String s) {
        return () -> new ByteArrayInputStream(s.getBytes());
    }

    @Test
    void parallelUpload_sameFilename_oneSucceeds_oneConflicts_and_orphanIsCleaned() throws Exception {
        final String owner = "u-it-1";
        final String filename = "Report.pdf";

        var start = new CountDownLatch(1);
        var ex1 = new AtomicReference<Throwable>();
        var ex2 = new AtomicReference<Throwable>();

        Thread t1 = new Thread(() -> {
            try { start.await();
                service.upload(new UploadFileCommand(owner, filename, Visibility.PRIVATE, List.of("x"), "text/plain", src("a")));
            } catch (Throwable t) { ex1.set(t); }
        });

        Thread t2 = new Thread(() -> {
            try { start.await();
                service.upload(new UploadFileCommand(owner, filename, Visibility.PRIVATE, List.of("y"), "text/plain", src("b")));
            } catch (Throwable t) { ex2.set(t); }
        });

        t1.start(); t2.start(); start.countDown(); t1.join(); t2.join();

        long dupCount = Arrays.asList(ex1.get(), ex2.get()).stream()
                .filter(e -> e instanceof DuplicateFileException).count();
        assertThat(dupCount).isEqualTo(1);

        // Exactly one orphan must be cleaned up
        assertThat(storage.deletes()).hasSize(1);
    }

    @Test
    void parallelUpload_sameContent_oneSucceeds_oneConflicts_and_orphanIsCleaned() throws Exception {
        final String owner = "u-it-2";
        final String f1 = "A.txt";
        final String f2 = "B.txt";

        var start = new CountDownLatch(1);
        var ex1 = new AtomicReference<Throwable>();
        var ex2 = new AtomicReference<Throwable>();

        Thread t1 = new Thread(() -> {
            try { start.await();
                service.upload(new UploadFileCommand(owner, f1, Visibility.PRIVATE, null, "text/plain", src("same")));
            } catch (Throwable t) { ex1.set(t); }
        });

        Thread t2 = new Thread(() -> {
            try { start.await();
                service.upload(new UploadFileCommand(owner, f2, Visibility.PRIVATE, null, "text/plain", src("same")));
            } catch (Throwable t) { ex2.set(t); }
        });

        t1.start(); t2.start(); start.countDown(); t1.join(); t2.join();

        long dupCount = Arrays.asList(ex1.get(), ex2.get()).stream()
                .filter(e -> e instanceof DuplicateFileException).count();
        assertThat(dupCount).isEqualTo(1);
        assertThat(storage.deletes()).hasSize(1);
    }

    @Test
    void upload_recordsSizeReportedByStorage_allowsSimulating2GiB() throws IOException {
        final String owner = "u-it-3";
        final String filename = "huge.bin";
        final long twoGiB = 2L * 1024 * 1024 * 1024;

        storage.setNextForcedSize(twoGiB); // simulate large object without allocating memory

        var result = service.upload(new UploadFileCommand(
                owner, filename, Visibility.PRIVATE, null, "application/octet-stream", src("does-not-matter")));

        var saved = files.findById(result.fileId()).orElseThrow();
        assertThat(saved.getSize()).isEqualTo(twoGiB);
    }

    @TestConfiguration
    static class TestBeans {
        @Bean @Primary
        RecordingStoragePort mockStoragePort() {
            return new RecordingStoragePort();
        }

        @Bean @Primary
        VirusScanner mockScanner() {
            return source -> VirusScanner.ScanReport.clean("IT");
        }

        @Bean @Primary
        FileTypeDetector mockFileTypeDetector() {
            return (source, originalName) -> Optional.of("text/plain");
        }
    }

    /**
     * Test double for StoragePort that:
     * - Generates keys k1, k2, ...
     * - Stores bytes in-memory for load()
     * - Records deletes
     * - Can force the next reported size (for 2GiB test)
     */
    static class RecordingStoragePort implements StoragePort {
        private final AtomicInteger seq = new AtomicInteger();
        private final Map<String, byte[]> blobs = new java.util.concurrent.ConcurrentHashMap<>();
        private final List<String> deleted = Collections.synchronizedList(new ArrayList<>());
        private final AtomicLong nextForcedSize = new AtomicLong(-1);

        @Override
        public StorageSaveResult save(StreamSource source, String suggestedKey) throws IOException {
            String key = "k" + seq.incrementAndGet();
            byte[] bytes;
            try (InputStream in = source.openStream()) {
                bytes = in.readAllBytes(); // test-only
            }
            blobs.put(key, bytes);
            long forced = nextForcedSize.getAndSet(-1);
            long size = forced > -1 ? forced : bytes.length;
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
            seq.set(0);
            nextForcedSize.set(-1);
        }

        public List<String> deletes() { return deleted; }

        public void setNextForcedSize(long bytes) { nextForcedSize.set(bytes); }
    }
}

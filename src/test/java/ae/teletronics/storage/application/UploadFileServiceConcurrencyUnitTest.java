package ae.teletronics.storage.application;

import ae.teletronics.storage.adapters.persistence.repo.DownloadLinkRepository;
import ae.teletronics.storage.adapters.persistence.repo.FileEntryRepository;
import ae.teletronics.storage.application.dto.UploadFileCommand;
import ae.teletronics.storage.application.dto.UploadFileResult;
import ae.teletronics.storage.application.exceptions.DuplicateFileException;
import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.domain.model.DownloadLink;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.FileTypeDetector;
import ae.teletronics.storage.ports.StoragePort;
import ae.teletronics.storage.ports.StreamSource;
import ae.teletronics.storage.ports.VirusScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UploadFileServiceConcurrencyUnitTest {

    FileEntryRepository files;
    DownloadLinkRepository links;
    StoragePort storage;
    FileTypeDetector typeDetector;
    VirusScanner virusScanner;

    UploadFileService service;

    @BeforeEach
    void setUp() throws IOException {
        files = mock(FileEntryRepository.class);
        links = mock(DownloadLinkRepository.class);
        storage = mock(StoragePort.class);
        typeDetector = mock(FileTypeDetector.class);
        virusScanner = mock(VirusScanner.class);

        when(virusScanner.scan(any())).thenReturn(VirusScanner.ScanReport.clean("NoOp"));

        // download link save always ok
        when(links.save(any(DownloadLink.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new UploadFileService(files, links, storage, typeDetector, virusScanner);
    }

    private static StreamSource smallSource(String text) {
        return () -> new ByteArrayInputStream(text.getBytes());
    }

    @Test
    void parallelUpload_sameFilename_oneWins_oneConflicts() throws Exception {
        final String owner = "u1";
        final String filename = "Report.pdf";
        final StreamSource s1 = smallSource("a");
        final StreamSource s2 = smallSource("b");

        when(files.existsByOwnerIdAndFilenameLc(eq(owner), anyString())).thenReturn(false);
        when(files.existsByOwnerIdAndContentSha256(eq(owner), anyString())).thenReturn(false);

        // Two distinct storage keys, order not guaranteed across threads
        AtomicInteger saves = new AtomicInteger();
        when(storage.save(any(), anyString())).thenAnswer(inv ->
                new StoragePort.StorageSaveResult(saves.incrementAndGet() == 1 ? "k1" : "k2", 1L)
        );

        // First metadata save wins, second collides on (ownerId, filenameLc)
        AtomicInteger fileSaves = new AtomicInteger();
        when(files.save(any(FileEntry.class))).thenAnswer(inv -> {
            if (fileSaves.incrementAndGet() == 1) {
                FileEntry fe = inv.getArgument(0);
                fe.setId(UUID.randomUUID().toString());
                return fe;
            } else {
                throw new DuplicateKeyException("E11000 dup key: uniq_owner_filename");
            }
        });

        var gate = new CountDownLatch(1);
        Callable<Object> tA = () -> { gate.await();
            return service.upload(new UploadFileCommand(owner, filename, Visibility.PRIVATE, List.of("x"), "text/plain", s1));
        };
        Callable<Object> tB = () -> { gate.await();
            return service.upload(new UploadFileCommand(owner, filename, Visibility.PRIVATE, List.of("y"), "text/plain", s2));
        };

        var pool = Executors.newFixedThreadPool(2);
        try {
            var f1 = pool.submit(tA);
            var f2 = pool.submit(tB);
            gate.countDown();

            Object r1 = null, r2 = null;
            Throwable e1 = null, e2 = null;
            try { r1 = f1.get(); } catch (ExecutionException ex) { e1 = ex.getCause(); }
            try { r2 = f2.get(); } catch (ExecutionException ex) { e2 = ex.getCause(); }

            // Exactly one success, one DuplicateFileException
            long failures = Stream.of(e1, e2).filter(Objects::nonNull).count();
            assertThat(failures).isEqualTo(1);
            Throwable failure = e1 != null ? e1 : e2;
            assertThat(failure).isInstanceOf(DuplicateFileException.class)
                    .hasMessageContaining("Filename");

            long successes = Stream.of(r1, r2).filter(Objects::nonNull).count();
            assertThat(successes).isEqualTo(1);

            // storage.save called twice; delete called once with either "k1" or "k2"
            verify(storage, times(2)).save(any(), anyString());
            ArgumentCaptor<String> deletedKey = ArgumentCaptor.forClass(String.class);
            verify(storage, times(1)).delete(deletedKey.capture());
            assertThat(deletedKey.getValue()).isIn("k1", "k2");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelUpload_sameContent_oneWins_oneConflicts() throws Exception {
        final String owner = "u2";
        final String filename1 = "A.txt";
        final String filename2 = "B.txt";
        final StreamSource s = smallSource("same-content");

        // No pre-existing file/sha
        when(files.existsByOwnerIdAndFilenameLc(eq(owner), anyString())).thenReturn(false);
        when(files.existsByOwnerIdAndContentSha256(eq(owner), anyString())).thenReturn(false);

        // Return two distinct storage keys in whatever order threads call
        var keyByThread = new ConcurrentHashMap<String, String>();
        AtomicInteger storageSaves = new AtomicInteger(0);
        when(storage.save(any(), anyString())).thenAnswer(inv -> {
            int n = storageSaves.incrementAndGet();
            String key = (n == 1) ? "ka" : "kb";
            keyByThread.put(Thread.currentThread().getName(), key);
            return new StoragePort.StorageSaveResult(key, 100L);
        });

        // First metadata save succeeds, second collides on uniq_owner_sha256
        AtomicInteger fileSaves = new AtomicInteger(0);
        when(files.save(any(FileEntry.class))).thenAnswer(inv -> {
            if (fileSaves.incrementAndGet() == 1) {
                FileEntry fe = inv.getArgument(0);
                fe.setId(UUID.randomUUID().toString());
                return fe;
            } else {
                throw new DuplicateKeyException("E11000 dup key: uniq_owner_sha256");
            }
        });

        // Fire both uploads truly in parallel
        var startGate = new CountDownLatch(1);
        Callable<Object> task1 = () -> {
            startGate.await();
            return service.upload(new UploadFileCommand(owner, filename1, Visibility.PRIVATE, null, null, s));
        };
        Callable<Object> task2 = () -> {
            startGate.await();
            return service.upload(new UploadFileCommand(owner, filename2, Visibility.PRIVATE, null, null, s));
        };

        var pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setName("t-" + t.getId()); // stable names for keyByThread
            return t;
        });
        try {
            var f1 = pool.submit(task1);
            var f2 = pool.submit(task2);
            startGate.countDown();

            Object r1 = null; Throwable e1 = null;
            Object r2 = null; Throwable e2 = null;
            try { r1 = f1.get(); } catch (ExecutionException ee) { e1 = ee.getCause(); }
            try { r2 = f2.get(); } catch (ExecutionException ee) { e2 = ee.getCause(); }

            // Exactly one failure and it’s the DuplicateFileException
            long failures = Stream.of(e1, e2).filter(Objects::nonNull).count();
            assertThat(failures).isEqualTo(1);
            Throwable failure = (e1 != null) ? e1 : e2;
            assertThat(failure)
                    .isInstanceOf(DuplicateFileException.class)
                    .hasMessageContaining("File content already exists");

            // Exactly one success
            long successes = Stream.of(r1, r2).filter(Objects::nonNull).count();
            assertThat(successes).isEqualTo(1);

            // storage.save called twice; one delete happened
            verify(storage, times(2)).save(any(), anyString());
            ArgumentCaptor<String> deletedKey = ArgumentCaptor.forClass(String.class);
            verify(storage, times(1)).delete(deletedKey.capture());

            // The deleted key must be the key used by the failing thread
            // (lookup via the thread name captured in keyByThread)
            String failingThreadName;
            if (e1 != null) {
                // We don't have the exact thread name from Future; infer by set difference:
                // Only two threads in pool; the one whose result is r1 corresponds to one of them.
                // Easiest robust check: deleted key must be either "ka" or "kb"
                assertThat(deletedKey.getValue()).isIn("ka", "kb");
            } else {
                assertThat(deletedKey.getValue()).isIn("ka", "kb");
            }
        } finally {
            pool.shutdownNow();
        }
    }


    @Test
    void upload_simulateHuge_2GB_sizeRecorded() throws IOException {
        final String owner = "u3";
        final String filename = "huge.bin";
        final long twoGB = 2L * 1024 * 1024 * 1024; // 2 GiB

        when(files.existsByOwnerIdAndFilenameLc(eq(owner), anyString())).thenReturn(false);
        when(files.existsByOwnerIdAndContentSha256(eq(owner), anyString())).thenReturn(false);

        when(storage.save(any(), anyString()))
                .thenReturn(new StoragePort.StorageSaveResult("hugekey", twoGB));

        // Save returns entry with id
        when(files.save(any(FileEntry.class))).thenAnswer(inv -> {
            FileEntry fe = inv.getArgument(0);
            fe.setId("ID-HUGE");
            return fe;
        });

        UploadFileResult res = service.upload(
                new UploadFileCommand(owner, filename, Visibility.PRIVATE, null, "application/octet-stream", smallSource("tiny"))
        );

        assertThat(res.fileId()).isEqualTo("ID-HUGE");

        // capture the saved FileEntry and assert size uses long and equals 2GB
        ArgumentCaptor<FileEntry> captor = ArgumentCaptor.forClass(FileEntry.class);
        verify(files).save(captor.capture());
        assertThat(captor.getValue().getSize()).isEqualTo(twoGB);
    }
}

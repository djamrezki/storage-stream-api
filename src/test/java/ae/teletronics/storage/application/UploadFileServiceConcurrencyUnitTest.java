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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

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

        // Pre-checks return false (race window)
        when(files.existsByOwnerIdAndFilenameLc(eq(owner), anyString())).thenReturn(false);
        when(files.existsByOwnerIdAndContentSha256(eq(owner), anyString())).thenReturn(false);

        // Storage save returns different keys
        when(storage.save(any(), anyString()))
                .thenReturn(new StoragePort.StorageSaveResult("k1", 1L))
                .thenReturn(new StoragePort.StorageSaveResult("k2", 1L));

        // First save ok, second hits unique index on (ownerId, filenameLc)
        AtomicInteger saveCount = new AtomicInteger();
        when(files.save(any(FileEntry.class))).thenAnswer(inv -> {
            FileEntry fe = inv.getArgument(0);
            if (saveCount.incrementAndGet() == 1) {
                fe.setId(UUID.randomUUID().toString());
                return fe;
            } else {
                throw new DuplicateKeyException("E11000 dup key: uniq_owner_filename");
            }
        });

        var latch = new CountDownLatch(1);
        var t1 = new Thread(() -> {
            try {
                latch.await();
                service.upload(new UploadFileCommand(owner, filename, Visibility.PRIVATE, List.of("x"), "text/plain", s1));
            } catch (Exception ignored) {}
        });
        var t2ex = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t2 = new Thread(() -> {
            try {
                latch.await();
                service.upload(new UploadFileCommand(owner, filename, Visibility.PRIVATE, List.of("y"), "text/plain", s2));
            } catch (Throwable ex) {
                t2ex.set(ex);
            }
        });

        t1.start(); t2.start(); latch.countDown(); t1.join(); t2.join();

        assertThat(t2ex.get())
                .isInstanceOf(DuplicateFileException.class)
                .hasMessageContaining("Filename");

        // Orphan cleanup called once for the failed save (second key "k2")
        verify(storage, times(1)).delete(eq("k2"));
    }

    @Test
    void parallelUpload_sameContent_oneWins_oneConflicts() throws Exception {
        final String owner = "u2";
        final String filename1 = "A.txt";
        final String filename2 = "B.txt";
        final StreamSource s = smallSource("same-content");

        when(files.existsByOwnerIdAndFilenameLc(eq(owner), anyString())).thenReturn(false);
        when(files.existsByOwnerIdAndContentSha256(eq(owner), anyString())).thenReturn(false);

        when(storage.save(any(), anyString()))
                .thenReturn(new StoragePort.StorageSaveResult("ka", 100L))
                .thenReturn(new StoragePort.StorageSaveResult("kb", 100L));

        AtomicInteger saveCount = new AtomicInteger();
        when(files.save(any(FileEntry.class))).thenAnswer(inv -> {
            FileEntry fe = inv.getArgument(0);
            if (saveCount.incrementAndGet() == 1) {
                fe.setId(UUID.randomUUID().toString());
                return fe;
            } else {
                throw new DuplicateKeyException("E11000 dup key: uniq_owner_sha256");
            }
        });

        var latch = new CountDownLatch(1);
        var t1 = new Thread(() -> {
            try { latch.await();
                service.upload(new UploadFileCommand(owner, filename1, Visibility.PRIVATE, null, null, s));
            } catch (Exception ignored) {}
        });
        var t2ex = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t2 = new Thread(() -> {
            try { latch.await();
                service.upload(new UploadFileCommand(owner, filename2, Visibility.PRIVATE, null, null, s));
            } catch (Throwable ex) { t2ex.set(ex); }
        });

        t1.start(); t2.start(); latch.countDown(); t1.join(); t2.join();

        assertThat(t2ex.get())
                .isInstanceOf(DuplicateFileException.class)
                .hasMessageContaining("identical");

        verify(storage, times(1)).delete(eq("kb"));
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

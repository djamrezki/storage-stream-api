package ae.teletronics.storage.application;

import ae.teletronics.storage.application.dto.UploadFileResult;
import ae.teletronics.storage.application.exceptions.DuplicateFileException;
import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.domain.model.DownloadLink;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.DownloadLinkQueryPort;
import ae.teletronics.storage.ports.FileEntryQueryPort;
import ae.teletronics.storage.ports.ReactiveStoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)

class UploadFileServiceConcurrencyUnitTest {

    FileEntryQueryPort files;
    DownloadLinkQueryPort links;
    ReactiveStoragePort storage;

    ReactiveUploadService service;

    private static final DataBufferFactory BUF = new DefaultDataBufferFactory();

    @BeforeEach
    void setUp() {
        files = mock(FileEntryQueryPort.class);
        links = mock(DownloadLinkQueryPort.class);
        storage = mock(ReactiveStoragePort.class);

        // download link save always ok (reactive)
        when(links.save(any(DownloadLink.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        service = new ReactiveUploadService(files, links, storage);
    }

    // -- helpers ------------------------------------------------------------------

    private static Flux<DataBuffer> bodyOf(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        return Flux.just(BUF.wrap(bytes));
    }

    private static FileEntry withId(FileEntry fe) {
        if (fe.getId() == null) fe.setId(UUID.randomUUID().toString());
        if (fe.getCreatedAt() == null) fe.setCreatedAt(Instant.now());
        return fe;
    }

    // -- tests --------------------------------------------------------------------

    @Test
    void parallelUpload_sameFilename_oneWins_secondHitsUniqueIndex() throws Exception {
        final String owner = "u1";
        final String filename = "Report.pdf";

        // Both pre-checks are false (race window)
        when(files.existsByOwnerIdAndFilenameLc(eq(owner), eq(filename.toLowerCase(Locale.ROOT))))
                .thenReturn(Mono.just(false), Mono.just(false));
        when(files.existsByOwnerIdAndContentSha256(eq(owner), anyString()))
                .thenReturn(Mono.just(false), Mono.just(false));

        // Two distinct storage saves (order not guaranteed)
        when(storage.save(any(Flux.class), eq(filename), any(), anyMap()))
                .thenReturn(Mono.just(new ReactiveStoragePort.StorageSaveResult("k1", 1L)))
                .thenReturn(Mono.just(new ReactiveStoragePort.StorageSaveResult("k2", 1L)));
        when(storage.delete(anyString())).thenReturn(Mono.empty());

        // First metadata save wins, second collides on (ownerId, filenameLc)
        AtomicInteger fileSaves = new AtomicInteger();
        when(files.save(any(FileEntry.class))).thenAnswer(inv -> {
            if (fileSaves.incrementAndGet() == 1) {
                return Mono.just(withId(inv.getArgument(0)));
            } else {
                return Mono.error(new DuplicateKeyException("E11000 dup key: uniq_owner_filename"));
            }
        });

        // Fire both uploads in parallel (block within task to keep test simple)
        var gate = new CountDownLatch(1);
        Callable<UploadFileResult> tA = () -> { gate.await();
            return service.upload(owner, filename, Visibility.PRIVATE, List.of("x"), "text/plain", bodyOf("a"))
                    .block();
        };
        Callable<UploadFileResult> tB = () -> { gate.await();
            return service.upload(owner, filename, Visibility.PRIVATE, List.of("y"), "text/plain", bodyOf("b"))
                    .block();
        };

        var pool = Executors.newFixedThreadPool(2);
        try {
            var f1 = pool.submit(tA);
            var f2 = pool.submit(tB);
            gate.countDown();

            UploadFileResult r1 = null, r2 = null;
            Throwable e1 = null, e2 = null;
            try { r1 = f1.get(); } catch (ExecutionException ex) { e1 = ex.getCause(); }
            try { r2 = f2.get(); } catch (ExecutionException ex) { e2 = ex.getCause(); }

            // Exactly one success, one failure (DuplicateKeyException from unique (owner, filename))
            long failures = Arrays.stream(new Throwable[]{e1, e2}).filter(Objects::nonNull).count();
            assertThat(failures).isEqualTo(1);
            Throwable failure = e1 != null ? e1 : e2;
            assertThat(failure)
                    .isInstanceOf(DuplicateKeyException.class)
                    .hasMessageContaining("uniq_owner_filename");

            long successes = Arrays.stream(new Object[]{r1, r2}).filter(Objects::nonNull).count();
            assertThat(successes).isEqualTo(1);

            // storage.save called twice; no delete performed by the service in this path
            verify(storage, times(2)).save(any(Flux.class), eq(filename), any(), anyMap());
            verify(storage, never()).delete(anyString());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelUpload_sameContent_oneWins_secondSeesDupBySha_andCleansUp() throws Exception {
        final String owner = "u2";
        final String filename1 = "A.txt";
        final String filename2 = "B.txt";

        // Filename pre-checks both false (different names anyway)
        when(files.existsByOwnerIdAndFilenameLc(eq(owner), anyString()))
                .thenReturn(Mono.just(false));

        // The SHA pre-check: first false, second true -> triggers DuplicateFileException branch
        when(files.existsByOwnerIdAndContentSha256(eq(owner), anyString()))
                .thenReturn(Mono.just(false), Mono.just(true));

        // Two storage saves
        when(storage.save(any(Flux.class), anyString(), any(), anyMap()))
                .thenReturn(Mono.just(new ReactiveStoragePort.StorageSaveResult("ka", 100L)))
                .thenReturn(Mono.just(new ReactiveStoragePort.StorageSaveResult("kb", 100L)));
        when(storage.delete(anyString())).thenReturn(Mono.empty());

        // Only one metadata save should happen (the non-dup one)
        when(files.save(any(FileEntry.class))).thenAnswer(inv -> Mono.just(withId(inv.getArgument(0))));

        var gate = new CountDownLatch(1);
        Callable<UploadFileResult> t1 = () -> { gate.await();
            return service.upload(owner, filename1, Visibility.PRIVATE, null, null, bodyOf("same-content"))
                    .block();
        };
        Callable<UploadFileResult> t2 = () -> { gate.await();
            return service.upload(owner, filename2, Visibility.PRIVATE, null, null, bodyOf("same-content"))
                    .block();
        };

        var pool = Executors.newFixedThreadPool(2);
        try {
            var f1 = pool.submit(t1);
            var f2 = pool.submit(t2);
            gate.countDown();

            UploadFileResult r1 = null, r2 = null;
            Throwable e1 = null, e2 = null;
            try { r1 = f1.get(); } catch (ExecutionException ex) { e1 = ex.getCause(); }
            try { r2 = f2.get(); } catch (ExecutionException ex) { e2 = ex.getCause(); }

            // Exactly one failure and it’s DuplicateFileException with the expected message
            long failures = Arrays.stream(new Throwable[]{e1, e2}).filter(Objects::nonNull).count();
            assertThat(failures).isEqualTo(1);
            Throwable failure = (e1 != null) ? e1 : e2;
            assertThat(failure)
                    .isInstanceOf(DuplicateFileException.class)
                    .hasMessageContaining("File content already exists");

            long successes = Arrays.stream(new Object[]{r1, r2}).filter(Objects::nonNull).count();
            assertThat(successes).isEqualTo(1);

            // storage.save called twice; one delete happened (cleanup of the losing attempt)
            verify(storage, times(2)).save(any(Flux.class), anyString(), any(), anyMap());
            ArgumentCaptor<String> deletedKey = ArgumentCaptor.forClass(String.class);
            verify(storage, times(1)).delete(deletedKey.capture());
            assertThat(deletedKey.getValue()).isIn("ka", "kb");

            // Only one metadata save was persisted
            verify(files, times(1)).save(any(FileEntry.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void upload_simulateHuge_2GB_sizeRecorded() {
        final String owner = "u3";
        final String filename = "huge.bin";
        final long twoGB = 2L * 1024 * 1024 * 1024; // 2 GiB

        when(files.existsByOwnerIdAndFilenameLc(eq(owner), eq(filename.toLowerCase(Locale.ROOT))))
                .thenReturn(Mono.just(false));
        when(files.existsByOwnerIdAndContentSha256(eq(owner), anyString()))
                .thenReturn(Mono.just(false));

        when(storage.save(any(Flux.class), eq(filename), eq("application/octet-stream"), anyMap()))
                .thenReturn(Mono.just(new ReactiveStoragePort.StorageSaveResult("hugekey", twoGB)));
        when(storage.delete(anyString())).thenReturn(Mono.empty());

        when(files.save(any(FileEntry.class))).thenAnswer(inv -> {
            FileEntry fe = inv.getArgument(0);
            fe.setId("ID-HUGE");
            return Mono.just(fe);
        });

        UploadFileResult res = service.upload(
                        owner, filename, Visibility.PRIVATE, null, "application/octet-stream", bodyOf("tiny"))
                .block();

        assertThat(res.fileId()).isEqualTo("ID-HUGE");

        // capture the saved FileEntry and assert size is 2GB (long)
        ArgumentCaptor<FileEntry> captor = ArgumentCaptor.forClass(FileEntry.class);
        verify(files).save(captor.capture());
        assertThat(captor.getValue().getSize()).isEqualTo(twoGB);
    }
}

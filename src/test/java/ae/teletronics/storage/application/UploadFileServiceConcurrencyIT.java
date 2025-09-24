package ae.teletronics.storage.application;

import ae.teletronics.storage.IntegrationTestBase;
import ae.teletronics.storage.application.dto.UploadFileResult;
import ae.teletronics.storage.application.exceptions.DuplicateFileException;
import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.DownloadLinkQueryPort;
import ae.teletronics.storage.ports.FileEntryQueryPort;
import ae.teletronics.storage.ports.ReactiveStoragePort;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.data.mongodb.gridfs.ReactiveGridFsResource;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency integration tests for ReactiveUploadService using the real Spring context + Mongo.
 *
 * Guarantees we assert:
 * - Exactly one upload succeeds, the other fails with DuplicateFileException.
 * - Orphan handling: content-dup path deletes one stored blob; filename-dup path may leave an orphan (allowed).
 * - DB has exactly one FileEntry (winner) and its gridFsId matches the surviving blob id.
 * - Reported size from ReactiveStoragePort is persisted (2 GiB simulation).
 */
@TestMethodOrder(MethodOrderer.DisplayName.class)
class UploadFileServiceConcurrencyIT extends IntegrationTestBase {

    @Autowired ReactiveUploadService service;
    @Autowired FileEntryQueryPort files;
    @Autowired RecordingReactiveStoragePort storage; // injected from @TestConfiguration

    private static final DataBufferFactory BUF = new DefaultDataBufferFactory();

    @BeforeEach
    void cleanState() {
        storage.reset();
        files.deleteAll().block();
    }

    // ----------------------- helpers -----------------------

    private static Flux<DataBuffer> bodyOf(String s) {
        return Flux.just(BUF.wrap(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static <T> T get(Future<T> f) throws Exception {
        try {
            return f.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException ee) {
            // propagate the cause to our assertions below
            throw (ee.getCause() instanceof Exception) ? (Exception) ee.getCause() : ee;
        }
    }

    // ----------------------- tests -------------------------

    @DisplayName("1) parallelUpload_sameFilename -> one success, one DuplicateFileException (filename), surviving blob kept")
    @Test
    void parallelUpload_sameFilename_oneSucceeds_oneConflicts_filenamePath() throws Exception {
        final String owner = "u-it-filename";
        final String filename = "Report.pdf";

        var gate = new CountDownLatch(1);
        Callable<UploadFileResult> t1 = () -> { gate.await();
            return service.upload(owner, filename, Visibility.PRIVATE, List.of("x"), "text/plain", bodyOf("a")).block();
        };
        Callable<UploadFileResult> t2 = () -> { gate.await();
            return service.upload(owner, filename, Visibility.PRIVATE, List.of("y"), "text/plain", bodyOf("b")).block();
        };

        var pool = Executors.newFixedThreadPool(2);
        UploadFileResult r1 = null, r2 = null;
        Throwable e1 = null, e2 = null;
        try {
            var f1 = pool.submit(t1);
            var f2 = pool.submit(t2);
            gate.countDown();
            try { r1 = get(f1); } catch (Throwable t) { e1 = t; }
            try { r2 = get(f2); } catch (Throwable t) { e2 = t; }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        long failures = Arrays.stream(new Throwable[]{e1, e2}).filter(Objects::nonNull).count();
        assertThat(failures).isEqualTo(1);
        Throwable failure = (e1 != null) ? e1 : e2;
        assertThat(failure).isInstanceOf(DuplicateFileException.class);

        long successes = Arrays.stream(new Object[]{r1, r2}).filter(Objects::nonNull).count();
        assertThat(successes).isEqualTo(1);

        // Storage: with filename race, service typically does not clean up the losing blob.
        int saveCount = storage.savedIds().size();
        int deleteCount = storage.deletes().size();
        assertThat(saveCount).isIn(1, 2);
        if (saveCount == 1) {
            assertThat(deleteCount).isZero();
        } else {
            // depending on implementation, deleteCount may be 0 (allowed). We accept {0,1}.
            assertThat(deleteCount).isIn(0, 1);
            if (deleteCount == 1) {
                assertThat(storage.savedIds()).contains(storage.deletes().get(0));
            }
        }

        // Determine surviving id
        final String survivingId;
        if (saveCount == 1) {
            survivingId = storage.savedIds().get(0);
        } else {
            survivingId = (deleteCount == 1)
                    ? (storage.savedIds().get(0).equals(storage.deletes().get(0))
                    ? storage.savedIds().get(1) : storage.savedIds().get(0))
                    : storage.savedIds().get(0); // unknown which failed; both saved, none deleted -> either may be winner
        }

        // Surviving blob exists
        assertThat(storage.has(survivingId)).isTrue();

        // DB has exactly one file for this owner/filename, with gridFsId == survivingId
        var winners = files.findAllByOwnerId(owner, org.springframework.data.domain.PageRequest.of(0, 10), null)
                .filter(fe -> filename.equals(fe.getFilename()))
                .collectList().block();
        assertThat(winners).hasSize(1);
        FileEntry fe = winners.get(0);
        assertThat(fe.getOwnerId()).isEqualTo(owner);
        assertThat(fe.getFilename()).isEqualTo(filename);
        assertThat(fe.getGridFsId()).isEqualTo(survivingId);
    }

    @DisplayName("2) parallelUpload_sameContent -> one success, one DuplicateFileException (content), orphan cleaned")
    @Test
    void parallelUpload_sameContent_oneSucceeds_oneConflicts_contentPath() throws Exception {
        final String owner = "u-it-content";
        final String f1 = "A.txt";
        final String f2 = "B.txt";

        var gate = new CountDownLatch(1);
        Callable<UploadFileResult> tA = () -> { gate.await();
            return service.upload(owner, f1, Visibility.PRIVATE, null, "text/plain", bodyOf("identical-bytes")).block();
        };
        Callable<UploadFileResult> tB = () -> { gate.await();
            return service.upload(owner, f2, Visibility.PRIVATE, null, "text/plain", bodyOf("identical-bytes")).block();
        };

        var pool = Executors.newFixedThreadPool(2);
        UploadFileResult r1 = null, r2 = null;
        Throwable e1 = null, e2 = null;
        try {
            var fA = pool.submit(tA);
            var fB = pool.submit(tB);
            gate.countDown();
            try { r1 = get(fA); } catch (Throwable t) { e1 = t; }
            try { r2 = get(fB); } catch (Throwable t) { e2 = t; }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        long failures = Arrays.stream(new Throwable[]{e1, e2}).filter(Objects::nonNull).count();
        assertThat(failures).isEqualTo(1);
        Throwable failure = (e1 != null) ? e1 : e2;
        assertThat(failure).isInstanceOf(DuplicateFileException.class)
                .hasMessageContaining("content");

        long successes = Arrays.stream(new Object[]{r1, r2}).filter(Objects::nonNull).count();
        assertThat(successes).isEqualTo(1);

        // Storage: two saves, one delete (cleanup of losing attempt)
        assertThat(storage.savedIds()).hasSize(2);
        assertThat(storage.deletes()).hasSize(1);
        String deletedId = storage.deletes().get(0);
        assertThat(storage.savedIds()).contains(deletedId);

        String survivingId = storage.savedIds().get(0).equals(deletedId)
                ? storage.savedIds().get(1)
                : storage.savedIds().get(0);
        assertThat(storage.has(survivingId)).isTrue();

        // DB contains exactly one entry (winner), with gridFsId == survivingId, filename one of the two
        var winners = files.findAllByOwnerId(owner, org.springframework.data.domain.PageRequest.of(0, 10), null)
                .collectList().block();
        assertThat(winners).hasSize(1);
        FileEntry fe = winners.get(0);
        assertThat(fe.getOwnerId()).isEqualTo(owner);
        assertThat(fe.getFilename()).isIn(f1, f2);
        assertThat(fe.getGridFsId()).isEqualTo(survivingId);
    }

    @DisplayName("3) records size reported by storage (simulate 2 GiB) without allocating")
    @Test
    void upload_recordsStorageReportedSize_simulate2GiB() {
        final String owner = "u-it-2gb";
        final String filename = "huge.bin";
        final long twoGiB = 2L * 1024 * 1024 * 1024;

        storage.setNextForcedSize(twoGiB);

        UploadFileResult res = service.upload(
                        owner, filename, Visibility.PRIVATE, null, "application/octet-stream", bodyOf("tiny"))
                .block();

        var saved = files.findById(res.fileId()).block();
        assertThat(saved).isNotNull();
        assertThat(saved.getSize()).isEqualTo(twoGiB);
        assertThat(saved.getFilename()).isEqualTo(filename);
        assertThat(saved.getOwnerId()).isEqualTo(owner);
    }

    // ----------------------- test beans --------------------

    @TestConfiguration
    static class TestBeans {

        @Bean @Primary
        RecordingReactiveStoragePort storagePort() {
            return new RecordingReactiveStoragePort();
        }

        // If your app doesn't already define a DownloadLinkQueryPort bean in tests,
        // you can add a simple in-memory version here. Otherwise, remove this bean.
        @Bean @Primary
        DownloadLinkQueryPort inMemoryDownloadLinks() {
            return new DownloadLinkQueryPort() {
                private final Map<String, ae.teletronics.storage.domain.model.DownloadLink> map = new ConcurrentHashMap<>();
                @Override public Mono<ae.teletronics.storage.domain.model.DownloadLink> save(ae.teletronics.storage.domain.model.DownloadLink dl) {
                    map.put(dl.getToken(), dl); return Mono.just(dl);
                }
                @Override public Mono<ae.teletronics.storage.domain.model.DownloadLink> findByToken(String token) {
                    return Mono.justOrEmpty(map.get(token));
                }
                @Override public Mono<Void> deleteAllByFileId(String fileId) {
                    map.values().removeIf(dl -> Objects.equals(dl.getFileId(), fileId));
                    return Mono.empty();
                }

                @Override
                public Mono<Void> incrementAccessCountByToken(String token) {
                    return null;
                }
            };
        }
    }

    /**
     * Reactive in-memory StoragePort for tests.
     * - Generates ids g1, g2, ...
     * - Stores bytes in-memory
     * - Records deletes
     * - Can force the next reported size (for 2 GiB test)
     */
    static class RecordingReactiveStoragePort implements ReactiveStoragePort {
        private final AtomicInteger seq = new AtomicInteger();
        private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
        private final List<String> deleted = Collections.synchronizedList(new ArrayList<>());
        private final List<String> saved = Collections.synchronizedList(new ArrayList<>());
        private final AtomicLong nextForcedSize = new AtomicLong(-1);

        @Override
        public Mono<StorageSaveResult> save(Flux<DataBuffer> body,
                                            String filename,
                                            @Nullable String contentType,
                                            Map<String, Object> metadata) {
            String gid = "g" + seq.incrementAndGet();
            return body
                    .reduce(new ByteArrayOutput(), (acc, buf) -> acc.write(buf.asByteBuffer()))
                    .map(out -> {
                        byte[] bytes = out.toByteArray();
                        blobs.put(gid, bytes);
                        saved.add(gid);
                        long forced = nextForcedSize.getAndSet(-1);
                        long size = (forced > -1) ? forced : bytes.length;
                        return new StorageSaveResult(gid, size);
                    });
        }

        @Override
        public Mono<Void> delete(String gridFsId) {
            deleted.add(gridFsId);
            blobs.remove(gridFsId);
            return Mono.empty();
        }

        void reset() {
            blobs.clear();
            deleted.clear();
            saved.clear();
            seq.set(0);
            nextForcedSize.set(-1);
        }

        @Override
        public Mono<ReactiveGridFsResource> open(String gridFsId) {
            return null;
        }

        // assertions helpers
        List<String> deletes() { return List.copyOf(deleted); }
        List<String> savedIds() { return List.copyOf(saved); }
        boolean has(String id) { return blobs.containsKey(id); }
        void setNextForcedSize(long bytes) { nextForcedSize.set(bytes); }

        // tiny byte accumulator to avoid keeping DataBuffers
        private static class ByteArrayOutput {
            private byte[] buf = new byte[0];
            ByteArrayOutput write(java.nio.ByteBuffer bb) {
                int add = bb.remaining();
                if (add == 0) return this;
                byte[] next = new byte[buf.length + add];
                System.arraycopy(buf, 0, next, 0, buf.length);
                bb.get(next, buf.length, add);
                buf = next;
                return this;
            }
            byte[] toByteArray() { return buf; }
        }
    }
}

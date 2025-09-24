package ae.teletronics.storage.application;

import ae.teletronics.storage.application.dto.UploadFileResult;
import ae.teletronics.storage.application.exceptions.DuplicateFileException;
import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.domain.model.DownloadLink;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.DownloadLinkQueryPort;
import ae.teletronics.storage.ports.FileEntryQueryPort;
import ae.teletronics.storage.ports.ReactiveStoragePort;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException; // <-- NEW
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ReactiveUploadService {

    private final FileEntryQueryPort files;     // exists/save/etc.
    private final DownloadLinkQueryPort links;  // create/save links
    private final ReactiveStoragePort storage;

    public ReactiveUploadService(FileEntryQueryPort files,
                                 DownloadLinkQueryPort links,
                                 ReactiveStoragePort storage) {
        this.files = files;
        this.links = links;
        this.storage = storage;
    }



    /**
     * Streams upload to GridFS while hashing, then persists metadata and creates a download token.
     */
    public Mono<UploadFileResult> upload(String ownerId,
                                         String filename,
                                         Visibility visibility,
                                         List<String> tags,
                                         @Nullable String contentType,
                                         Flux<DataBuffer> body) {

        final String filenameLc = filename.toLowerCase(Locale.ROOT);



        // 1) Tee to compute SHA-256 + sniff head + size (no full buffering)
        Tuple2<Flux<DataBuffer>, Mono<Tuple2<String, SniffResult>>> tee =
                teeWithSha256AndSniff(body, 16 * 1024); // 16 KB head
        Flux<DataBuffer> toStore = tee.getT1();
        Mono<Tuple2<String, SniffResult>> metaMono = tee.getT2();

        // 2) Store immediately (streaming) while meta is computed in-flight
        Mono<ReactiveStoragePort.StorageSaveResult> storeMono =
                storage.save(toStore, filename, contentType, Map.of("ownerId", ownerId));

        // 3) After store completes, combine results
        return Mono.zip(storeMono, metaMono)   // let DB unique index decide the winner
                .flatMap(tuple -> {
                    ReactiveStoragePort.StorageSaveResult stored = tuple.getT1();
                    String sha256 = tuple.getT2().getT1();
                    SniffResult sniff = tuple.getT2().getT2();

                    // Duplicate content check (owner-scoped)
                    return files.existsByOwnerIdAndContentSha256(ownerId, sha256).flatMap(dup -> {
                        if (dup) {
                            // Content duplicate detected early: cleanup blob and raise domain error
                            return storage.delete(stored.gridFsId())
                                    .then(Mono.error(new DuplicateFileException(DuplicateFileException.Kind.CONTENT, "File content already exists")));
                        }

                        // Decide final content type:
                        String provided = contentType;
                        String detected = detectContentType(sniff.head, filename);
                        String finalCt = isMeaningful(provided) ? provided
                                : isMeaningful(detected) ? detected
                                : "application/octet-stream";

                        // Prefer storage-reported size; fall back to sniff.size
                        long finalSize = (stored.size() >= 0) ? stored.size() : sniff.size;

                        // Persist metadata
                        FileEntry fe = new FileEntry();
                        fe.setOwnerId(ownerId);
                        fe.setFilename(filename);
                        fe.setFilenameLc(filenameLc);
                        fe.setVisibility(visibility);
                        fe.setTags(normalizeTags(tags));
                        fe.setContentType(finalCt);
                        fe.setSize(finalSize);
                        fe.setGridFsId(stored.gridFsId());
                        fe.setContentSha256(sha256);
                        fe.setCreatedAt(Instant.now());

                        return files.save(fe)
                                .onErrorResume(DuplicateKeyException.class, ex -> {
                                    // content duplicate -> cleanup blob
                                    if (isDupOn(ex, "uniq_owner_sha256")) {
                                        return storage.delete(stored.gridFsId()).onErrorResume(e -> Mono.empty())
                                                .then(Mono.error(new DuplicateFileException(DuplicateFileException.Kind.CONTENT,"File content already exists")));
                                    }
                                    // filename duplicate -> keep blob (surviving blob kept)
                                    if (isDupOn(ex, "uniq_owner_filename")) {
                                        return Mono.error(new DuplicateFileException(DuplicateFileException.Kind.FILENAME,"Filename already exists"));
                                    }
                                    // unknown duplicate -> keep blob, bubble up
                                    return Mono.error(ex);
                                })
                                .flatMap(saved -> {
                                    DownloadLink link = new DownloadLink(
                                            UUID.randomUUID().toString().replace("-", ""),
                                            saved.getId(),
                                            ownerId,
                                            null
                                    );
                                    return links.save(link)
                                            .map(savedLink -> new UploadFileResult(saved.getId(), savedLink.getToken(), saved.getFilename()));
                                });

                    });
                });

    }

    // --- helpers --------------------------------------------------------------

    private static boolean isDupOn(Throwable t, String indexName) {
        return t instanceof DuplicateKeyException
                && t.getMessage() != null
                && t.getMessage().contains("index: " + indexName);
    }

    @Nullable
    private static String detectContentType(byte[] head, String filenameHint) {
        try {
            org.apache.tika.Tika tika = new org.apache.tika.Tika(); // tika-core
            String detected = (filenameHint != null && !filenameHint.isBlank())
                    ? tika.detect(head, filenameHint)
                    : tika.detect(head);
            if (detected == null || detected.isBlank()) return null;
            String s = detected.trim().toLowerCase(Locale.ROOT);
            return "application/octet-stream".equals(s) ? null : detected;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isMeaningful(@Nullable String ct) {
        if (ct == null) return false;
        String s = ct.trim().toLowerCase(Locale.ROOT);
        return !s.isBlank() && !s.equals("application/octet-stream");
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null) return java.util.List.of();
        return tags.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT).trim())
                .toList();
    }

    /**
     * Returns (passthrough Flux, Mono<sha256>) computing sha while forwarding buffers.
     */
    private static Tuple2<Flux<DataBuffer>, Mono<String>> teeWithSha256(Flux<DataBuffer> in) {
        final MessageDigest digest = newDigest();
        final AtomicLong size = new AtomicLong();

        Flux<DataBuffer> out = in.doOnNext(db -> {
            ByteBuffer nio = db.asByteBuffer();
            size.addAndGet(nio.remaining());
            digest.update(nio);
        });

        Mono<String> sha = Mono.defer(() -> Mono.fromSupplier(
                () -> HexFormat.of().formatHex(digest.digest())
        ));

        return Tuples.of(out, sha);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static Tuple2<Flux<DataBuffer>, Mono<Tuple2<String, SniffResult>>> teeWithSha256AndSniff(
            Flux<DataBuffer> in, int sniffBytes) {

        final MessageDigest digest = newDigest();
        final AtomicLong size = new AtomicLong();
        final ByteArrayOutputStream headBuf = new ByteArrayOutputStream(Math.min(sniffBytes, 16384));
        final int sniffLimit = Math.max(0, sniffBytes);

        Flux<DataBuffer> out = in.doOnNext(db -> {
            // Hash without changing DataBuffer state
            ByteBuffer nio = db.asByteBuffer();
            size.addAndGet(nio.remaining());
            digest.update(nio.duplicate()); // advances the duplicate's position, not the DataBuffer

            // Copy leading bytes for sniffing, without touching DataBuffer readPosition
            if (headBuf.size() < sniffLimit) {
                ByteBuffer slice = nio.slice(); // relative to current position
                int toCopy = Math.min(slice.remaining(), sniffLimit - headBuf.size());
                if (toCopy > 0) {
                    slice.limit(toCopy);
                    byte[] tmp = new byte[toCopy];
                    slice.get(tmp);
                    headBuf.write(tmp, 0, toCopy);
                }
            }
        });

        Mono<Tuple2<String, SniffResult>> meta = Mono.fromSupplier(() -> {
            String sha = HexFormat.of().formatHex(digest.digest());
            return Tuples.of(sha, new SniffResult(headBuf.toByteArray(), size.get()));
        });

        return Tuples.of(out, meta);
    }

    static final class SniffResult {
        final byte[] head;   // first few KB to sniff type
        final long size;     // total bytes seen
        SniffResult(byte[] head, long size) { this.head = head; this.size = size; }
    }
}

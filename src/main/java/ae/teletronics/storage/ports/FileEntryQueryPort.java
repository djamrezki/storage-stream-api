package ae.teletronics.storage.ports;

import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.domain.model.FileEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface FileEntryQueryPort {
    Mono<FileEntry> findById(String id);

    // Listing with pagination + optional tag filter
    Flux<FileEntry> findAllByOwnerId(String ownerId, Pageable pageable, @Nullable String tag);
    Flux<FileEntry> findAllByVisibility(Visibility visibility, Pageable pageable, @Nullable String tag);

    // Totals for pagination UIs
    Mono<Long> countByOwnerId(String ownerId, @Nullable String tag);
    Mono<Long> countByVisibility(Visibility visibility, @Nullable String tag);

    // Also used by rename/upload flows
    Mono<Boolean> existsByOwnerIdAndFilenameLc(String ownerId, String filenameLc);
    Mono<Boolean> existsByOwnerIdAndContentSha256(String ownerId, String sha256);

    Mono<FileEntry> save(FileEntry entry);

    Flux<FileEntry> findByOwnerId(String ownerId, Pageable pageable);
    Flux<FileEntry> findByOwnerIdAndTag(String ownerId, String tag, Pageable pageable);

    Flux<FileEntry> findPublic(Pageable pageable);
    Flux<FileEntry> findPublicByTag(String tag, Pageable pageable);

    // --- NEW ---
    Mono<Void> deleteById(String id);
    Mono<Void> deleteAll();
}

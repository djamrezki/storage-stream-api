package ae.teletronics.storage.adapters.persistence.repo;

import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.domain.model.FileEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface FileEntryReactiveRepository extends ReactiveMongoRepository<FileEntry, String> {

    Mono<Boolean> existsByOwnerIdAndFilenameLc(String ownerId, String filenameLc);

    Mono<Boolean> existsByOwnerIdAndContentSha256(String ownerId, String sha256);

    Flux<FileEntry> findByOwnerId(String ownerId, Pageable pageable);
    Flux<FileEntry> findByOwnerIdAndTagsIgnoreCaseContaining(String ownerId, String tag, Pageable pageable);

    Flux<FileEntry> findByVisibility(Visibility visibility, Pageable pageable);
    Flux<FileEntry> findByVisibilityAndTagsIgnoreCaseContaining(Visibility visibility, String tag, Pageable pageable);



}

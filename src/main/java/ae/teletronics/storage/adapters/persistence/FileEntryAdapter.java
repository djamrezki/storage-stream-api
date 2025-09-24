package ae.teletronics.storage.adapters.persistence;

import ae.teletronics.storage.adapters.persistence.repo.FileEntryReactiveRepository;
import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.FileEntryQueryPort;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class FileEntryAdapter implements FileEntryQueryPort {

    private final FileEntryReactiveRepository repo;
    private final ReactiveMongoTemplate mongo;

    public FileEntryAdapter(FileEntryReactiveRepository repo,
                            ReactiveMongoTemplate mongo) {
        this.repo = repo;
        this.mongo = mongo;
    }

    @Override
    public Mono<FileEntry> findById(String id) {
        return repo.findById(id);
    }

    @Override
    public Mono<Boolean> existsByOwnerIdAndFilenameLc(String ownerId, String filenameLc) {
        return repo.existsByOwnerIdAndFilenameLc(ownerId, filenameLc);
    }

    @Override
    public Mono<Boolean> existsByOwnerIdAndContentSha256(String ownerId, String sha256) {
        return repo.existsByOwnerIdAndContentSha256(ownerId, sha256);
    }

    @Override
    public Mono<FileEntry> save(FileEntry entry) {
        return repo.save(entry);
    }

    @Override
    public Flux<FileEntry> findAllByOwnerId(String ownerId, Pageable pageable, @Nullable String tag) {
        Criteria c = Criteria.where("ownerId").is(ownerId);
        if (tag != null) {
            c = c.and("tags").is(tag);
        }
        Query q = Query.query(c).with(pageable);
        return mongo.find(q, FileEntry.class);
    }

    @Override
    public Flux<FileEntry> findAllByVisibility(Visibility visibility, Pageable pageable, @Nullable String tag) {
        Criteria c = Criteria.where("visibility").is(visibility);
        if (tag != null) {
            c = c.and("tags").is(tag);
        }
        Query q = Query.query(c).with(pageable);
        return mongo.find(q, FileEntry.class);
    }

    @Override
    public Mono<Long> countByOwnerId(String ownerId, @Nullable String tag) {
        Criteria c = Criteria.where("ownerId").is(ownerId);
        if (tag != null) {
            c = c.and("tags").is(tag);
        }
        return mongo.count(Query.query(c), FileEntry.class);
    }

    @Override
    public Mono<Long> countByVisibility(Visibility visibility, @Nullable String tag) {
        Criteria c = Criteria.where("visibility").is(visibility);
        if (tag != null) {
            c = c.and("tags").is(tag);
        }
        return mongo.count(Query.query(c), FileEntry.class);
    }

    @Override public Flux<FileEntry> findByOwnerIdAndTag(String ownerId, String tag, Pageable pageable) {
        return repo.findByOwnerIdAndTagsIgnoreCaseContaining(ownerId, tag, pageable);
    }

    @Override public Flux<FileEntry> findPublic(Pageable pageable) {
        return repo.findByVisibility(Visibility.PUBLIC, pageable);
    }
    @Override public Flux<FileEntry> findPublicByTag(String tag, Pageable pageable) {
        return repo.findByVisibilityAndTagsIgnoreCaseContaining(Visibility.PUBLIC, tag, pageable);
    }

    @Override public Flux<FileEntry> findByOwnerId(String ownerId, Pageable pageable) {
        return repo.findByOwnerId(ownerId, pageable);
    }

    @Override
    public Mono<Void> deleteById(String id) {
        return repo.deleteById(id);
    }

    @Override
    public Mono<Void> deleteAll() {
        return repo.deleteAll();
    }
}

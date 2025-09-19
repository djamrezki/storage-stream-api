package ae.teletronics.storage.adapters.persistence.repo;

import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.domain.model.FileEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Optional;

public interface FileEntryRepository extends MongoRepository<FileEntry, String> {

    // Uniqueness helpers
    Optional<FileEntry> findByOwnerIdAndFilenameLc(String ownerId, String filenameLc);
    Optional<FileEntry> findByOwnerIdAndContentSha256(String ownerId, String contentSha256);

    boolean existsByOwnerIdAndFilenameLc(String ownerId, String filenameLc);
    boolean existsByOwnerIdAndContentSha256(String ownerId, String contentSha256);

    // Listings (sorting via Pageable)
    Page<FileEntry> findByVisibility(Visibility visibility, Pageable pageable);
    Page<FileEntry> findByVisibilityAndTags(Visibility visibility, String tag, Pageable pageable);

    Page<FileEntry> findByOwnerId(String ownerId, Pageable pageable);
    Page<FileEntry> findByOwnerIdAndTags(String ownerId, String tag, Pageable pageable);

    // Union: PUBLIC OR ownerId = ?1
    @Query("{$or:[{'visibility': ?0}, {'ownerId': ?1}]}")
    Page<FileEntry> findAllVisibleToUser(Visibility visibility, String ownerId, Pageable pageable);

    // Union + tag: (tags contains ?2) AND (PUBLIC OR ownerId = ?1)
    @Query("{$and:[{'tags': ?2}, {$or:[{'visibility': ?0}, {'ownerId': ?1}]}]}")
    Page<FileEntry> findAllVisibleToUserWithTag(Visibility visibility, String ownerId, String tag, Pageable pageable);
}

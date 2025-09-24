package ae.teletronics.storage.domain.model;

import ae.teletronics.storage.domain.Visibility;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Mongo document that stores metadata about an uploaded file.
 * The actual bytes are stored via a StoragePort implementation.
 *
 * Uniqueness rules (scoped to the owner):
 *  - (ownerId + filenameLc) must be unique
 *  - (ownerId + contentSha256) must be unique
 */
@Document(collection = "files")
@CompoundIndexes({
        @CompoundIndex(name = "uniq_owner_filename", def = "{'ownerId': 1, 'filenameLc': 1}", unique = true),
        @CompoundIndex(name = "uniq_owner_sha256",  def = "{'ownerId': 1, 'contentSha256': 1}", unique = true)
})
public class FileEntry {

    @Id
    private String id;

    /** Owner of this file (provided via X-User-Id header). */
    @Indexed(name = "idx_owner")
    private String ownerId;

    /** Original filename as provided by the user (preserved for display). */
    private String filename;

    /** Lower-cased filename used for uniqueness checks + sorting. */
    private String filenameLc;

    /** Optional content type; may be set by detection post-upload. */
    private String contentType;

    /** File size in bytes. */
    private long size;

    /** PUBLIC or PRIVATE. */
    @Indexed(name = "idx_visibility")
    private Visibility visibility = Visibility.PRIVATE;

    /**
     * Normalized tags (stored lower-case, trimmed). Up to 5.
     * Multikey index to enable tag filtering.
     */
    @Indexed(name = "idx_tags")
    private List<String> tags = new ArrayList<>();

    /**
     * Storage object key (e.g., local FS path or opaque object id).
     * This is what the StoragePort uses to resolve the stream.
     */
    private String storageKey;

    /**
     * SHA-256 of the content for duplicate-by-content detection (per owner).
     * (Hex-encoded lowercase string)
     */
    private String contentSha256;

    /** Auditing & concurrency. */
    @CreatedDate
    @Indexed(name = "idx_createdAt") // useful for default sort & pagination
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    String gridFsId; // ObjectId string


    @Version
    private Long version;

    public FileEntry() { }

    public FileEntry(String ownerId,
                     String filename,
                     String contentType,
                     long size,
                     Visibility visibility,
                     List<String> tags,
                     String storageKey,
                     String contentSha256) {
        this.ownerId = ownerId;
        setFilename(filename);
        this.contentType = contentType;
        this.size = size;
        this.visibility = visibility == null ? Visibility.PRIVATE : visibility;
        setTags(tags);
        this.storageKey = storageKey;
        this.contentSha256 = contentSha256;
    }

    /* -------------------- Normalization helpers -------------------- */

    public void setFilename(String filename) {
        this.filename = filename;
        this.filenameLc = filename == null ? null : filename.toLowerCase();
    }

    public void setTags(List<String> tags) {
        this.tags = normalizeTags(tags);
    }

    private static List<String> normalizeTags(List<String> input) {
        if (input == null || input.isEmpty()) return new ArrayList<>();
        List<String> cleaned = new ArrayList<>(Math.min(input.size(), 5));
        for (String t : input) {
            if (t == null) continue;
            String nt = t.trim().toLowerCase();
            if (!nt.isEmpty() && !cleaned.contains(nt)) {
                cleaned.add(nt);
                if (cleaned.size() == 5) break; // enforce max 5
            }
        }
        return cleaned;
    }

    /* -------------------- Getters & setters -------------------- */

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getFilename() { return filename; }
    public String getFilenameLc() { return filenameLc; }

    public void setFilenameLc(String filenameLc) {
        this.filenameLc = filenameLc;
    }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public Visibility getVisibility() { return visibility; }
    public void setVisibility(Visibility visibility) { this.visibility = visibility; }

    public List<String> getTags() { return Collections.unmodifiableList(tags); }
    public void replaceTags(List<String> tags) { setTags(tags); }

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }

    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public String getGridFsId() {
        return gridFsId;
    }

    public void setGridFsId(String gridFsId) {
        this.gridFsId = gridFsId;
    }

    /* -------------------- Equality by id -------------------- */

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileEntry)) return false;
        FileEntry that = (FileEntry) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    @Override
    public String toString() {
        return "FileEntry{" +
                "id='" + id + '\'' +
                ", ownerId='" + ownerId + '\'' +
                ", filename='" + filename + '\'' +
                ", size=" + size +
                ", visibility=" + visibility +
                ", tags=" + tags +
                ", storageKey='" + storageKey + '\'' +
                '}';
    }
}

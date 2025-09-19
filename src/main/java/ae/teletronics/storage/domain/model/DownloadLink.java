package ae.teletronics.storage.domain.model;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a download token that maps to a FileEntry.
 * Resolve strictly by `token` (unguessable).
 * Expiry is enforced via TTL on `expiresAt`.
 */
@Document(collection = "download_links")
public class DownloadLink {

    @Id
    private String id;

    /** Unique, opaque token used in the public download URL. */
    @Indexed(name = "uniq_token", unique = true)
    private String token;

    /** Target file id (used only if you list or clean links by file). */
    private String fileId;

    /** Who created it (owner). Useful for auditing. */
    private String createdByUserId;

    /** Creation timestamp. */
    @CreatedDate
    private Instant createdAt;

    /**
     * Optional expiry. TTL index removes docs automatically once `expiresAt` passes.
     * expireAfterSeconds = 0 means "expire exactly at the timestamp value".
     */
    @Indexed(name = "ttl_expiresAt", expireAfterSeconds = 0)
    private Instant expiresAt;

    /** Number of successful downloads (for observability). */
    private long accessCount;

    public DownloadLink() {}

    public DownloadLink(String token, String fileId, String createdByUserId, Instant expiresAt) {
        this.token = token;
        this.fileId = fileId;
        this.createdByUserId = createdByUserId;
        this.expiresAt = expiresAt;
    }

    // Getters/setters

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public String getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(String createdByUserId) { this.createdByUserId = createdByUserId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public long getAccessCount() { return accessCount; }
    public void setAccessCount(long accessCount) { this.accessCount = accessCount; }
    public void incrementAccessCount() { this.accessCount++; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DownloadLink)) return false;
        DownloadLink that = (DownloadLink) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    @Override
    public String toString() {
        return "DownloadLink{" +
                "id='" + id + '\'' +
                ", token='" + token + '\'' +
                ", fileId='" + fileId + '\'' +
                ", createdByUserId='" + createdByUserId + '\'' +
                ", createdAt=" + createdAt +
                ", expiresAt=" + expiresAt +
                ", accessCount=" + accessCount +
                '}';
    }
}

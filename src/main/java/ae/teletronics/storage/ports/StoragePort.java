package ae.teletronics.storage.ports;

import java.io.IOException;
import java.util.Objects;

/**
 * Abstraction over the physical storage (local FS, S3, MinIO, etc.).
 * Handles streaming upload/download and deletion by an opaque storage key.
 */
public interface StoragePort {

    /**
     * Persist the binary stream and return the assigned storage key and size.
     *
     * @param source        re-openable stream source
     * @param suggestedKey  optional hint for backends (e.g., folder/owner/uuid); can be ignored
     */
    StorageSaveResult save(StreamSource source, String suggestedKey) throws IOException;

    /**
     * Open the stored object for reading and get its metadata.
     */
    StoredObject load(String storageKey) throws IOException;

    /**
     * Delete the stored object. Should be idempotent: no error if the object doesn't exist.
     */
    void delete(String storageKey) throws IOException;

    /**
         * Opaque storage key + size (in bytes) returned on save.
         */
        record StorageSaveResult(String storageKey, long size) {
            public StorageSaveResult(String storageKey, long size) {
                this.storageKey = Objects.requireNonNull(storageKey, "storageKey");
                this.size = size;
            }
        }

    /**
         * Object metadata + a source you can open for reading.
         */
        record StoredObject(String storageKey, long size, StreamSource source) {
            public StoredObject(String storageKey, long size, StreamSource source) {
                this.storageKey = Objects.requireNonNull(storageKey, "storageKey");
                this.size = size;
                this.source = Objects.requireNonNull(source, "source");
            }
        }
}

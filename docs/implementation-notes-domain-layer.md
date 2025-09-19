# Domain Layer — Notes & Rationale

### Uniqueness Rules (per owner)
- Enforced via compound indexes:
    - `(ownerId, filenameLc)` → prevents duplicate filenames (case-insensitive).
    - `(ownerId, contentSha256)` → prevents duplicate content.
- This allows different users to upload the same file without conflict.

### Case-insensitive Filename & Tags
- **Filename**: stored in both original and lowercase form (`filenameLc`) for display vs uniqueness checks.
- **Tags**: normalized to lowercase and trimmed. Stored as a list with a max of 5 unique entries.
- Ensures that `TAG` and `tAg` are treated as the same tag.

### Auditing & Concurrency
- `@CreatedDate`, `@LastModifiedDate`, and `@Version` annotations enable:
    - Automatic timestamps.
    - Optimistic locking.
    - Stable sorting for file listings.

### DownloadLink TTL-ready
- `expiresAt` supports optional expiration.
- Mongo TTL index (`expireAfterSeconds=0`) can be applied so expired download links are automatically removed.

### Separation of Concerns
- **FileEntry**: metadata only, no file content (delegated to `StoragePort`).
- **DownloadLink**: one-way mapping from a unique token to a `FileEntry`.
- Clean separation ensures flexibility in storage backends and scalability.

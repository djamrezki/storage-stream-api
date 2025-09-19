package ae.teletronics.storage.application;

import ae.teletronics.storage.adapters.persistence.repo.DownloadLinkRepository;
import ae.teletronics.storage.adapters.persistence.repo.FileEntryRepository;
import ae.teletronics.storage.application.dto.UploadFileCommand;
import ae.teletronics.storage.application.dto.UploadFileResult;
import ae.teletronics.storage.application.exceptions.DuplicateFileException;
import ae.teletronics.storage.application.exceptions.VirusDetectedException;
import ae.teletronics.storage.application.util.Hashing;
import ae.teletronics.storage.application.util.TokenGenerator;
import ae.teletronics.storage.domain.model.DownloadLink;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class UploadFileService {

    private final FileEntryRepository files;
    private final DownloadLinkRepository links;
    private final StoragePort storage;
    private final FileTypeDetector typeDetector;
    private final VirusScanner virusScanner;

    public UploadFileService(FileEntryRepository files,
                             DownloadLinkRepository links,
                             StoragePort storage,
                             FileTypeDetector typeDetector,
                             VirusScanner virusScanner) {
        this.files = files;
        this.links = links;
        this.storage = storage;
        this.typeDetector = typeDetector;
        this.virusScanner = virusScanner;
    }

    public UploadFileResult upload(UploadFileCommand cmd) throws IOException {
        // Basic validation
        if (cmd == null || !StringUtils.hasText(cmd.ownerId()) || !StringUtils.hasText(cmd.filename())) {
            throw new IllegalArgumentException("ownerId and filename are required");
        }
        final String ownerId = cmd.ownerId();
        final String filename = cmd.filename();
        final String filenameLc = filename.toLowerCase(Locale.ROOT);

        // Fast path: duplicate filename check
        if (files.existsByOwnerIdAndFilenameLc(ownerId, filenameLc)) {
            throw new DuplicateFileException(DuplicateFileException.Kind.FILENAME,
                    "A file with this name already exists for this user");
        }

        // Compute content hash (streamed)
        final String sha256 = Hashing.sha256Hex(cmd.source());

        // Duplicate-by-content check
        if (files.existsByOwnerIdAndContentSha256(ownerId, sha256)) {
            throw new DuplicateFileException(DuplicateFileException.Kind.CONTENT,
                    "An identical file already exists for this user");
        }

        // Virus scan (can be NoOp)
        var report = virusScanner.scan(cmd.source());
        switch (report.getVerdict()) {
            case INFECTED -> throw new VirusDetectedException("Upload rejected: " + report.getDetails());
            case ERROR -> throw new VirusDetectedException("Virus scan error: " + report.getDetails());
            default -> { /* CLEAN */ }
        }

        // Persist bytes to storage
        final String suggestedKey = ownerId + "/" + UUID.randomUUID().toString().replace("-", "");
        StoragePort.StorageSaveResult save = storage.save(cmd.source(), suggestedKey);

        // Content type: use header if non-empty and not octet-stream; otherwise detect
        String finalContentType = null;
        if (StringUtils.hasText(cmd.contentTypeHeader())
                && !"application/octet-stream".equalsIgnoreCase(cmd.contentTypeHeader())) {
            finalContentType = cmd.contentTypeHeader();
        }
        if (!StringUtils.hasText(finalContentType)) {
            Optional<String> detected = typeDetector.detect(cmd.source(), filename);
            finalContentType = detected.orElse(null);
        }

        // Build and persist FileEntry
        // Guard to avoid partial index in mongo and then unique constraint rejection
        if (!StringUtils.hasText(sha256)) {
            throw new IllegalStateException("contentSha256 must be set before saving");
        }


        FileEntry entry = new FileEntry(
                ownerId,
                filename,
                finalContentType,
                save.size(),
                cmd.visibility(),
                cmd.tags(),
                save.storageKey(),
                sha256
        );

        try {
            entry = files.save(entry);
        } catch (DuplicateKeyException dke) {
            // Clean up stored object to avoid orphan
            try { storage.delete(save.storageKey()); } catch (Exception ignored) {}
            // Determine which unique constraint was hit
            String msg = dke.getMessage() != null ? dke.getMessage() : "";
            if (msg.contains("uniq_owner_filename")) {
                throw new DuplicateFileException(DuplicateFileException.Kind.FILENAME, "Filename already exists");
            } else if (msg.contains("uniq_owner_sha256")) {
                throw new DuplicateFileException(DuplicateFileException.Kind.CONTENT, "File content already exists");
            }
            throw dke;
        }

        // Create a unique download token (retry on rare collision)
        String token = null;
        for (int i = 0; i < 5; i++) {
            String candidate = TokenGenerator.randomToken(32);
            try {
                links.save(new DownloadLink(candidate, entry.getId(), ownerId, null));
                token = candidate;
                break;
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
                // collision, retry
            }
        }
        if (token == null) {
            // best-effort fallback
            token = TokenGenerator.randomToken(40);
            links.save(new DownloadLink(token, entry.getId(), ownerId, null));
        }

        return new UploadFileResult(entry.getId(), token);
    }

    // Small convenience for tests
    public static List<String> normalizeTags(List<String> tags) {
        // FileEntry already normalizes, this is just exposed for test clarity
        FileEntry tmp = new FileEntry();
        tmp.replaceTags(tags);
        return tmp.getTags();
    }
}

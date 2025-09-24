package ae.teletronics.storage.application;


import ae.teletronics.storage.application.exceptions.DuplicateFileException;
import ae.teletronics.storage.application.exceptions.NotFoundException;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.FileEntryQueryPort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Locale;

@Service
public class RenameFileServiceReactive {

    private final FileEntryQueryPort files;

    public RenameFileServiceReactive(FileEntryQueryPort files) {
        this.files = files;
    }

    /**
     * Rename a file owned by {@code ownerId} to {@code newFilename}.
     * - Enforces (ownerId, filenameLc) uniqueness.
     * - Case-insensitive comparison for conflicts.
     * - Returns the updated FileEntry.
     */
    public Mono<FileEntry> rename(String ownerId, String fileId, String newFilename) {
        final String normalized = normalizeFilename(newFilename);
        final String normalizedLc = normalized.toLowerCase(Locale.ROOT);

        return files.findById(fileId)
                .switchIfEmpty(Mono.error(new NotFoundException("File not found")))
                .flatMap(existing -> {
                    // Avoid leaking existence of foreign files: treat as not found if owner mismatch
                    if (!ownerId.equals(existing.getOwnerId())) {
                        return Mono.error(new NotFoundException("File not found"));
                    }

                    // If lower-case is identical, it's either a no-op or a case-only change; allow it
                    if (normalizedLc.equals(existing.getFilenameLc())) {
                        boolean anyChange = !normalized.equals(existing.getFilename());
                        if (!anyChange) {
                            return Mono.just(existing); // no-op
                        }
                        existing.setFilename(normalized);
                        return files.save(existing);
                    }

                    // Otherwise enforce uniqueness on (ownerId, filenameLc)
                    return files.existsByOwnerIdAndFilenameLc(ownerId, normalizedLc)
                            .flatMap(exists -> {
                                if (exists) {
                                    return Mono.error(new DuplicateFileException(DuplicateFileException.Kind.FILENAME,"Filename already exists"));
                                }
                                existing.setFilename(normalized);
                                existing.setFilenameLc(normalizedLc);
                                return files.save(existing);
                            });
                });
    }

    // ---- helpers ----

    private static String normalizeFilename(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("New filename must not be blank");
        }
        // Optional: tighten validation (no path separators)
        if (name.contains("/") || name.contains("\\"))
            throw new IllegalArgumentException("Filename must not contain path separators");
        // Trim surrounding whitespace
        return name.trim();
    }
}

package ae.teletronics.storage.application;

import ae.teletronics.storage.adapters.persistence.repo.DownloadLinkRepository;
import ae.teletronics.storage.adapters.persistence.repo.FileEntryRepository;
import ae.teletronics.storage.application.exceptions.ForbiddenOperationException;
import ae.teletronics.storage.application.exceptions.NotFoundException;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.StoragePort;
import org.springframework.stereotype.Service;

@Service
public class DeleteFileService {

    private final FileEntryRepository files;
    private final DownloadLinkRepository links;
    private final StoragePort storage;

    public DeleteFileService(FileEntryRepository files,
                             DownloadLinkRepository links,
                             StoragePort storage) {
        this.files = files;
        this.links = links;
        this.storage = storage;
    }

    public void delete(String ownerId, String fileId) {
        FileEntry entry = files.findById(fileId)
                .orElseThrow(() -> new NotFoundException("File not found"));
        if (!entry.getOwnerId().equals(ownerId)) {
            throw new ForbiddenOperationException("You are not the owner of this file");
        }

        // Delete bytes first; if it fails, propagate (keeps metadata so user can retry)
        try {
            storage.delete(entry.getStorageKey());
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete stored object", e);
        }

        // Remove metadata + associated download links
        files.deleteById(fileId);
        links.deleteByFileId(fileId);
    }
}

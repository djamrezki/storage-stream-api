package ae.teletronics.storage.application;

import ae.teletronics.storage.adapters.persistence.repo.DownloadLinkRepository;
import ae.teletronics.storage.adapters.persistence.repo.FileEntryRepository;
import ae.teletronics.storage.application.dto.DownloadResult;
import ae.teletronics.storage.application.exceptions.NotFoundException;
import ae.teletronics.storage.domain.model.DownloadLink;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.StoragePort;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class DownloadService {

    private final DownloadLinkRepository links;
    private final FileEntryRepository files;
    private final StoragePort storage;

    public DownloadService(DownloadLinkRepository links,
                           FileEntryRepository files,
                           StoragePort storage) {
        this.links = links;
        this.files = files;
        this.storage = storage;
    }

    public DownloadResult byToken(String token) throws IOException {
        DownloadLink link = links.findByToken(token)
                .orElseThrow(() -> new NotFoundException("Download link not found"));
        FileEntry entry = files.findById(link.getFileId())
                .orElseThrow(() -> new NotFoundException("File not found for token"));

        StoragePort.StoredObject obj = storage.load(entry.getStorageKey());
        // async-safe increment (atomic $inc)
        links.incrementAccessCountByToken(token);

        return new DownloadResult(
                entry.getFilename(),
                entry.getContentType() != null ? entry.getContentType() : "application/octet-stream",
                obj.size(),
                obj.source()
        );
    }
}

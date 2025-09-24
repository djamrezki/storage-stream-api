package ae.teletronics.storage.application;

import ae.teletronics.storage.application.exceptions.NotFoundException;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.DownloadLinkQueryPort;
import ae.teletronics.storage.ports.FileEntryQueryPort;
import ae.teletronics.storage.ports.ReactiveStoragePort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class DeleteFileServiceReactive {

    private final FileEntryQueryPort files;
    private final DownloadLinkQueryPort links;
    private final ReactiveStoragePort storage;

    public DeleteFileServiceReactive(FileEntryQueryPort files,
                                     DownloadLinkQueryPort links,
                                     ReactiveStoragePort storage) {
        this.files = files;
        this.links = links;
        this.storage = storage;
    }

    /**
     * Deletes a file owned by {@code ownerId}:
     * 1) Verify ownership
     * 2) Delete all DownloadLink docs for this file
     * 3) Delete GridFS content
     * 4) Delete FileEntry metadata (last, to avoid dangling metadata)
     */
    public Mono<Void> delete(String ownerId, String fileId) {
        return files.findById(fileId)
                .switchIfEmpty(Mono.error(new NotFoundException("File not found")))
                .flatMap((FileEntry fe) -> {
                    if (!ownerId.equals(fe.getOwnerId())) {
                        return Mono.error(new NotFoundException("File not found")); // don't leak existence
                    }
                    return links.deleteAllByFileId(fileId)     // best-effort cascade of tokens
                            .then(storage.delete(fe.getGridFsId())) // remove blob
                            .then(files.deleteById(fileId));        // remove metadata last
                });
    }
}

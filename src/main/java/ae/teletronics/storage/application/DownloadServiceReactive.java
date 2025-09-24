package ae.teletronics.storage.application;

import ae.teletronics.storage.application.dto.DownloadResult;
import ae.teletronics.storage.application.exceptions.NotFoundException;
import ae.teletronics.storage.domain.model.DownloadLink;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.DownloadLinkQueryPort;
import ae.teletronics.storage.ports.FileEntryQueryPort;
import ae.teletronics.storage.ports.ReactiveStoragePort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class DownloadServiceReactive {

    private final DownloadLinkQueryPort links;
    private final FileEntryQueryPort files;
    private final ReactiveStoragePort storage;

    public DownloadServiceReactive(DownloadLinkQueryPort links,
                                   FileEntryQueryPort files,
                                   ReactiveStoragePort storage) {
        this.links = links;
        this.files = files;
        this.storage = storage;
    }

    /**
     * Resolve a download token to a streaming body + headers (in DTO form).
     * No HTTP/web concerns here (hexagonal clean).
     */
    public Mono<DownloadResult> byToken(String token) {
        return links.findByToken(token)
                .switchIfEmpty(Mono.error(new NotFoundException("Invalid or expired link")))
                .flatMap((DownloadLink link) ->
                        files.findById(link.getFileId())
                                .switchIfEmpty(Mono.error(new NotFoundException("File not found for token")))
                                .flatMap((FileEntry fe) ->
                                        storage.open(fe.getGridFsId())
                                                .flatMap(res -> {
                                                    // increment counter but do not break the stream if it fails
                                                    return links.incrementAccessCountByToken(token)
                                                            .onErrorResume(e -> Mono.empty())
                                                            .thenReturn(new DownloadResult(
                                                                    fe.getFilename(),
                                                                    fe.getContentType() != null
                                                                            ? fe.getContentType()
                                                                            : MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                                                    // ReactiveGridFsResource::getDownloadStream yields Flux<DataBuffer>
                                                                    res.getDownloadStream()
                                                            ));
                                                })
                                )
                );
    }
}

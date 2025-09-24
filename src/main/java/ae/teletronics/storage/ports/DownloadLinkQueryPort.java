package ae.teletronics.storage.ports;

import ae.teletronics.storage.domain.model.DownloadLink;
import reactor.core.publisher.Mono;

public interface DownloadLinkQueryPort {
    Mono<DownloadLink> findByToken(String token);
    Mono<Void> incrementAccessCountByToken(String token);
    Mono<DownloadLink> save(DownloadLink link);
    Mono<Void> deleteAllByFileId(String fileId);

}
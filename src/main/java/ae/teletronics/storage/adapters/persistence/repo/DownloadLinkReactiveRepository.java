package ae.teletronics.storage.adapters.persistence.repo;

import ae.teletronics.storage.domain.model.DownloadLink;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Mono;

public interface DownloadLinkReactiveRepository extends ReactiveMongoRepository<DownloadLink, String> {

    Mono<DownloadLink> findByToken(String token);
    Mono<Void> deleteAllByFileId(String fileId);

}

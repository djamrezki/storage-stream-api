package ae.teletronics.storage.adapters.persistence;

import ae.teletronics.storage.adapters.persistence.repo.DownloadLinkReactiveRepository;
import ae.teletronics.storage.domain.model.DownloadLink;
import ae.teletronics.storage.ports.DownloadLinkQueryPort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class DownloadLinkAdapter implements DownloadLinkQueryPort {
    private final DownloadLinkReactiveRepository repo;
    private final ReactiveMongoTemplate mongo;

    public DownloadLinkAdapter(DownloadLinkReactiveRepository repo, ReactiveMongoTemplate mongo){
        this.repo = repo;
        this.mongo = mongo;
    }

    public Mono<DownloadLink> findByToken(String token) {
        return repo.findByToken(token);
    }

    public Mono<Void> incrementAccessCountByToken(String token) {
        return mongo.updateFirst(
                Query.query(Criteria.where("token").is(token)),
                new Update().inc("accessCount", 1),
                DownloadLink.class
        ).then();
    }

    @Override
    public Mono<DownloadLink> save(DownloadLink link) {
        return repo.save(link);
    }

    @Override
    public Mono<Void> deleteAllByFileId(String fileId) { return repo.deleteAllByFileId(fileId); }

}

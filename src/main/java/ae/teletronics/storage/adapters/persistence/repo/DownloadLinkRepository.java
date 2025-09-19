package ae.teletronics.storage.adapters.persistence.repo;

import ae.teletronics.storage.domain.model.DownloadLink;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface DownloadLinkRepository
        extends MongoRepository<DownloadLink, String>, DownloadLinkRepositoryCustom {

    Optional<DownloadLink> findByToken(String token);

    long deleteByFileId(String fileId);
}

package ae.teletronics.storage.adapters.persistence.repo;

import ae.teletronics.storage.domain.model.DownloadLink;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

public class DownloadLinkRepositoryImpl implements DownloadLinkRepositoryCustom {

    @Autowired
    MongoTemplate mongoTemplate;

    @Override
    public void incrementAccessCountByToken(String token) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("token").is(token)),
                new Update().inc("accessCount", 1),
                DownloadLink.class
        );
    }
}

package ae.teletronics.storage.adapters.persistence;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;


/**
 * Enables auditing and ensures TTL index for DownloadLink.expiresAt.
 */
@Configuration
@EnableMongoAuditing
public class MongoPersistenceConfig {


}

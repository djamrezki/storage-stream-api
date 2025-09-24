package ae.teletronics.storage.adapters;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.gridfs.ReactiveGridFsTemplate;

@Configuration
public class MongoReactiveConfig {
    @Bean
    public ReactiveGridFsTemplate reactiveGridFsTemplate(ReactiveMongoDatabaseFactory dbf,
                                                         MappingMongoConverter converter) {
        return new ReactiveGridFsTemplate(dbf, converter);
    }
}

package ae.teletronics.storage.adapters.storage;

import ae.teletronics.storage.ports.ReactiveStoragePort;
import org.bson.types.ObjectId;
import org.springframework.core.io.buffer.DataBuffer;
import org.bson.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.ReactiveGridFsResource;
import org.springframework.data.mongodb.gridfs.ReactiveGridFsTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class GridFsReactiveAdapter implements ReactiveStoragePort {
    private final ReactiveGridFsTemplate gridFs;

    public GridFsReactiveAdapter(ReactiveGridFsTemplate gridFs){
        this.gridFs = gridFs;
    }

    @Override
    public Mono<StorageSaveResult> save(Flux<DataBuffer> content,
                                        String filename,
                                        String contentType,
                                        Map<String,Object> metadata) {
// Directly stream to GridFS; no aggregation in memory
        return gridFs.store(content, filename, contentType, new Document(metadata))
                .map(id -> new StorageSaveResult(id.toString(), -1L));
    }

    public Mono<ReactiveGridFsResource> open(String gridFsId) {
        return gridFs.findOne(Query.query(Criteria.where("_id").is(new ObjectId(gridFsId))))
                .flatMap(gridFs::getResource);
    }

    @Override public Mono<Void> delete(String id) {
        return gridFs.delete(Query.query(Criteria.where("_id").is(new ObjectId(id))));
    }
}

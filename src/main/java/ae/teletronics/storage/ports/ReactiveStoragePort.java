package ae.teletronics.storage.ports;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

import org.springframework.data.mongodb.gridfs.ReactiveGridFsResource;

public interface ReactiveStoragePort {
    Mono<StorageSaveResult> save(Flux<DataBuffer> content, String filename,
                                 @Nullable String contentType, Map<String, Object> metadata);

    Mono<ReactiveGridFsResource> open(String gridFsId);  // <-- reactive type
    Mono<Void> delete(String gridFsId);
    record StorageSaveResult(String gridFsId, long size) {}
}

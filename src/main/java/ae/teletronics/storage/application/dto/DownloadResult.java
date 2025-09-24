package ae.teletronics.storage.application.dto;

import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;

public record DownloadResult(
        String filename,
        String contentType,
        Flux<DataBuffer> body
) {}

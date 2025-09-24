package ae.teletronics.storage.adapters.web;

import ae.teletronics.storage.application.DownloadServiceReactive;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;


@RestController
@RequestMapping("/files")
public class DownloadController {

    private final DownloadServiceReactive downloads;

    public DownloadController(DownloadServiceReactive downloads){
        this.downloads = downloads;
    }

    @GetMapping("/download/{token}")
    public Mono<Void> download(@PathVariable String token, ServerHttpResponse response) {
        return downloads.byToken(token).flatMap(r -> {
            response.getHeaders().set(HttpHeaders.CONTENT_TYPE, r.contentType());
            response.getHeaders().setContentDisposition(
                    ContentDisposition.attachment().filename(r.filename()).build());

            // Content-Length is optional; if you want it and can get it reliably, set it here.
            // Otherwise stream chunked:
            return response.writeWith(r.body());
        });
    }
}

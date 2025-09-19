package ae.teletronics.storage.adapters.web;

import ae.teletronics.storage.application.DownloadService;
import ae.teletronics.storage.application.dto.DownloadResult;
import org.springframework.http.*;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping
public class DownloadController {

    private final DownloadService downloadService;

    public DownloadController(DownloadService downloadService) {
        this.downloadService = downloadService;
    }

    @GetMapping("/download/{token}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String token) throws Exception {
        DownloadResult res = downloadService.byToken(token);

        String filename = res.filename();
        String contentType = res.contentType();
        long size = res.size();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        headers.setContentLength(size);
        headers.set("X-Content-Type-Options", "nosniff");

        // RFC 5987 filename* for UTF-8 + a basic filename fallback
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + safeAscii(filename) + "\"; filename*=UTF-8''" + encoded);

        StreamingResponseBody body = outputStream -> {
            try (InputStream in = res.source().openStream()) {
                StreamUtils.copy(in, outputStream); // streams with internal buffer
            }
        };

        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    private static String safeAscii(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(c < 32 || c > 126 ? '_' : c);
        }
        return sb.toString();
    }
}

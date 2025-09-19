package ae.teletronics.storage.adapters.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds relative download links based on a configurable base URL (relative path).
 * Example: baseUrl="/" -> "/download/{token}"
 *          baseUrl="/api" -> "/api/download/{token}"
 */
@Component
public class DownloadLinkBuilder {

    private final String baseUrl;

    public DownloadLinkBuilder(@Value("${storage.base-url:/}") String baseUrl) {
        String b = baseUrl == null ? "/" : baseUrl.trim();
        if (!b.startsWith("/")) b = "/" + b;
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.baseUrl = b;
    }

    public String build(String token) {
        return baseUrl + "/download/" + token;
    }
}

package ae.teletronics.storage.application.dto;

import ae.teletronics.storage.ports.StreamSource;

public record DownloadResult(
        String filename,
        String contentType,
        long size,
        StreamSource source
) {}

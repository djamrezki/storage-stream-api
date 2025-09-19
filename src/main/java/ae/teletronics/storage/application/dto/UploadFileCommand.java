package ae.teletronics.storage.application.dto;

import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.ports.StreamSource;

import java.util.List;

public record UploadFileCommand(
        String ownerId,
        String filename,
        Visibility visibility,
        List<String> tags,
        String contentTypeHeader, // may be null or "application/octet-stream"
        StreamSource source
) {}

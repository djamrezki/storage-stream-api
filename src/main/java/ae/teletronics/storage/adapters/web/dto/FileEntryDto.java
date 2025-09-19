package ae.teletronics.storage.adapters.web.dto;

import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.domain.model.FileEntry;

import java.time.Instant;
import java.util.List;

public record FileEntryDto(
        String id,
        String filename,
        String contentType,
        long size,
        Visibility visibility,
        List<String> tags,
        Instant createdAt
) {
    public static FileEntryDto from(FileEntry e) {
        return new FileEntryDto(
                e.getId(),
                e.getFilename(),
                e.getContentType(),
                e.getSize(),
                e.getVisibility(),
                e.getTags(),
                e.getCreatedAt()
        );
    }
}

package ae.teletronics.storage.application;

import ae.teletronics.storage.adapters.persistence.repo.FileEntryRepository;
import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.domain.model.FileEntry;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class ListFilesService {

    private final FileEntryRepository files;

    public ListFilesService(FileEntryRepository files) { this.files = files; }

    public Page<FileEntry> listPublic(String tag, int page, int size, String sortBy, boolean asc) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(asc ? Sort.Direction.ASC : Sort.Direction.DESC, mapSort(sortBy)));
        if (tag != null && !tag.isBlank()) {
            return files.findByVisibilityAndTags(Visibility.PUBLIC, tag.toLowerCase(Locale.ROOT), pageable);
        }
        return files.findByVisibility(Visibility.PUBLIC, pageable);
    }

    public Page<FileEntry> listMine(String ownerId, String tag, int page, int size, String sortBy, boolean asc) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(asc ? Sort.Direction.ASC : Sort.Direction.DESC, mapSort(sortBy)));
        if (tag != null && !tag.isBlank()) {
            return files.findByOwnerIdAndTags(ownerId, tag.toLowerCase(Locale.ROOT), pageable);
        }
        return files.findByOwnerId(ownerId, pageable);
    }

    public Page<FileEntry> listAll(String ownerId, String tag, int page, int size, String sortBy, boolean asc) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(asc ? Sort.Direction.ASC : Sort.Direction.DESC, mapSort(sortBy)));
        if (tag != null && !tag.isBlank()) {
            return files.findAllVisibleToUserWithTag(Visibility.PUBLIC, ownerId, tag.toLowerCase(Locale.ROOT), pageable);
        }
        return files.findAllVisibleToUser(Visibility.PUBLIC, ownerId, pageable);
    }

    private String mapSort(String sortBy) {
        if (sortBy == null) return "createdAt";
        return switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "filename", "name" -> "filenameLc";      // case-insensitive
            case "upload", "date", "createdat" -> "createdAt";
            case "tag", "tags" -> "tags";
            case "contenttype", "type" -> "contentType";
            case "size", "filesize" -> "size";
            default -> "createdAt";
        };
    }
}

package ae.teletronics.storage.application;

import ae.teletronics.storage.application.dto.PagedResult;
import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.FileEntryQueryPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ListFilesServiceReactive {

    private final FileEntryQueryPort files;

    public ListFilesServiceReactive(FileEntryQueryPort files) {
        this.files = files;
    }

    /**
     * List files owned by the given user, with optional tag filter and sort.
     * Returns a PagedResult containing a Flux of items and a Mono total count.
     */
    public Mono<PagedResult<FileEntry>> listMine(String ownerId,
                                                 int page,
                                                 int size,
                                                 @Nullable String tag,
                                                 @Nullable String sortExpr) {
        Pageable pageable = toPageable(page, size, sortExpr);
        Flux<FileEntry> rows = files.findAllByOwnerId(ownerId, pageable, normalizeTag(tag));
        Mono<Long> total = files.countByOwnerId(ownerId, normalizeTag(tag));
        return Mono.just(new PagedResult<>(rows, total, page, size));
    }

    /**
     * List PUBLIC files (visible to everyone), with optional tag filter and sort.
     */
    public Mono<PagedResult<FileEntry>> listPublic(int page,
                                                   int size,
                                                   @Nullable String tag,
                                                   @Nullable String sortExpr) {
        Pageable pageable = toPageable(page, size, sortExpr);
        Flux<FileEntry> rows = files.findAllByVisibility(Visibility.PUBLIC, pageable, normalizeTag(tag));
        Mono<Long> total = files.countByVisibility(Visibility.PUBLIC, normalizeTag(tag));
        return Mono.just(new PagedResult<>(rows, total, page, size));
    }

    // ---- helpers ----

    private static Pageable toPageable(int page, int size, @Nullable String sortExpr) {
        if (page < 0) page = 0;
        if (size <= 0) size = 20;

        Sort sort = parseSort(sortExpr);
        return PageRequest.of(page, size, sort);
    }

    /**
     * Accepts inputs like:
     *  - "uploadedAt,desc"
     *  - "uploadedAt,asc"
     *  - "filename,asc"
     * Defaults to uploadedAt desc.
     */
    private static Sort parseSort(@Nullable String sortExpr) {
        if (sortExpr == null || sortExpr.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "uploadedAt");
        }
        String[] parts = sortExpr.split(",", 2);
        String field = parts[0].trim();
        Sort.Direction dir = (parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim()))
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        if (field.isEmpty()) field = "uploadedAt";
        return Sort.by(dir, field);
    }

    private static @Nullable String normalizeTag(@Nullable String tag) {
        if (tag == null) return null;
        String t = tag.trim().toLowerCase();
        return t.isEmpty() ? null : t;
    }
}

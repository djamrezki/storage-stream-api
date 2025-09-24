package ae.teletronics.storage.adapters.web;

import ae.teletronics.storage.adapters.web.dto.FileEntryDto;
import ae.teletronics.storage.application.DeleteFileServiceReactive;
import ae.teletronics.storage.application.ReactiveUploadService;
import ae.teletronics.storage.application.exceptions.NotFoundException;
import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.FileEntryQueryPort;
import ae.teletronics.storage.ports.DownloadLinkQueryPort;
import ae.teletronics.storage.ports.ReactiveStoragePort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@RestController
@RequestMapping
@Validated
public class ReactiveFileController {

    private final ReactiveUploadService uploadService;
    private final DeleteFileServiceReactive deleteService;
    private final FileEntryQueryPort files;
    private final DownloadLinkQueryPort links;
    private final ReactiveStoragePort storage;

    public ReactiveFileController(ReactiveUploadService uploadService,
                                  DeleteFileServiceReactive deleteService,
                                  FileEntryQueryPort files,
                                  DownloadLinkQueryPort links,
                                  ReactiveStoragePort storage) {
        this.uploadService = uploadService;
        this.deleteService = deleteService;
        this.files = files;
        this.links = links;
        this.storage = storage;
    }

    @PostMapping(path = "/files",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<FileEntryDto> upload(@RequestHeader("X-User-Id") String ownerId,
                                     @RequestPart("file") FilePart file,
                                     @RequestPart(name = "filename", required = false) String filename,
                                     @RequestPart(name = "visibility", required = false) String visibilityStr,
                                     @RequestPart(name = "tag", required = false) Flux<String> tagParts) {

        final String effectiveFilename = Optional.ofNullable(filename).filter(s -> !s.isBlank())
                .orElse(file.filename());
        final Visibility visibility = parseVisibility(visibilityStr).orElse(Visibility.PRIVATE);
        final MediaType contentType = Optional.ofNullable(file.headers().getContentType())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);

        Mono<List<String>> tagsMono = (tagParts == null ? Flux.<String>empty() : tagParts)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .collectList();

        return tagsMono.flatMap(safeTags ->
                uploadService.upload(
                        ownerId,
                        effectiveFilename,
                        visibility,
                        safeTags,
                        contentType.toString(),
                        file.content()
                ).flatMap(r -> files.findById(r.fileId()).map(FileEntryDto::from))
        );
    }

    private Optional<Visibility> parseVisibility(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            return Optional.of(Visibility.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException iae) {
            return Optional.of(Visibility.PRIVATE);
        }
    }

    static final class RenameRequest {
        public String filename;
    }

    @PatchMapping(path = "/files/{id}/rename", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<FileEntryDto> rename(@RequestHeader("X-User-Id") String ownerId,
                                     @PathVariable String id,
                                     @RequestBody RenameRequest body) {
        final String newName = Optional.ofNullable(body.filename)
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("filename is required"));

        return files.findById(id)
                .switchIfEmpty(Mono.error(new NotFoundException("File not found")))
                .flatMap(fe -> {
                    if (!ownerId.equals(fe.getOwnerId())) return Mono.error(new NotFoundException("File not found"));
                    fe.setFilename(newName);
                    fe.setFilenameLc(newName.toLowerCase(Locale.ROOT));
                    fe.setUpdatedAt(Instant.now());
                    return files.save(fe);
                })
                .map(FileEntryDto::from);
    }

    // ---- List "my files" (paged/sorted/filtered by tag) ----
    @GetMapping("/files/me")
    public Flux<FileEntryDto> listMyFiles(@RequestHeader("X-User-Id") String ownerId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "10") int size,
                                          @RequestParam(required = false) String sort,
                                          @RequestParam(required = false, name = "tag") String tag) {
        Pageable pageable = PageRequest.of(page, size, resolveSort(sort));
        if (tag != null && !tag.isBlank()) {
            return files.findByOwnerIdAndTag(ownerId, tag, pageable).map(FileEntryDto::from);
        }
        return files.findByOwnerId(ownerId, pageable).map(FileEntryDto::from);
    }

    // ---- List public files (paged/sorted/filtered by tag) ----
    @GetMapping("/files/public")
    public Flux<FileEntryDto> listPublic(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "10") int size,
                                         @RequestParam(required = false) String sort,
                                         @RequestParam(required = false, name = "tag") String tag) {
        Pageable pageable = PageRequest.of(page, size, resolveSort(sort));
        if (tag != null && !tag.isBlank()) {
            return files.findPublicByTag(tag, pageable).map(FileEntryDto::from);
        }
        return files.findPublic(pageable).map(FileEntryDto::from);
    }

    private Sort resolveSort(String sortParam) {
        if (sortParam == null || sortParam.isBlank()) {
            return Sort.by(Sort.Order.desc("createdAt")); // was uploadedAt
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String token : sortParam.split(",")) {
            String t = token.trim();
            boolean desc = t.startsWith("-");
            String field = desc ? t.substring(1) : (t.startsWith("+") ? t.substring(1) : t);
            String mapped = switch (field) {
                case "filename", "name" -> "filenameLc";
                case "uploadDate", "uploadedAt", "createdAt" -> "createdAt"; // normalize here
                case "updatedAt" -> "updatedAt";
                case "contentType" -> "contentType";
                case "size", "fileSize" -> "size";
                default -> "createdAt";
            };
            orders.add(desc ? Sort.Order.desc(mapped) : Sort.Order.asc(mapped));
        }
        return Sort.by(orders);
    }


    // ---- Download by unguessable token ----
    @GetMapping("/download/{token}")
    public Mono<Void> download(@PathVariable String token, ServerHttpResponse response) {
        return links.findByToken(token)
                .switchIfEmpty(Mono.error(new NotFoundException("File not found")))
                .flatMap(link -> files.findById(link.getFileId()))
                .switchIfEmpty(Mono.error(new NotFoundException("File not found")))
                .flatMap(fe -> storage.open(fe.getGridFsId())
                        .flatMap(res -> {
                            MediaType ct = Optional.ofNullable(fe.getContentType())
                                    .map(s -> {
                                        try { return MediaType.parseMediaType(s); } catch (Exception ignore) { return MediaType.APPLICATION_OCTET_STREAM; }
                                    })
                                    .orElse(MediaType.APPLICATION_OCTET_STREAM);
                            response.getHeaders().setContentType(ct);
                            response.getHeaders().set(HttpHeaders.CONTENT_LENGTH, String.valueOf(fe.getSize()));
                            ContentDisposition cd = ContentDisposition.attachment()
                                    .filename(fe.getFilename(), java.nio.charset.StandardCharsets.UTF_8)
                                    .build();
                            response.getHeaders().setContentDisposition(cd);

                            return response.writeWith(res.getDownloadStream());
                        }));
    }

    // ---- Delete ----
    @DeleteMapping("/files/{id}")
    public Mono<Void> delete(@RequestHeader("X-User-Id") String ownerId, @PathVariable String id) {
        return deleteService.delete(ownerId, id);
    }
}

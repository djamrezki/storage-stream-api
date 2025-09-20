package ae.teletronics.storage.adapters.web;

import ae.teletronics.storage.adapters.web.dto.*;
import ae.teletronics.storage.application.DeleteFileService;
import ae.teletronics.storage.application.ListFilesService;
import ae.teletronics.storage.application.RenameFileService;
import ae.teletronics.storage.application.UploadFileService;
import ae.teletronics.storage.application.dto.UploadFileCommand;
import ae.teletronics.storage.application.dto.UploadFileResult;
import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.StreamSource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping
public class FilesController {

    private final UploadFileService uploadSvc;
    private final RenameFileService renameSvc;
    private final ListFilesService listSvc;
    private final DeleteFileService deleteSvc;
    private final DownloadLinkBuilder linkBuilder;

    public FilesController(UploadFileService uploadSvc,
                           RenameFileService renameSvc,
                           ListFilesService listSvc,
                           DeleteFileService deleteSvc,
                           DownloadLinkBuilder linkBuilder) {
        this.uploadSvc = uploadSvc;
        this.renameSvc = renameSvc;
        this.listSvc = listSvc;
        this.deleteSvc = deleteSvc;
        this.linkBuilder = linkBuilder;
    }

    @PostMapping(path = "/files", consumes = {"multipart/form-data"})
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse upload(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("filename") String filename,
            @RequestParam(value = "visibility", required = false, defaultValue = "PRIVATE") String visibilityStr,
            @RequestParam(value = "tag", required = false) List<String> tags
    ) throws IOException {

        Visibility visibility = Visibility.valueOf(visibilityStr.toUpperCase(Locale.ROOT));
        String contentTypeHeader = file.getContentType();

        StreamSource source = file::getInputStream; // re-openable per call

        UploadFileResult result = uploadSvc.upload(new UploadFileCommand(
                userId,
                filename,
                visibility,
                tags,
                contentTypeHeader,
                source
        ));

        String download = linkBuilder.build(result.downloadToken());
        return new UploadResponse(result.fileId(), download);
    }

    @PatchMapping("/files/{id}/rename")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rename(@RequestHeader("X-User-Id") String userId,
                       @PathVariable("id") String fileId,
                       @RequestBody RenameRequest body) {
        if (body == null || !StringUtils.hasText(body.filename())) {
            throw new IllegalArgumentException("New filename must be provided");
        }
        renameSvc.rename(userId, fileId, body.filename());
    }

    @DeleteMapping("/files/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader("X-User-Id") String userId,
                       @PathVariable("id") String fileId) {
        deleteSvc.delete(userId, fileId);
    }

    @GetMapping("/files/public")
    public PageResponse<FileEntryDto> listPublic(@RequestParam(value = "tag", required = false) String tag,
                                                 @RequestParam(value = "page", defaultValue = "0") int page,
                                                 @RequestParam(value = "size", defaultValue = "20") int size,
                                                 @RequestParam(value = "sortBy", required = false) String sortBy,
                                                 @RequestParam(value = "asc", defaultValue = "false") boolean asc) {
        Page<FileEntry> p = listSvc.listPublic(tag, page, size, sortBy, asc);
        return new PageResponse<>(p.getContent().stream().map(FileEntryDto::from).toList(),
                p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    @GetMapping("/files/me")
    public PageResponse<FileEntryDto> listMine(@RequestHeader("X-User-Id") String userId,
                                               @RequestParam(value = "tag", required = false) String tag,
                                               @RequestParam(value = "page", defaultValue = "0") int page,
                                               @RequestParam(value = "size", defaultValue = "20") int size,
                                               @RequestParam(value = "sortBy", required = false) String sortBy,
                                               @RequestParam(value = "asc", defaultValue = "false") boolean asc) {
        Page<FileEntry> p = listSvc.listMine(userId, tag, page, size, sortBy, asc);
        return new PageResponse<>(p.getContent().stream().map(FileEntryDto::from).toList(),
                p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    @GetMapping("/files/all")
    public PageResponse<FileEntryDto> listAll(@RequestHeader("X-User-Id") String userId,
                                              @RequestParam(value = "tag", required = false) String tag,
                                              @RequestParam(value = "page", defaultValue = "0") int page,
                                              @RequestParam(value = "size", defaultValue = "20") int size,
                                              @RequestParam(value = "sortBy", required = false) String sortBy,
                                              @RequestParam(value = "asc", defaultValue = "false") boolean asc) {
        Page<FileEntry> p = listSvc.listAll(userId, tag, page, size, sortBy, asc);
        return new PageResponse<>(p.getContent().stream().map(FileEntryDto::from).toList(),
                p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

}

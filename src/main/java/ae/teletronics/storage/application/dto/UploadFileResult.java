package ae.teletronics.storage.application.dto;

public record UploadFileResult(
        String fileId,
        String downloadToken
) {}

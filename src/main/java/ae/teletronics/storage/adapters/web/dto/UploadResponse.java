package ae.teletronics.storage.adapters.web.dto;

public record UploadResponse(
        String id,
        String download // relative link, e.g. "/download/{token}"
) {}

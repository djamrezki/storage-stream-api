package ae.teletronics.storage.adapters.web.dto;

public record ErrorResponse(
        String error,
        String message
) {}

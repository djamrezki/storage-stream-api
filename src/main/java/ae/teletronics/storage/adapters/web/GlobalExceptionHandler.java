package ae.teletronics.storage.adapters.web;

import ae.teletronics.storage.adapters.web.dto.ErrorResponse;
import ae.teletronics.storage.application.exceptions.*;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import org.springframework.web.bind.support.WebExchangeBindException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // ---- 409: Duplicates / Conflicts ----
    @ExceptionHandler(DuplicateFileException.class)
    public ResponseEntity<ErrorResponse> handleDup(DuplicateFileException ex) {
        String kind = (ex.getKind() != null) ? ex.getKind().name() : "UNKNOWN";
        String code = "DUPLICATE_" + kind;           // e.g. DUPLICATE_CONTENT / DUPLICATE_FILENAME / DUPLICATE_UNKNOWN
        String msg  = (ex.getMessage() != null) ? ex.getMessage() : "Duplicate file";
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(code, msg));
    }


    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("STALE_UPDATE", ex.getMessage()));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimistic(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("STALE_UPDATE", "File was modified concurrently, please retry"));
    }

    // ---- 403 / 404 ----
    @ExceptionHandler(ForbiddenOperationException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenOperationException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("FORBIDDEN", ex.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", ex.getMessage()));
    }

    // ---- 400: Client input errors (headers/body/validation) ----
    @ExceptionHandler({
            IllegalArgumentException.class,
            MultipartException.class,
            ServerWebInputException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "Bad request";
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("BAD_REQUEST", msg));
    }

    // Bean validation / @Validated binding errors (collect field messages)
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorResponse> handleBind(WebExchangeBindException ex) {
        String details = ex.getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (details.isBlank() && ex.getMessage() != null) details = ex.getMessage();
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_ERROR", details));
    }

    // ---- 413: Request body too large (useful for big uploads) ----
    @ExceptionHandler(DataBufferLimitException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(DataBufferLimitException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("PAYLOAD_TOO_LARGE", "Request body too large"));
    }

    // ---- 415: Unsupported media type ----
    @ExceptionHandler(UnsupportedMediaTypeStatusException.class)
    public ResponseEntity<ErrorResponse> handleUnsupported(UnsupportedMediaTypeStatusException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ErrorResponse("UNSUPPORTED_MEDIA_TYPE", ex.getMessage()));
    }

    // ---- 405: Method not allowed ----
    @ExceptionHandler(MethodNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(MethodNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ErrorResponse("METHOD_NOT_ALLOWED", ex.getMessage()));
    }

    // ---- ResponseStatusException passthrough (keeps the original status) ----
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = (ex.getStatusCode() instanceof HttpStatus hs) ? hs : HttpStatus.valueOf(ex.getStatusCode().value());
        String msg = ex.getReason() != null ? ex.getReason() : "Error";
        return ResponseEntity.status(status).body(new ErrorResponse(status.name(), msg));
    }

    // ---- 500: Catch-all ----
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "Unexpected error";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", msg));
    }
}

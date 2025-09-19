package ae.teletronics.storage.application.exceptions;

/**
 * Thrown when a concurrent modification conflict occurs.
 * Typically mapped to HTTP 409 Conflict.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }

    public ConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}

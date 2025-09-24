package ae.teletronics.storage.application.exceptions;

public class DuplicateFileException extends RuntimeException {
    public enum Kind { FILENAME, CONTENT }
    private final Kind kind;
    public DuplicateFileException(Kind kind, String message) { super(message); this.kind = kind; }
    public DuplicateFileException(String message){super(message);this.kind=null;}
    public Kind getKind() { return kind; }
}

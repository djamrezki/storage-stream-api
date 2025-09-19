package ae.teletronics.storage.ports;

import java.io.IOException;
import java.util.Optional;

/**
 * Determines the media type (e.g., "image/png") from bytes and/or filename hint.
 * Should not assume the stream supports mark/reset; always use the provided StreamSource.
 */
public interface FileTypeDetector {

    /**
     * @param source        re-openable source to inspect
     * @param filenameHint  optional filename to help detection (e.g., extension)
     * @return Optional content type (RFC 2046, e.g., "application/pdf")
     */
    Optional<String> detect(StreamSource source, String filenameHint) throws IOException;
}

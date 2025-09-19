package ae.teletronics.storage.ports;

import java.io.IOException;
import java.io.InputStream;

/**
 * Supplier of (re-openable) InputStreams.
 * Implementations should return a fresh stream each time to support multiple consumers
 * (e.g., virus scan, type detection, and storage write) without buffering huge files in memory.
 */
@FunctionalInterface
public interface StreamSource {
    InputStream openStream() throws IOException;
}

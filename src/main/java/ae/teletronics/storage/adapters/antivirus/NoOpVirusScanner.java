package ae.teletronics.storage.adapters.antivirus;

import ae.teletronics.storage.ports.StreamSource;
import ae.teletronics.storage.ports.VirusScanner;

import java.io.IOException;

/**
 * No-op virus scanner that always returns CLEAN.
 * Provides a seam for future integration (e.g., ClamAV).
 */
public class NoOpVirusScanner implements VirusScanner {

    private static final String ENGINE = "NoOp";

    @Override
    public ScanReport scan(StreamSource source) throws IOException {
        // Intentionally do not read the stream to avoid overhead.
        return ScanReport.clean(ENGINE);
    }
}

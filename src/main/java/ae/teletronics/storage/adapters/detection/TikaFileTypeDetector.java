package ae.teletronics.storage.adapters.detection;

import ae.teletronics.storage.ports.FileTypeDetector;
import ae.teletronics.storage.ports.StreamSource;
import org.apache.tika.Tika;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.io.TemporaryResources;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Apache Tika-based file type detector.
 *
 * Maven:
 * <dependency>
 *   <groupId>org.apache.tika</groupId>
 *   <artifactId>tika-core</artifactId>
 *   <version>2.9.2</version>
 * </dependency>
 */
public class TikaFileTypeDetector implements FileTypeDetector {

    private final DefaultDetector detector;

    public TikaFileTypeDetector() {
        this.detector = new DefaultDetector(TikaConfig.getDefaultConfig().getMimeRepository());
    }

    @Override
    public Optional<String> detect(StreamSource source, String filenameHint) throws IOException {
        Metadata md = new Metadata();
        if (filenameHint != null && !filenameHint.isBlank()) {
            md.set(TikaCoreProperties.RESOURCE_NAME_KEY, filenameHint);
        }

        try (InputStream in = source.openStream()) {
            var mediaType = detector.detect(in, md);
            if (mediaType != null) {
                String asString = mediaType.toString();
                // Tika sometimes returns "application/octet-stream" for unknowns
                if (!"application/octet-stream".equals(asString)) {
                    return Optional.of(asString);
                }
            }
        } catch (Exception e) {
            // fall through to empty
        }
        return Optional.empty();
    }
}

package ae.teletronics.storage.adapters;

import ae.teletronics.storage.adapters.antivirus.NoOpVirusScanner;
import ae.teletronics.storage.adapters.detection.TikaFileTypeDetector;
import ae.teletronics.storage.adapters.storage.LocalFsStorageAdapter;
import ae.teletronics.storage.adapters.time.SystemClockProvider;
import ae.teletronics.storage.ports.ClockProvider;
import ae.teletronics.storage.ports.FileTypeDetector;
import ae.teletronics.storage.ports.StoragePort;
import ae.teletronics.storage.ports.VirusScanner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
@Profile("!test")
public class AdaptersConfig {

    @Bean
    @ConditionalOnMissingBean(StoragePort.class)
    public StoragePort storagePort(
            @Value("${storage.base-path:/data/storage}") String basePath,
            @Value("${storage.fsync-on-write:false}") boolean fsyncOnWrite
    ) throws IOException {
        Path root = Paths.get(basePath).toAbsolutePath().normalize();
        Files.createDirectories(root);
        return new LocalFsStorageAdapter(root, fsyncOnWrite);
    }

    @Bean
    @ConditionalOnMissingBean(FileTypeDetector.class)
    public FileTypeDetector fileTypeDetector() {
        return new TikaFileTypeDetector();
    }

    @Bean
    @ConditionalOnMissingBean(VirusScanner.class)
    public VirusScanner virusScanner() {
        return new NoOpVirusScanner();
    }

    @Bean
    @ConditionalOnMissingBean(ClockProvider.class)
    public ClockProvider clockProvider() {
        return new SystemClockProvider();
    }
}

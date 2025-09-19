package ae.teletronics.storage.adapters.storage;

import ae.teletronics.storage.ports.StoragePort;
import ae.teletronics.storage.ports.StreamSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Objects;
import java.util.UUID;

public class LocalFsStorageAdapter implements StoragePort {

    private final Path rootDir;
    private final boolean fsyncOnWrite; // <— new toggle

    public LocalFsStorageAdapter(Path rootDir, boolean fsyncOnWrite) {
        this.rootDir = Objects.requireNonNull(rootDir, "rootDir");
        this.fsyncOnWrite = fsyncOnWrite;
    }

    @Override
    public StorageSaveResult save(StreamSource source, String suggestedKey) throws IOException {
        Objects.requireNonNull(source, "source");

        final String key = (suggestedKey != null && !suggestedKey.isBlank())
                ? sanitizeKey(suggestedKey)
                : randomKey();

        Path target = rootDir.resolve(key).normalize();
        if (!target.startsWith(rootDir)) {
            throw new IOException("Refusing to escape root directory: " + key);
        }

        Files.createDirectories(target.getParent());

        long total = 0L;
        try (InputStream in = source.openStream();
             OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
                total += r;
            }
        }

        if (fsyncOnWrite) {
            try (FileChannel ch = FileChannel.open(target, StandardOpenOption.WRITE)) {
                ch.force(true); // flush content + metadata
            } catch (Throwable ignored) {
                // best-effort
            }
        }

        return new StorageSaveResult(key, total);
    }

    @Override
    public StoredObject load(String storageKey) throws IOException {
        Path p = resolve(storageKey);
        if (!Files.exists(p)) throw new NoSuchFileException(storageKey);
        long size = Files.size(p);
        StreamSource source = () -> Files.newInputStream(p, StandardOpenOption.READ);
        return new StoredObject(storageKey, size, source);
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Path p = resolve(storageKey);
        try {
            Files.deleteIfExists(p);
            Path parent = p.getParent();
            for (int i = 0; i < 2 && parent != null && !parent.equals(rootDir); i++) {
                try {
                    Files.delete(parent);
                    parent = parent.getParent();
                } catch (DirectoryNotEmptyException ex) {
                    break;
                }
            }
        } catch (SecurityException se) {
            throw new IOException("Failed to delete: " + storageKey, se);
        }
    }

    /* helpers */

    private Path resolve(String key) throws IOException {
        String safe = sanitizeKey(key);
        Path p = rootDir.resolve(safe).normalize();
        if (!p.startsWith(rootDir)) {
            throw new IOException("Refusing to escape root directory: " + key);
        }
        return p;
    }

    private static String sanitizeKey(String input) {
        String trimmed = input.trim().replace("\\", "/");
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        return trimmed.replace("..", "");
    }

    private static String randomKey() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String s1 = uuid.substring(0, 2);
        String s2 = uuid.substring(2, 4);
        return s1 + "/" + s2 + "/" + uuid;
    }
}

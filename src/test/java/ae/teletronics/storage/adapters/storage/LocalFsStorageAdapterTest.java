package ae.teletronics.storage.adapters.storage;

import ae.teletronics.storage.ports.StoragePort;
import ae.teletronics.storage.ports.StreamSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

class LocalFsStorageAdapterTest {

    @TempDir
    Path tempRoot;

    private StoragePort newAdapter(boolean fsyncOnWrite) {
        return new LocalFsStorageAdapter(tempRoot, fsyncOnWrite);
    }

    private static StreamSource fromBytes(byte[] data) {
        return () -> new ByteArrayInputStream(data);
    }

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        return data;
    }

    @Test
    void save_then_load_roundtrip_ok() throws Exception {
        StoragePort storage = newAdapter(false);
        byte[] data = randomBytes(16 * 1024);
        String key = "user1/dir/nested/file.bin";

        StoragePort.StorageSaveResult res = storage.save(fromBytes(data), key);

        assertThat(res.storageKey()).isEqualTo(key);
        assertThat(res.size()).isEqualTo(data.length);

        Path onDisk = tempRoot.resolve(key);
        assertThat(Files.exists(onDisk)).isTrue();
        assertThat(Files.size(onDisk)).isEqualTo(data.length);

        StoragePort.StoredObject obj = storage.load(key);
        assertThat(obj.storageKey()).isEqualTo(key);
        assertThat(obj.size()).isEqualTo(data.length);

        try (InputStream in = obj.source().openStream()) {
            byte[] read = in.readAllBytes();
            assertThat(read).containsExactly(data);
        }
    }

    @Test
    void save_creates_parent_directories() throws Exception {
        StoragePort storage = newAdapter(false);
        String key = "nested/a/b/c/file.txt";
        byte[] data = "hello".getBytes();

        storage.save(fromBytes(data), key);

        Path file = tempRoot.resolve(key);
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.isDirectory(file.getParent())).isTrue();
    }

    @Test
    void save_withFsyncOnWrite_bestEffort() throws Exception {
        // We can’t observe fsync directly; we just ensure save succeeds with the flag on.
        StoragePort storage = newAdapter(true);
        String key = "sync/file.txt";
        byte[] data = "fsync pls".getBytes();

        storage.save(fromBytes(data), key);

        Path file = tempRoot.resolve(key);
        assertThat(Files.readString(file)).isEqualTo("fsync pls");
    }

    @Test
    void delete_removes_file_and_is_idempotent() throws Exception {
        StoragePort storage = newAdapter(false);
        String key = "todelete/file.txt";
        Path file = tempRoot.resolve(key);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "bye");

        assertThat(Files.exists(file)).isTrue();

        storage.delete(key);
        assertThat(Files.exists(file)).isFalse();

        // second call should not throw
        assertThatCode(() -> storage.delete(key)).doesNotThrowAnyException();
    }

    @Test
    void load_nonExistingKey_throws_NoSuchFile() {
        StoragePort storage = newAdapter(false);
        assertThatThrownBy(() -> storage.load("no/such/file.bin"))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void save_rejects_escaping_root() {
        StoragePort storage = newAdapter(false);
        // Attempt to escape via .. or leading slashes should be sanitized/blocked.
        assertThatThrownBy(() -> storage.save(fromBytes("x".getBytes()), "../../etc/passwd"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> storage.save(fromBytes("x".getBytes()), "/../../etc/passwd"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void save_generates_random_key_when_suggested_missing() throws Exception {
        StoragePort storage = newAdapter(false);
        byte[] data = "auto-key".getBytes();

        StoragePort.StorageSaveResult res = storage.save(fromBytes(data), null);

        // randomKey() format: "xx/yy/<uuidNoDashes>"
        assertThat(res.storageKey()).matches("^[a-f0-9]{2}/[a-f0-9]{2}/[a-f0-9]{32}$");
        Path file = tempRoot.resolve(res.storageKey());
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.size(file)).isEqualTo(data.length);
    }
}

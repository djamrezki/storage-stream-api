package ae.teletronics.storage.application;


import ae.teletronics.storage.application.exceptions.NotFoundException;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.DownloadLinkQueryPort;
import ae.teletronics.storage.ports.FileEntryQueryPort;
import ae.teletronics.storage.ports.ReactiveStoragePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.publisher.PublisherProbe;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeleteFileServiceUnitTest {

    @Mock FileEntryQueryPort files;          // << was FileEntryRepository
    @Mock DownloadLinkQueryPort links;
    @Mock ReactiveStoragePort storage;

    @InjectMocks DeleteFileServiceReactive service;

    private FileEntry file(String id, String owner, String storageKey, String gridFsId) {
        var f = new FileEntry();
        f.setId(id);
        f.setOwnerId(owner);
        f.setStorageKey(storageKey); // if you still keep it
        f.setGridFsId(gridFsId);     // used by service.delete(...)
        return f;
    }

    @Test
    void delete_owner_ok_removesStorage_thenDb_and_links() {
        var owner = "u1";
        var fe = file("F1", owner, "bucket/key1", "gfs-1");

        when(files.findById("F1")).thenReturn(Mono.just(fe));
        when(links.deleteAllByFileId("F1")).thenReturn(Mono.empty());
        when(storage.delete("gfs-1")).thenReturn(Mono.empty());
        when(files.deleteById("F1")).thenReturn(Mono.empty());

        // subscribe to trigger the pipeline
        service.delete(owner, "F1").block();

        InOrder inOrder = Mockito.inOrder(storage, files, links);
        // your service deletes links first, then blob, then metadata
        inOrder.verify(links).deleteAllByFileId("F1");
        inOrder.verify(storage).delete("gfs-1");
        inOrder.verify(files).deleteById("F1");
        verifyNoMoreInteractions(storage, files, links);
    }

    @Test
    void delete_nonOwner_returnsNotFound_noSideEffects() {
        var fe = file("F1", "owner", "k", "gfs-1");
        when(files.findById("F1")).thenReturn(Mono.just(fe));

        assertThatThrownBy(() -> service.delete("intruder", "F1").block())
                .isInstanceOf(NotFoundException.class); // service intentionally hides existence

        verifyNoInteractions(storage, links);
        verify(files, never()).deleteById(anyString());
    }

    @Test
    void delete_notFound_throws404_noSideEffects() {
        when(files.findById("NF")).thenReturn(Mono.empty());

        assertThatThrownBy(() -> service.delete("u", "NF").block())
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(storage, links);
        verify(files, never()).deleteById(anyString());
    }

    @Test
    void delete_storageFailure_bubblesUp_and_keepsDb() {
        var fe = file("F1", "u1", "k1", "gfs-1");
        when(files.findById("F1")).thenReturn(Mono.just(fe));
        when(links.deleteAllByFileId("F1")).thenReturn(Mono.empty());

        // Simulate failure
        when(storage.delete("gfs-1"))
                .thenReturn(Mono.error(new RuntimeException("S3 down")));

        // Probe for deleteById
        PublisherProbe<Void> deleteProbe = PublisherProbe.empty();
        when(files.deleteById("F1")).thenReturn(deleteProbe.mono());

        assertThatThrownBy(() -> service.delete("u1", "F1").block())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("S3 down");

        // Check that deleteById was never subscribed
        deleteProbe.assertWasNotSubscribed();

        // storage.delete WAS subscribed
        verify(storage).delete("gfs-1");
    }

    @Test
    void delete_idempotency_example_alreadyDeleted_returnsNotFound() {
        when(files.findById("gone")).thenReturn(Mono.empty());

        assertThatThrownBy(() -> service.delete("u1", "gone").block())
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(storage, links);
    }
}


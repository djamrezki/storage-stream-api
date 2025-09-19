package ae.teletronics.storage.application;


import ae.teletronics.storage.adapters.persistence.repo.DownloadLinkRepository;
import ae.teletronics.storage.adapters.persistence.repo.FileEntryRepository;
import ae.teletronics.storage.application.exceptions.ForbiddenOperationException;
import ae.teletronics.storage.application.exceptions.NotFoundException;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.FileTypeDetector;
import ae.teletronics.storage.ports.StoragePort;
import ae.teletronics.storage.ports.VirusScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeleteFileServiceUnitTest {

    @Mock FileEntryRepository files;
    @Mock DownloadLinkRepository links;
    @Mock StoragePort storage;
    @Mock FileTypeDetector typeDetector;     // not used here, but service likely needs it in ctor
    @Mock VirusScanner virusScanner;         // same

    @InjectMocks DeleteFileService service;

    private FileEntry file(String id, String owner, String storageKey) {
        var f = new FileEntry();
        f.setId(id);
        f.setOwnerId(owner);
        f.setStorageKey(storageKey);
        return f;
    }

    @BeforeEach
    void setUp() {
        // if your UploadFileService has other collaborators in ctor, @InjectMocks will wire mocks
        // nothing else to init
    }

    @Test
    void delete_owner_ok_removesStorage_thenDb_and_links() throws IOException {
        // given
        var owner = "u1";
        var fe = file("F1", owner, "bucket/key1");
        when(files.findById("F1")).thenReturn(Optional.of(fe));

        // when
        service.delete(owner, "F1");

        // then
        // order: delete bytes first, then DB, then links (or vice-versa if you designed it differently)
        InOrder inOrder = Mockito.inOrder(storage, files, links);
        inOrder.verify(storage).delete("bucket/key1");
        inOrder.verify(files).deleteById("F1");
        // adapt to your repo method (by fileId or cascading). If you don’t delete links explicitly, remove this:
        inOrder.verify(links).deleteByFileId("F1");

        verifyNoMoreInteractions(storage, files, links);
    }

    @Test
    void delete_nonOwner_forbidden_noSideEffects() {
        // given
        var fe = file("F1", "owner", "k");
        when(files.findById("F1")).thenReturn(Optional.of(fe));

        // expect
        assertThatThrownBy(() -> service.delete("intruder", "F1"))
                .isInstanceOf(ForbiddenOperationException.class);

        verifyNoInteractions(storage);
        verify(files, never()).deleteById(any());
        verifyNoInteractions(links);
    }

    @Test
    void delete_notFound_throws404_noSideEffects() {
        when(files.findById("NF")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("u", "NF"))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(storage, links);
        verify(files, never()).deleteById(any());
    }

    @Test
    void delete_storageFailure_bubblesUp_and_keepsDb() throws IOException {
        // given
        var fe = file("F1", "u1", "k1");
        when(files.findById("F1")).thenReturn(Optional.of(fe));
        doThrow(new RuntimeException("S3 down")).when(storage).delete("k1");

        // expect
        Throwable t = catchThrowable(() -> service.delete("u1", "F1"));

        assertThat(t)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to delete stored object")
                .hasRootCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("S3 down");

        // then: DB untouched, links untouched
        verify(files, never()).deleteById(any());
        verifyNoInteractions(links);
    }

    @Test
    void delete_idempotency_decision_example_alreadyDeleted_returnsNotFound() {
        // If you chose strict 404 on second call:
        when(files.findById("gone")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("u1", "gone"))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(storage, links);
    }

}

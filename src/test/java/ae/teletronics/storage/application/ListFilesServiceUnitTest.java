package ae.teletronics.storage.application;

import ae.teletronics.storage.adapters.persistence.repo.FileEntryRepository;
import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.domain.model.FileEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ListFilesServiceUnitTest {

    @Test
    void listPublic_returnsPage() {
        FileEntryRepository repo = mock(FileEntryRepository.class);
        ListFilesService svc = new ListFilesService(repo);

        FileEntry a = new FileEntry(); a.setId("A"); a.setFilename("a.txt");
        FileEntry b = new FileEntry(); b.setId("B"); b.setFilename("b.txt");

        Page<FileEntry> page = new PageImpl<>(List.of(a, b), PageRequest.of(0, 2), 2);
        when(repo.findByVisibility(eq(Visibility.PUBLIC), any(Pageable.class))).thenReturn(page);

        Page<FileEntry> res = svc.listPublic(null, 0, 2, "filename", true);

        assertThat(res.getContent()).hasSize(2);
        assertThat(res.getTotalElements()).isEqualTo(2);
        verify(repo).findByVisibility(eq(Visibility.PUBLIC), any(Pageable.class));
    }

    @Test
    void listPublic_withTag_filters() {
        FileEntryRepository repo = mock(FileEntryRepository.class);
        ListFilesService svc = new ListFilesService(repo);

        Page<FileEntry> page = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(repo.findByVisibilityAndTags(eq(Visibility.PUBLIC), eq("docs"), any(Pageable.class))).thenReturn(page);

        Page<FileEntry> res = svc.listPublic("DoCs", 0, 10, "createdAt", false);
        assertThat(res.getTotalElements()).isZero();
        verify(repo).findByVisibilityAndTags(eq(Visibility.PUBLIC), eq("docs"), any(Pageable.class));
    }
}

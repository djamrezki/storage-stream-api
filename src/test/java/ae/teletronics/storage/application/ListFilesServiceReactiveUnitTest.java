package ae.teletronics.storage.application;

import ae.teletronics.storage.application.dto.PagedResult;
import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.FileEntryQueryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ListFilesServiceReactiveUnitTest {

    // --- helpers ---------------------------------------------------------------

    private static FileEntry fe(String id, String name, Visibility vis) {
        FileEntry f = new FileEntry();
        f.setId(id);
        f.setFilename(name);
        f.setFilenameLc(name.toLowerCase(Locale.ROOT));
        f.setVisibility(vis);
        f.setCreatedAt(Instant.now());
        return f;
    }

    // --- tests ----------------------------------------------------------------

    @Test
    void listPublic_returnsPagedResult() {
        FileEntryQueryPort port = mock(FileEntryQueryPort.class);
        ListFilesServiceReactive svc = new ListFilesServiceReactive(port);

        var a = fe("A", "a.txt", Visibility.PUBLIC);
        var b = fe("B", "b.txt", Visibility.PUBLIC);

        when(port.findAllByVisibility(eq(Visibility.PUBLIC), any(Pageable.class), isNull()))
                .thenReturn(Flux.just(a, b));
        when(port.countByVisibility(eq(Visibility.PUBLIC), isNull()))
                .thenReturn(Mono.just(2L));

        PagedResult<FileEntry> pr = svc.listPublic(0, 2, null, "filename,asc").block();
        assertThat(pr).isNotNull();
        assertThat(pr.page()).isEqualTo(0);
        assertThat(pr.size()).isEqualTo(2);

        var items = pr.items().collectList().block();
        var total = pr.total().block();

        assertThat(items).containsExactly(a, b);
        assertThat(total).isEqualTo(2L);

        verify(port).findAllByVisibility(eq(Visibility.PUBLIC), any(Pageable.class), isNull());
        verify(port).countByVisibility(eq(Visibility.PUBLIC), isNull());
    }

    @Test
    void listPublic_withTag_isLowercased_andFiltered() {
        FileEntryQueryPort port = mock(FileEntryQueryPort.class);
        ListFilesServiceReactive svc = new ListFilesServiceReactive(port);

        when(port.findAllByVisibility(eq(Visibility.PUBLIC), any(Pageable.class), eq("docs")))
                .thenReturn(Flux.empty());
        when(port.countByVisibility(eq(Visibility.PUBLIC), eq("docs")))
                .thenReturn(Mono.just(0L));

        PagedResult<FileEntry> pr = svc.listPublic(0, 10, "DoCs", "uploadedAt,desc").block();
        assertThat(pr).isNotNull();

        var items = pr.items().collectList().block();
        var total = pr.total().block();

        assertThat(items).isEmpty();
        assertThat(total).isZero();

        verify(port).findAllByVisibility(eq(Visibility.PUBLIC), any(Pageable.class), eq("docs"));
        verify(port).countByVisibility(eq(Visibility.PUBLIC), eq("docs"));
    }

    @Test
    void listPublic_defaultSort_isUploadedAtDesc() {
        FileEntryQueryPort port = mock(FileEntryQueryPort.class);
        ListFilesServiceReactive svc = new ListFilesServiceReactive(port);

        when(port.findAllByVisibility(eq(Visibility.PUBLIC), any(Pageable.class), isNull()))
                .thenReturn(Flux.empty());
        when(port.countByVisibility(eq(Visibility.PUBLIC), isNull()))
                .thenReturn(Mono.just(0L));

        svc.listPublic(1, 25, null, null).block();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(port).findAllByVisibility(eq(Visibility.PUBLIC), pageableCaptor.capture(), isNull());

        Pageable p = pageableCaptor.getValue();
        assertThat(p.getPageNumber()).isEqualTo(1);
        assertThat(p.getPageSize()).isEqualTo(25);

        Sort sort = p.getSort();
        Sort.Order order = sort.getOrderFor("uploadedAt");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void listPublic_explicitSort_filenameAsc() {
        FileEntryQueryPort port = mock(FileEntryQueryPort.class);
        ListFilesServiceReactive svc = new ListFilesServiceReactive(port);

        when(port.findAllByVisibility(eq(Visibility.PUBLIC), any(Pageable.class), isNull()))
                .thenReturn(Flux.empty());
        when(port.countByVisibility(eq(Visibility.PUBLIC), isNull()))
                .thenReturn(Mono.just(0L));

        svc.listPublic(0, 50, null, "filename,asc").block();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(port).findAllByVisibility(eq(Visibility.PUBLIC), pageableCaptor.capture(), isNull());

        Pageable p = pageableCaptor.getValue();
        Sort.Order order = p.getSort().getOrderFor("filename");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void listMine_returnsPagedResult_andPassesOwnerAndTag() {
        FileEntryQueryPort port = mock(FileEntryQueryPort.class);
        ListFilesServiceReactive svc = new ListFilesServiceReactive(port);

        var u = "owner-1";
        var x = fe("X", "x.pdf", Visibility.PRIVATE);
        var y = fe("Y", "y.pdf", Visibility.PRIVATE);

        when(port.findAllByOwnerId(eq(u), any(Pageable.class), eq("invoices")))
                .thenReturn(Flux.just(x, y));
        when(port.countByOwnerId(eq(u), eq("invoices")))
                .thenReturn(Mono.just(2L));

        PagedResult<FileEntry> pr = svc.listMine(u, 2, 10, " InVoIcEs ", "uploadedAt,asc").block();
        assertThat(pr).isNotNull();
        assertThat(pr.page()).isEqualTo(2);
        assertThat(pr.size()).isEqualTo(10);

        var items = pr.items().collectList().block();
        var total = pr.total().block();

        assertThat(items).containsExactly(x, y);
        assertThat(total).isEqualTo(2L);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(port).findAllByOwnerId(eq(u), pageableCaptor.capture(), eq("invoices"));
        Pageable p = pageableCaptor.getValue();
        Sort.Order order = p.getSort().getOrderFor("uploadedAt");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }
}

package ae.teletronics.storage.adapters.web;

import ae.teletronics.storage.application.DeleteFileServiceReactive;
import ae.teletronics.storage.application.ReactiveUploadService;
import ae.teletronics.storage.domain.Visibility;
import ae.teletronics.storage.domain.model.FileEntry;
import ae.teletronics.storage.ports.DownloadLinkQueryPort;
import ae.teletronics.storage.ports.FileEntryQueryPort;
import ae.teletronics.storage.ports.ReactiveStoragePort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@WebFluxTest(controllers = ReactiveFileController.class)
class ReactiveFileControllerListPublicTest {

    @Autowired WebTestClient client;

    // Controller deps
    @MockBean ReactiveUploadService uploadService;
    @MockBean DeleteFileServiceReactive deleteService;
    @MockBean FileEntryQueryPort files;
    @MockBean DownloadLinkQueryPort links;
    @MockBean ReactiveStoragePort storage;

    @Test
    void listPublic_defaults_ok() {
        FileEntry fe = new FileEntry();
        fe.setId("ID1");
        fe.setFilename("a.txt");
        fe.setFilenameLc("a.txt");
        fe.setVisibility(Visibility.PUBLIC);

        when(files.findPublic(any(Pageable.class))).thenReturn(Flux.just(fe));

        client.get()
                .uri("/files/public") // no params → defaults apply
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                // array, not an object with "content"
                .jsonPath("$[0].id").isEqualTo("ID1")
                .jsonPath("$[0].filename").isEqualTo("a.txt");

        ArgumentCaptor<Pageable> pg = ArgumentCaptor.forClass(Pageable.class);
        verify(files).findPublic(pg.capture());
        // verify defaults: page=0, size=10, sort by uploadedAt desc
        assertThat(pg.getValue().getPageNumber()).isEqualTo(0);
        assertThat(pg.getValue().getPageSize()).isEqualTo(10);
        assertThat(pg.getValue().getSort().toString().toLowerCase())
                .contains("uploadedat: desc");
    }

    @Test
    void listPublic_withTag_ok() {
        when(files.findPublicByTag(eq("demo"), any(Pageable.class)))
                .thenReturn(Flux.empty());

        client.get()
                .uri("/files/public?tag=demo&page=1&size=5&sort=-name")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("[]");

        verify(files).findPublicByTag(eq("demo"), any(Pageable.class));
    }
}

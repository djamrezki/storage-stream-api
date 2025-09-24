package ae.teletronics.storage.adapters.web;

import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.when;

@WebFluxTest(controllers = DownloadController.class)
@MockitoSettings(strictness = Strictness.LENIENT)

class DownloadControllerTest {

    @Autowired
    WebTestClient client;

    @MockBean
    ae.teletronics.storage.application.DownloadServiceReactive downloads;

    @Test
    void download_streams_ok() {
        var resp = new ae.teletronics.storage.application.dto.DownloadResult(
                "a.txt", "text/plain", Flux.just(
                org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance
                        .wrap("hi".getBytes())
        )
        );
        when(downloads.byToken("tok")).thenReturn(Mono.just(resp));

        client.get().uri("/files/download/tok").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Content-Type", "text/plain")
                .expectHeader().valueMatches("Content-Disposition", "attachment;.*filename=\"a.txt\"")
                .expectBody(byte[].class).isEqualTo("hi".getBytes());
    }
}

package ae.teletronics.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiAvailabilityTest {
    @LocalServerPort int port;

    @Test void openapiServed() {
        var client = RestClient.create();
        var body = client.get()
                .uri("http://localhost:" + port + "/openapi.yml")
                .retrieve().body(String.class);
        assertThat(body).contains("openapi: 3.0.3");
    }
}

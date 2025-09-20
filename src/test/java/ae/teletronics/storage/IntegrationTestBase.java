package ae.teletronics.storage;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    // No @Container / @Testcontainers here. We start it ourselves.
    static final MongoDBContainer mongo =
            new MongoDBContainer(DockerImageName.parse("mongo:7"));

    static {
        mongo.start(); // ensure it's running before Spring binds properties
    }

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
        r.add("spring.data.mongodb.auto-index-creation", () -> true);
    }
}

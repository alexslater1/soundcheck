package app.soundcheck.support;

import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests that boot the application against real PostGIS and LocalStack.
 * Containers are static singletons so every test class in the JVM shares them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability // tracing and metrics as in the application: Spring Boot's tests switch them off otherwise
@ActiveProfiles("test")
@Import(TestClockConfig.class)
public abstract class IntegrationTest {

    public static final String POSTGIS_IMAGE = "postgis/postgis:17-3.5";
    public static final String LOCALSTACK_IMAGE = "localstack/localstack:4.4.0";
    public static final String BUCKET = "soundcheck-media-test";
    public static final String QUEUE = "soundcheck-transcode-test";
    public static final String DLQ = "soundcheck-transcode-test-dlq";

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse(POSTGIS_IMAGE).asCompatibleSubstituteFor("postgres"));

    public static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
                    DockerImageName.parse(LOCALSTACK_IMAGE))
            .withEnv("SERVICES", "s3,sqs")
            .withEnv("SQS_ENDPOINT_STRATEGY", "off");

    static {
        POSTGRES.start();
        LOCALSTACK.start();
        LocalStackResources.create(
                LOCALSTACK.getEndpoint(), LOCALSTACK.getRegion(), LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey());
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "soundcheck.storage.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("soundcheck.storage.region", LOCALSTACK::getRegion);
        registry.add("soundcheck.storage.access-key", LOCALSTACK::getAccessKey);
        registry.add("soundcheck.storage.secret-key", LOCALSTACK::getSecretKey);
        registry.add("soundcheck.storage.bucket", () -> BUCKET);
        registry.add("soundcheck.queue.url", () -> LOCALSTACK.getEndpoint() + "/000000000000/" + QUEUE);
        registry.add("soundcheck.queue.dlq-url", () -> LOCALSTACK.getEndpoint() + "/000000000000/" + DLQ);
        registry.add("soundcheck.admin.email", () -> "admin@test.local");
        registry.add("soundcheck.admin.password", () -> "admin-test-passphrase");
        registry.add("management.otlp.tracing.export.enabled", () -> "false");
        registry.add("management.otlp.metrics.export.enabled", () -> "false");
    }
}

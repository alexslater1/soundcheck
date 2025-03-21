package app.soundcheck.ingestion.internal;

import app.soundcheck.ingestion.internal.source.GigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * One-shot commands behind {@code make record-fixtures} and {@code make ingest}: the application starts,
 * runs the command named by {@code soundcheck.ingestion.cli}, and exits with 0 or 1.
 */
@Component
@ConditionalOnProperty(name = "soundcheck.ingestion.cli")
class IngestionCli implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionCli.class);

    private final ApplicationContext context;
    private final IngestionProperties properties;
    private final GigSource source;
    private final FixtureRecorder recorder;
    private final IngestionService service;
    private final IngestionGuard guard;

    IngestionCli(
            ApplicationContext context,
            IngestionProperties properties,
            GigSource source,
            FixtureRecorder recorder,
            IngestionService service,
            IngestionGuard guard) {
        this.context = context;
        this.properties = properties;
        this.source = source;
        this.recorder = recorder;
        this.service = service;
        this.guard = guard;
    }

    @Override
    public void run(ApplicationArguments args) {
        String command = context.getEnvironment().getProperty("soundcheck.ingestion.cli", "");
        int exit = 0;
        try {
            switch (command) {
                case "record-fixtures" -> {
                    if (!properties.live()) {
                        throw new IllegalStateException("record-fixtures needs INGESTION_MODE=live");
                    }
                    recorder.record(source);
                }
                case "ingest" -> {
                    guard.check(source);
                    for (String name : service.sourceNames()) {
                        IngestionService.RunResult result = service.run(name, "CLI");
                        if (result.status().equals("FAILED")) {
                            exit = 1;
                        }
                    }
                }
                default -> throw new IllegalArgumentException("Unknown soundcheck.ingestion.cli command: " + command);
            }
        } catch (RuntimeException e) {
            log.error("Ingestion command '{}' failed: {}", command, e.getMessage());
            exit = 1;
        }
        int code = exit;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}

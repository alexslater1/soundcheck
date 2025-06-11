package app.soundcheck.ingestion.internal;

import app.soundcheck.ingestion.internal.source.GigSource;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourcePage;
import app.soundcheck.ingestion.internal.source.venues.VenueCoverage;
import app.soundcheck.ingestion.internal.source.venues.VenueSiteSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * One-shot commands behind {@code make record-fixtures}, {@code make ingest} and {@code make venue-coverage}:
 * the application starts,
 * runs the command named by {@code soundcheck.ingestion.cli}, and exits with 0 or 1.
 */
@Component
@ConditionalOnProperty(name = "soundcheck.ingestion.cli")
class IngestionCli implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionCli.class);

    private final ApplicationContext context;
    private final IngestionProperties properties;
    private final List<GigSource> sources;
    private final FixtureRecorder recorder;
    private final IngestionService service;
    private final IngestionGuard guard;
    private final VenueCoverage coverage;
    private final ObjectProvider<VenueSiteSource> venues;
    private final Clock clock;

    IngestionCli(
            ApplicationContext context,
            IngestionProperties properties,
            List<GigSource> sources,
            FixtureRecorder recorder,
            IngestionService service,
            IngestionGuard guard,
            VenueCoverage coverage,
            ObjectProvider<VenueSiteSource> venues,
            Clock clock) {
        this.context = context;
        this.properties = properties;
        this.sources = sources;
        this.recorder = recorder;
        this.service = service;
        this.guard = guard;
        this.coverage = coverage;
        this.venues = venues;
        this.clock = clock;
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
                    // Recordings are of the ticketing source only; venue sites are never recorded.
                    recorder.record(sources.stream()
                            .filter(s -> !s.name().equals("VENUE"))
                            .findFirst()
                            .orElseThrow());
                }
                case "ingest" -> {
                    sources.forEach(guard::check);
                    // --soundcheck.ingestion.cli-sources=VENUE runs only those sources (default: all).
                    String only = context.getEnvironment().getProperty("soundcheck.ingestion.cli-sources", "");
                    List<String> wanted = only.isBlank() ? service.sourceNames() : List.of(only.split("\\s*,\\s*"));
                    for (String name : wanted) {
                        IngestionService.RunResult result = service.run(name, "CLI");
                        if (result.status().equals("FAILED")) {
                            exit = 1;
                        }
                    }
                }
                // How many of each venue site's shows Ticketmaster misses (from the catalog, no fetching).
                case "venue-coverage" -> System.out.println(VenueCoverage.report(coverage.measure(), clock.instant()));
                // What a venue's site would give, without writing anything (checking a new entry).
                case "venue-preview" -> System.out.println(preview());
                default -> throw new IllegalArgumentException("Unknown soundcheck.ingestion.cli command: " + command);
            }
        } catch (RuntimeException e) {
            log.error("Ingestion command '{}' failed: {}", command, e.getMessage());
            exit = 1;
        }
        int code = exit;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    private String preview() {
        VenueSiteSource source = venues.getIfAvailable();
        if (source == null) {
            throw new IllegalStateException(
                    "venue-preview needs INGESTION_MODE=live and soundcheck.ingestion.venues.enabled=true");
        }
        Instant now = clock.instant();
        ZoneId uk = ZoneId.of("Europe/London");
        StringBuilder out = new StringBuilder();
        for (SourcePage page :
                source.preview(now, now.plus(Duration.ofDays(90))).toList()) {
            out.append("== ")
                    .append(page.request())
                    .append(" · ")
                    .append(page.body())
                    .append('\n');
            page.issues().forEach(i -> out.append("   issue ")
                    .append(i.kind())
                    .append(": ")
                    .append(i.detail())
                    .append('\n'));
            for (SourceGig gig : page.gigs()) {
                out.append("   ")
                        .append(gig.startsAt().atZone(uk).toLocalDateTime())
                        .append(' ')
                        .append(gig.timePrecision())
                        .append(" | ")
                        .append(gig.title())
                        .append(" | ")
                        .append(gig.artists().stream().map(a -> a.name()).toList())
                        .append('\n');
            }
        }
        return out.toString();
    }
}

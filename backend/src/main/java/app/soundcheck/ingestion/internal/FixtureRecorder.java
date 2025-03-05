package app.soundcheck.ingestion.internal;

import app.soundcheck.ingestion.internal.source.CallBudget;
import app.soundcheck.ingestion.internal.source.GigSource;
import app.soundcheck.ingestion.internal.source.SourcePage;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code make record-fixtures}: fetches one real 7-day window from the live source and writes each raw
 * page to {@code fixtures/recorded/ticketmaster/}. That directory is gitignored: real
 * responses are kept on the developer's machine only; the committed fixtures are synthetic.
 * The API key is never part of a response body, but every page is still checked for it before being
 * written, and the write is refused if it appears.
 */
@Component
class FixtureRecorder {

    static final Duration WINDOW = Duration.ofDays(7);
    /** Real recordings go under {@code fixtures/recorded/}, which is gitignored. */
    static final String RECORDED = "recorded";

    private static final Logger log = LoggerFactory.getLogger(FixtureRecorder.class);

    private final IngestionProperties properties;
    private final JsonMapper json;
    private final Clock clock;

    FixtureRecorder(IngestionProperties properties, JsonMapper json, Clock clock) {
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    /** Returns the number of pages written. Existing {@code page-*.json} recordings are replaced. */
    int record(GigSource source) {
        Path directory = Path.of(properties.fixturesDir(), RECORDED, "ticketmaster");
        Instant from = Instant.now(clock).truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(1));
        CallBudget budget = new CallBudget(properties.callBudget());
        int pages = 0;
        int gigs = 0;
        try (Stream<SourcePage> stream = source.pages(from, from.plus(WINDOW), budget)) {
            Files.createDirectories(directory);
            deleteOldPages(directory);
            for (Iterator<SourcePage> it = stream.iterator(); it.hasNext(); ) {
                SourcePage page = it.next();
                String body = json.writeValueAsString(page.body());
                String key = properties.ticketmaster().apiKey();
                if (!key.isBlank() && body.contains(key)) {
                    throw new IllegalStateException("Refusing to write a fixture that contains the API key");
                }
                Path file = directory.resolve("page-%03d.json".formatted(pages));
                Files.writeString(file, body + "\n", StandardCharsets.UTF_8);
                pages++;
                gigs += page.gigs().size();
                log.info(
                        "Recorded {} ({} gigs) from {}",
                        file.getFileName(),
                        page.gigs().size(),
                        page.request());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        log.info(
                "Recorded {} pages / {} mappable gigs for {} .. {} with {} API calls",
                pages,
                gigs,
                from,
                from.plus(WINDOW),
                budget.used());
        return pages;
    }

    private static void deleteOldPages(Path directory) throws IOException {
        try (Stream<Path> listing = Files.list(directory)) {
            for (Path old : listing.filter(p -> p.getFileName().toString().matches("page-\\d+\\.json"))
                    .toList()) {
                Files.delete(old);
            }
        }
    }
}

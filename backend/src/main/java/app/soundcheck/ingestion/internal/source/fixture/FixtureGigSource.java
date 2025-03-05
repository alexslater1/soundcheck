package app.soundcheck.ingestion.internal.source.fixture;

import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.source.CallBudget;
import app.soundcheck.ingestion.internal.source.GigSource;
import app.soundcheck.ingestion.internal.source.SourcePage;
import app.soundcheck.ingestion.internal.source.ticketmaster.TicketmasterMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Replays recorded Ticketmaster pages from {@code fixtures/ticketmaster/*.json} (written by
 * {@code make record-fixtures}) through the same mapper as the live adapter. Used by tests, CI and
 * offline demos ({@code INGESTION_MODE=fixtures}, the default). Makes no network calls.
 */
@Component
@ConditionalOnProperty(name = "soundcheck.ingestion.mode", havingValue = "fixtures", matchIfMissing = true)
public class FixtureGigSource implements GigSource {

    private final Path directory;
    private final JsonMapper json;

    public FixtureGigSource(IngestionProperties properties, JsonMapper json) {
        this.directory = Path.of(properties.fixturesDir(), "ticketmaster");
        this.json = json;
    }

    @Override
    public String name() {
        return TicketmasterMapper.SOURCE;
    }

    /** Every recorded page, in file-name order. The window and the budget do not apply to a recording. */
    @Override
    public Stream<SourcePage> pages(Instant from, Instant to, CallBudget budget) {
        return files().stream().map(this::read);
    }

    List<Path> files() {
        try (Stream<Path> listing = Files.list(directory)) {
            return listing.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list fixtures in " + directory.toAbsolutePath(), e);
        }
    }

    private SourcePage read(Path file) {
        JsonNode body;
        try {
            body = json.readTree(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read fixture " + file, e);
        }
        TicketmasterMapper.Mapped mapped = TicketmasterMapper.map(body);
        return new SourcePage("fixture:" + file.getFileName(), body, mapped.gigs(), mapped.issues());
    }
}

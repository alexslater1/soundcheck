package app.soundcheck.ingestion.internal.source.fixture;

import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.ingestion.internal.source.CallBudget;
import app.soundcheck.ingestion.internal.source.GigSource;
import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceIssue;
import app.soundcheck.ingestion.internal.source.SourcePage;
import app.soundcheck.ingestion.internal.source.ticketmaster.TicketmasterMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    private final boolean shiftToToday;
    private final Clock clock;

    public FixtureGigSource(IngestionProperties properties, JsonMapper json, Clock clock) {
        this.directory = Path.of(properties.fixturesDir(), "ticketmaster");
        this.json = json;
        this.shiftToToday = properties.fixturesShiftToToday();
        this.clock = clock;
    }

    @Override
    public String name() {
        return TicketmasterMapper.SOURCE;
    }

    /**
     * The committed fixtures are invented and say so with a top-level {@code "synthetic": true};
     * a real recording replayed from {@code fixtures/recorded} has no such marker.
     */
    @Override
    public boolean synthetic() {
        List<Path> files = files();
        if (files.isEmpty()) {
            return false;
        }
        try {
            return json.readTree(Files.readAllBytes(files.getFirst()))
                    .path("synthetic")
                    .asBoolean(false);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read fixture " + files.getFirst(), e);
        }
    }

    /** Every recorded page, in file-name order. The window and the budget do not apply to a recording. */
    @Override
    public Stream<SourcePage> pages(Instant from, Instant to, CallBudget budget) {
        List<SourcePage> pages = files().stream().map(this::read).toList();
        return shiftToToday ? shifted(pages).stream() : pages.stream();
    }

    /**
     * Moves all gigs forward by the same number of days so the earliest recorded day becomes today (UTC).
     * The shift is applied in each venue's timezone, so a 19:30 show stays a 19:30 show across a clock
     * change. The first page carries a {@code FIXTURE_DATES_SHIFTED} issue so the run records that the
     * dates are not the recorded ones.
     */
    private List<SourcePage> shifted(List<SourcePage> pages) {
        Optional<LocalDate> firstDay = pages.stream()
                .flatMap(p -> p.gigs().stream())
                .map(g -> g.startsAt().atZone(zone(g)).toLocalDate())
                .min(LocalDate::compareTo);
        if (firstDay.isEmpty()) {
            return pages;
        }
        long days = ChronoUnit.DAYS.between(firstDay.get(), LocalDate.now(clock));
        if (days == 0) {
            return pages;
        }
        List<SourcePage> result = new ArrayList<>();
        for (SourcePage page : pages) {
            List<SourceIssue> issues = new ArrayList<>(page.issues());
            if (result.isEmpty()) {
                issues.add(new SourceIssue(
                        "FIXTURE_DATES_SHIFTED", null, "recorded dates moved by " + days + " days to start today"));
            }
            List<SourceGig> gigs = page.gigs().stream().map(g -> shift(g, days)).toList();
            result.add(new SourcePage(page.request(), page.body(), gigs, issues));
        }
        return result;
    }

    private static SourceGig shift(SourceGig gig, long days) {
        ZoneId zone = zone(gig);
        return new SourceGig(
                gig.source(),
                gig.externalId(),
                gig.url(),
                gig.title(),
                gig.startsAt().atZone(zone).plusDays(days).toInstant(),
                gig.doorsAt() == null
                        ? null
                        : gig.doorsAt().atZone(zone).plusDays(days).toInstant(),
                gig.status(),
                gig.ticketUrl(),
                gig.priceMin(),
                gig.priceMax(),
                gig.currency(),
                gig.imageUrl(),
                gig.venue(),
                gig.artists());
    }

    private static ZoneId zone(SourceGig gig) {
        return ZoneId.of(gig.venue().timezone());
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

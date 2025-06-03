package app.soundcheck.ingestion.internal.resolve;

import static app.soundcheck.ingestion.internal.resolve.GigMatcher.Verdict.DIFFERENT;
import static app.soundcheck.ingestion.internal.resolve.GigMatcher.Verdict.MAYBE;
import static app.soundcheck.ingestion.internal.resolve.GigMatcher.Verdict.SAME;
import static app.soundcheck.ingestion.internal.source.TimePrecision.DATE_ONLY;
import static app.soundcheck.ingestion.internal.source.TimePrecision.DOORS;
import static app.soundcheck.ingestion.internal.source.TimePrecision.EXACT;
import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.ingestion.internal.resolve.GigMatcher.Side;
import app.soundcheck.ingestion.internal.source.TimePrecision;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Matching rules, one test per rule, on a Friday at The Windmill (times are UK local). */
class GigMatcherTest {

    static final ZoneId UK = ZoneId.of("Europe/London");
    static final UUID PALE_KITES = UUID.randomUUID();
    static final UUID NIGHT_BUS = UUID.randomUUID();
    static final UUID GLASS_HARBOUR = UUID.randomUUID();

    static Side side(String source, String localTime, TimePrecision precision, String title, UUID... acts) {
        return side(source, "2026-10-16", localTime, precision, title, acts);
    }

    static Side side(String source, String day, String localTime, TimePrecision precision, String title, UUID... acts) {
        List<String> names = java.util.Arrays.stream(acts)
                .map(id -> id == PALE_KITES ? "Pale Kites" : id == NIGHT_BUS ? "Night Bus" : "Glass Harbour")
                .toList();
        return new Side(
                source,
                LocalDateTime.parse(day + "T" + localTime).atZone(UK).toInstant(),
                precision,
                title,
                List.of(acts),
                names,
                EntityResolver.isPackage(title));
    }

    static GigMatcher.Verdict verdict(Side incoming, Side existing, String... sources) {
        return GigMatcher.decide(incoming, existing, List.of(sources)).verdict();
    }

    @Test
    void theVenueSitesDoorsTimeMatchesTicketmastersShowTimeOnTheSameNightWithASharedAct() {
        Side tm = side("TICKETMASTER", "20:30", EXACT, "Pale Kites", PALE_KITES);
        Side venue = side("VENUE", "19:00", DOORS, "Pale Kites + Night Bus", PALE_KITES, NIGHT_BUS);
        assertThat(verdict(venue, tm, "TICKETMASTER")).isEqualTo(SAME);
    }

    @Test
    void anySharedActIsEnoughNotOnlyTheHeadliner() {
        // Ticketmaster bills the support act's own show; the venue lists the night under its headliner.
        Side tm = side("TICKETMASTER", "20:00", EXACT, "Night Bus", NIGHT_BUS);
        Side venue = side("VENUE", "19:30", DOORS, "Pale Kites w/ Night Bus", PALE_KITES, NIGHT_BUS);
        assertThat(verdict(venue, tm, "TICKETMASTER")).isEqualTo(SAME);
    }

    @Test
    void halfPastMidnightBelongsToTheNightBefore() {
        Side tm = side("TICKETMASTER", "2026-10-16", "23:00", EXACT, "Pale Kites", PALE_KITES);
        Side venue = side("VENUE", "2026-10-17", "00:30", DOORS, "Pale Kites", PALE_KITES);
        assertThat(GigMatcher.night(venue.startsAt())).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(verdict(venue, tm, "TICKETMASTER")).isEqualTo(SAME);
    }

    @Test
    void anotherNightIsAnotherShow() {
        Side tm = side("TICKETMASTER", "2026-10-16", "20:00", EXACT, "Pale Kites", PALE_KITES);
        Side venue = side("VENUE", "2026-10-17", "20:00", DOORS, "Pale Kites", PALE_KITES);
        assertThat(verdict(venue, tm, "TICKETMASTER")).isEqualTo(DIFFERENT);
    }

    @Test
    void realTimesMoreThanThreeHoursApartAreAnEarlyAndALateShow() {
        Side early = side("TICKETMASTER", "17:00", EXACT, "Pale Kites (Early Show)", PALE_KITES);
        Side late = side("SKIDDLE", "21:00", EXACT, "Pale Kites (Late Show)", PALE_KITES);
        assertThat(verdict(late, early, "TICKETMASTER")).isEqualTo(DIFFERENT);
        // A doors time may be up to four hours before the show.
        Side doors = side("VENUE", "18:00", DOORS, "Pale Kites", PALE_KITES);
        Side show = side("TICKETMASTER", "21:30", EXACT, "Pale Kites", PALE_KITES);
        assertThat(verdict(doors, show, "TICKETMASTER")).isEqualTo(SAME);
    }

    @Test
    void aFanAddedDateOnlyGigFitsAnywhereInTheNight() {
        Side fan = side("USER", "20:00", DATE_ONLY, "Pale Kites", PALE_KITES);
        Side tm = side("TICKETMASTER", "16:00", EXACT, "Pale Kites: Afternoon Session", PALE_KITES);
        assertThat(verdict(tm, fan, "USER")).isEqualTo(SAME);
    }

    @Test
    void withinOneSourceTwoListingsOnOneNightAreTwoShowsUnlessOneIsAPackage() {
        Side early = side("VENUE", "18:00", DOORS, "Pale Kites", PALE_KITES);
        Side late = side("VENUE", "21:00", DOORS, "Pale Kites", PALE_KITES);
        assertThat(verdict(late, early, "VENUE")).isEqualTo(DIFFERENT);
        Side vip = side("VENUE", "18:00", DOORS, "Pale Kites - VIP Package", PALE_KITES);
        assertThat(verdict(vip, late, "VENUE")).isEqualTo(SAME);
    }

    @Test
    void aSimilarCleanedTitleIsTheSameShowEvenWithoutResolvedActs() {
        Side tm = side("TICKETMASTER", "20:00", EXACT, "Pale Kites: The Long Way Home Tour");
        Side venue = side("VENUE", "19:00", DOORS, "SOLD OUT: Club Fandango presents Pale Kites (18+)");
        GigMatcher.Decision decision = GigMatcher.decide(venue, tm, List.of("TICKETMASTER"));
        assertThat(decision.verdict()).isEqualTo(SAME);
        assertThat(decision.titleSimilarity()).isEqualTo(1.0);
    }

    @Test
    void almostMatchingNamesWithNoSharedActAreLoggedNotMerged() {
        UUID paleKite = UUID.randomUUID(); // a separate catalog artist: "Pale Kite"
        Side tm = new Side(
                "TICKETMASTER",
                LocalDateTime.parse("2026-10-16T20:00").atZone(UK).toInstant(),
                EXACT,
                "Pale Kite",
                List.of(paleKite),
                List.of("Pale Kite"),
                false);
        Side venue = side("VENUE", "19:00", DOORS, "Pale Kites", PALE_KITES);
        GigMatcher.Decision decision = GigMatcher.decide(venue, tm, List.of("TICKETMASTER"));
        assertThat(decision.verdict()).isEqualTo(MAYBE);
        assertThat(decision.actSimilarity()).isBetween(0.5, 1.0);
    }

    @Test
    void twoDifferentGigsAtOneVenueOnOneNightStayApart() {
        Side tm = side("TICKETMASTER", "19:00", EXACT, "Glass Harbour", GLASS_HARBOUR);
        Side venue = side("VENUE", "22:00", DOORS, "Pale Kites + Night Bus", PALE_KITES, NIGHT_BUS);
        assertThat(verdict(venue, tm, "TICKETMASTER")).isEqualTo(DIFFERENT);
    }
}

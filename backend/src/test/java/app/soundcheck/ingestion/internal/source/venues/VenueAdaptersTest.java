package app.soundcheck.ingestion.internal.source.venues;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceIssue;
import app.soundcheck.ingestion.internal.source.TimePrecision;
import app.soundcheck.shared.config.JsonMappers;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Venue adapters on synthetic files shaped like each site's real answer (2025-06-05 samples; the acts
 * are invented). What each adapter must get right: the UK wall-clock time, doors versus date-only,
 * acts from fields before the title, prices, cancellations, paging, and skipping what it cannot read.
 */
class VenueAdaptersTest {

    static final JsonMapper JSON = JsonMappers.builder().build();
    static final LocalDate TODAY = LocalDate.of(2026, 10, 2);

    static VenueSite site(String key, String adapter, String venueName) {
        return new VenueSite(
                key,
                adapter,
                "https://" + key + ".example/feed",
                new VenueSite.Place(venueName, "1 Test Street", "London", "N1 1AA", 51.5, -0.1),
                null,
                TimePrecision.DOORS,
                null);
    }

    static String fixture(String name) {
        try (InputStream in = VenueAdaptersTest.class.getResourceAsStream("/venues/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<String> acts(VenueSite site, Listing listing) {
        return Listings.toSourceGig(site, listing).artists().stream()
                .map(a -> a.name())
                .toList();
    }

    @Test
    void hopeAndAnchorUsesItsBandsFieldWhenFilledAndItsTitleOtherwise() {
        VenueSite site = site("hope", "hope-and-anchor-api", "Hope & Anchor");
        HopeAndAnchorAdapter adapter = new HopeAndAnchorAdapter(JSON);
        assertThat(adapter.firstPage(site, TODAY).toString())
                .isEqualTo("https://hope.example/feed?status=all&limit=100&from=2026-10-02");

        VenueAdapter.Page page = adapter.read(site, fixture("hope-and-anchor.json"), TODAY);

        assertThat(page.listings()).hasSize(3);
        assertThat(page.issues()).extracting(SourceIssue::kind).containsExactly("NO_DATE");
        assertThat(page.next()).isEqualTo(URI.create("https://hopeandanchor.example/api/v1/events?offset=100"));

        Listing first = page.listings().get(0);
        assertThat(first.start()).isEqualTo(LocalDateTime.of(2026, 10, 16, 19, 30));
        assertThat(first.precision()).isEqualTo(TimePrecision.DOORS);
        assertThat(first.priceMin()).isEqualByComparingTo("12");
        assertThat(first.priceMax()).isEqualByComparingTo("14.50");
        assertThat(acts(site, first)).containsExactly("Harbour Hounds", "Night Moths", "Glass Lanterns");

        // The bands field wins over the title (strings or objects with a name).
        assertThat(acts(site, page.listings().get(1))).containsExactly("Velvet Static", "Dune Sisters");

        Listing cancelled = page.listings().get(2);
        assertThat(cancelled.cancelled()).isTrue();
        assertThat(cancelled.start()).isEqualTo(LocalDateTime.of(2026, 10, 18, 19, 30)); // no doors time given
        assertThat(cancelled.priceMin()).isEqualByComparingTo(BigDecimal.ZERO);
        SourceGig gig = Listings.toSourceGig(site, cancelled);
        assertThat(gig.status()).isEqualTo("CANCELLED");
        assertThat(gig.externalId()).isEqualTo("hope:903");
        assertThat(gig.source()).isEqualTo("VENUE");
    }

    @Test
    void theEventsCalendarIsReadAsLondonWallClockTimeAndItsSentenceTitlesYieldTheActs() {
        VenueSite site = site("lantern", "tribe-events", "The Lantern Arms");
        TribeEventsAdapter adapter = new TribeEventsAdapter(JSON);
        assertThat(adapter.firstPage(site, TODAY).toString()).endsWith("?per_page=50&start_date=2026-10-02");

        VenueAdapter.Page page = adapter.read(site, fixture("tribe-events.json"), TODAY);

        assertThat(page.listings()).hasSize(3);
        assertThat(page.issues()).extracting(SourceIssue::kind).containsExactly("NO_DATE");
        assertThat(page.next()).hasToString("https://lantern.example/wp-json/tribe/events/v1/events?page=2");

        Listing first = page.listings().get(0);
        assertThat(first.title()).isEqualTo("Saturday 10th October – Kholla Moths take the helm");
        // 18:00 labelled "UTC+0" is 18:00 in London, i.e. 17:00 UTC in October (summer time).
        assertThat(Listings.toSourceGig(site, first).startsAt()).isEqualTo(Instant.parse("2026-10-10T17:00:00Z"));
        assertThat(first.imageUrl()).isEqualTo("https://lantern.example/a.jpg");
        assertThat(acts(site, first)).containsExactly("Kholla Moths take the helm");

        assertThat(acts(site, page.listings().get(1))).containsExactly("The Ondine Owls");
        Listing allDay = page.listings().get(2);
        assertThat(allDay.precision()).isEqualTo(TimePrecision.DATE_ONLY);
        assertThat(acts(site, allDay)).containsExactly("Joe Clarke", "Ric Tarmac", "Father Levity");
    }

    @Test
    void mothsFeedGivesTheDateFromTheTitleTheDoorsAndActsFromTheDescription() {
        VenueSite site = site("moth", "moth-rss", "MOTH Club");
        VenueAdapter.Page page = new MothRssAdapter().read(site, fixture("moth.rss"), TODAY);

        assertThat(page.listings()).hasSize(3);
        assertThat(page.issues()).extracting(SourceIssue::kind).containsExactly("NO_DATE");
        assertThat(page.next()).isNull();

        Listing club = page.listings().get(0);
        assertThat(club.eventId()).isEqualTo("Femmes-Fatales-Fri-27-Nov-2026");
        assertThat(club.title()).isEqualTo("Femmes Fatales");
        assertThat(club.start()).isEqualTo(LocalDateTime.of(2026, 11, 27, 23, 0));
        assertThat(club.precision()).isEqualTo(TimePrecision.DOORS);
        assertThat(acts(site, club)).containsExactly("Femmes Fatales");

        // An event night: its name is not an act; the "w/" guests and the "With" acts are.
        Listing night = page.listings().get(1);
        assertThat(night.start()).isEqualTo(LocalDateTime.of(2026, 10, 19, 19, 0));
        assertThat(acts(site, night)).containsExactly("Glasshouse Spider Mite", "Leather.head", "Rope Burns");

        // No description: date-only, and the title is all there is.
        Listing bare = page.listings().get(2);
        assertThat(bare.precision()).isEqualTo(TimePrecision.DATE_ONLY);
        assertThat(acts(site, bare)).containsExactly("Cory Harrison WANDS");
    }

    @Test
    void jsonLdTakesPerformersOffersAndDoorsAndIgnoresStaleTemplateEvents() {
        VenueSite site = site("social", "json-ld", "The Social");
        VenueAdapter.Page page = new JsonLdAdapter(JSON).read(site, fixture("json-ld.html"), TODAY);

        assertThat(page.issues()).extracting(SourceIssue::kind).containsExactly("VENUE_UNREADABLE");
        assertThat(page.listings()).hasSize(2); // the 2019 festival is gone
        Listing gig = page.listings().get(0);
        assertThat(gig.start()).isEqualTo(LocalDateTime.of(2026, 10, 16, 19, 0)); // the doors time
        assertThat(gig.precision()).isEqualTo(TimePrecision.DOORS);
        assertThat(gig.acts()).containsExactly("Pale Kites", "Night Bus");
        assertThat(gig.priceMin()).isEqualByComparingTo("12.50");
        assertThat(gig.ticketUrl()).isEqualTo("https://tickets.example.com/1");
        assertThat(gig.imageUrl()).isEqualTo("https://social.example/1.jpg");

        Listing cancelled = page.listings().get(1);
        assertThat(cancelled.cancelled()).isTrue();
        assertThat(cancelled.start()).isEqualTo(LocalDateTime.of(2026, 12, 5, 22, 0)); // UTC = GMT in December
    }

    @Test
    void pricesReadEveryShapeVenuesWrite() {
        assertThat(Listings.prices("£8 + fees")).containsExactly(new BigDecimal("8"), new BigDecimal("8"));
        assertThat(Listings.prices("£12 – £12.5")).containsExactly(new BigDecimal("12"), new BigDecimal("12.5"));
        assertThat(Listings.prices("Free entry")).containsExactly(BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(Listings.prices("TBC")).isNull();
        assertThat(Listings.prices(null)).isNull();
    }
}

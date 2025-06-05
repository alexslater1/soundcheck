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

        assertThat(page.listings()).hasSize(4);
        assertThat(page.issues()).extracting(SourceIssue::kind).containsExactly("NO_DATE");
        assertThat(page.next()).isNull();
        // A promoter and "An Evening with" in the description's headline part.
        assertThat(acts(site, page.listings().get(3))).containsExactly("Groom of the Stools", "Freddie Merrion");

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
    void squarespaceEventsTakeTheTwentyFourHourTimeThenTheCalendarLinkAndSkipPastEvents() {
        VenueSite site = site("lantern", "squarespace", "The Lantern Arms");
        VenueAdapter.Page page = new SquarespaceAdapter().read(site, fixture("squarespace-eventlist.html"), TODAY);

        assertThat(page.listings()).hasSize(3); // the past event is not read
        assertThat(page.issues()).extracting(SourceIssue::kind).containsExactly("NO_DATE");
        assertThat(page.next()).isNull();

        Listing first = page.listings().get(0);
        assertThat(first.start()).isEqualTo(LocalDateTime.of(2026, 10, 16, 19, 0));
        assertThat(first.precision()).isEqualTo(TimePrecision.DOORS);
        assertThat(first.url()).isEqualTo("https://lantern.example/events/2026/10/16/pale-kites");
        assertThat(first.eventId()).isEqualTo("/events/2026/10/16/pale-kites");
        assertThat(first.imageUrl()).isEqualTo("https://cdn.example/pk.jpg");
        assertThat(acts(site, first)).containsExactly("PALE KITES", "NIGHT BUS", "GLASS LANTERNS");

        // No 24-hour time: the Google Calendar link's UTC start, which in December is London time.
        Listing calendar = page.listings().get(1);
        assertThat(calendar.start()).isEqualTo(LocalDateTime.of(2026, 12, 4, 19, 30));
        assertThat(calendar.precision()).isEqualTo(TimePrecision.DOORS);
        assertThat(acts(site, calendar)).containsExactly("DUNE SISTERS");

        Listing dateOnly = page.listings().get(2);
        assertThat(dateOnly.start()).isEqualTo(LocalDateTime.of(2026, 11, 1, 19, 30));
        assertThat(dateOnly.precision()).isEqualTo(TimePrecision.DATE_ONLY);
    }

    @Test
    void squarespaceSummaryBlocksAreDateOnlyAndADayAndMonthIsTheNextOneToCome() {
        VenueSite site = site("lantern", "squarespace", "The Lantern Arms");
        VenueAdapter.Page page = new SquarespaceAdapter().read(site, fixture("squarespace-summary.html"), TODAY);

        assertThat(page.listings()).hasSize(2);
        assertThat(page.issues()).extracting(SourceIssue::kind).containsExactly("NO_DATE");

        Listing full = page.listings().get(0);
        assertThat(full.start()).isEqualTo(LocalDateTime.of(2026, 10, 23, 19, 30));
        assertThat(full.precision()).isEqualTo(TimePrecision.DATE_ONLY);
        assertThat(full.imageUrl()).isEqualTo("https://cdn.example/nm.jpg");
        assertThat(acts(site, full)).containsExactly("Night Moths", "Rope Burns");

        // "Jan 9" read on 2 October 2026 is next January.
        Listing nextYear = page.listings().get(1);
        assertThat(nextYear.start()).isEqualTo(LocalDateTime.of(2027, 1, 9, 19, 30));
        assertThat(acts(site, nextYear)).containsExactly("KHOLLA");
    }

    @Test
    void aSquarespaceListReadsTheDateLineAndLeavesOutRegulars() {
        VenueSite site = site("lantern", "squarespace-list", "The Lantern Arms");
        VenueAdapter.Page page = new SquarespaceListAdapter(JSON).read(site, fixture("squarespace-list.html"), TODAY);

        assertThat(page.listings())
                .extracting(Listing::title)
                .containsExactly("New Lands: Ondine Owls + Pale Kites", "The George Moody Trio", "Velvet Static");
        assertThat(page.issues()).isEmpty();

        Listing gig = page.listings().get(0);
        assertThat(gig.start()).isEqualTo(LocalDateTime.of(2026, 10, 16, 19, 30));
        assertThat(gig.precision()).isEqualTo(TimePrecision.DOORS);
        assertThat(gig.priceMin()).isEqualByComparingTo("7");
        assertThat(gig.ticketUrl()).isEqualTo("https://tickets.example/882");
        assertThat(gig.imageUrl()).isEqualTo("https://cdn.example/owls.png");
        assertThat(acts(site, gig)).containsExactly("Ondine Owls", "Pale Kites");

        assertThat(page.listings().get(1).priceMin()).isEqualByComparingTo(BigDecimal.ZERO); // "Free Entry"
        Listing january = page.listings().get(2);
        assertThat(january.start()).isEqualTo(LocalDateTime.of(2027, 1, 7, 20, 0)); // "20.00", next January
        assertThat(january.priceMin()).isNull();
    }

    @Test
    void nightsThatAreNotGigsAreLeftOutAndAVenueCanNameMore() {
        VenueSite plain = site("lantern", "squarespace", "The Lantern Arms");
        assertThat(plain.skips("The Lantern Open Mic Night")).isTrue();
        assertThat(plain.skips("South East London Pub Quiz")).isTrue();
        assertThat(plain.skips("Live Band Karaoke")).isTrue();
        assertThat(plain.skips("Pale Kites + Night Bus")).isFalse();
        assertThat(plain.skips("Piñata")).isFalse();

        VenueSite picky = new VenueSite(
                "lantern",
                "squarespace",
                "https://lantern.example/",
                plain.venue(),
                null,
                TimePrecision.DOORS,
                null,
                "piñata|cyanotype",
                List.of());
        assertThat(picky.skips("Piñata")).isTrue();
        assertThat(picky.skips("Open mic")).isTrue();
        assertThat(picky.skips("Pale Kites")).isFalse();
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

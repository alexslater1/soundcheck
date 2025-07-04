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
        // ... but a venue's poster is never stored (facts only).
        assertThat(Listings.toSourceGig(site, first).imageUrl()).isNull();
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
    void mothsAllEventsPageGivesItsOwnShowPagesWithTheFeedsIdsAndDropsACutOffSentence() {
        VenueSite site = new VenueSite(
                "moth",
                "moth-page",
                "https://moth.example/All-Events",
                new VenueSite.Place("MOTH Club", "1 Test Street", "London", "N1 1AA", 51.5, -0.1),
                null,
                TimePrecision.DOORS,
                null);
        VenueAdapter.Page page = new MothPageAdapter(JSON).read(site, fixture("moth-page.html"), TODAY);

        assertThat(page.listings())
                .extracting(Listing::eventId)
                .containsExactly( // the show page's path: the same ids the RSS gave, so known gigs stay matched
                        "Harbour-Hounds-Fri-16-Oct-2026",
                        "Quiet-Engines-Sat-17-Oct-2026",
                        "Gutter-Saints-Sun-18-Oct-2026");
        assertThat(page.issues()).extracting(SourceIssue::kind).containsExactly("NO_DATE"); // the gift cards page
        assertThat(page.next()).isNull();

        Listing full = page.listings().get(0);
        assertThat(full.url()).isEqualTo("https://moth.example/Harbour-Hounds-Fri-16-Oct-2026");
        assertThat(full.start()).isEqualTo(LocalDateTime.of(2026, 10, 16, 19, 30));
        assertThat(full.precision()).isEqualTo(TimePrecision.DOORS);
        assertThat(acts(site, full)).containsExactly("Harbour Hounds", "Night Moths", "Pale Kites");

        // Cut off mid-list ("With Lantern.head,..."): the doors time stays, the unfinished list goes.
        Listing cut = page.listings().get(1);
        assertThat(cut.start()).isEqualTo(LocalDateTime.of(2026, 10, 17, 19, 0));
        assertThat(acts(site, cut)).containsExactly("Quiet Engines");

        Listing bare = page.listings().get(2);
        assertThat(bare.precision()).isEqualTo(TimePrecision.DATE_ONLY);
        assertThat(acts(site, bare)).containsExactly("Gutter Saints");

        // A page without the embedded show list reads as empty (the source reports VENUE_EMPTY).
        assertThat(new MothPageAdapter(JSON)
                        .read(site, "<html><body>No shows here</body></html>", TODAY)
                        .listings())
                .isEmpty();
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

    static VenueSite selectorSite(VenueSite.Selectors selectors) {
        VenueSite base = site("hall", "html-selectors", "The Lantern Arms");
        return new VenueSite(
                "hall",
                "html-selectors",
                "https://hall.example/whats-on",
                base.venue(),
                null,
                TimePrecision.DOORS,
                null,
                null,
                List.of(),
                selectors);
    }

    @Test
    void selectorsReadAGridWithTheTimeInTheDateAndAYearlessDateAsTheNextOne() {
        VenueSite site = selectorSite(new VenueSite.Selectors(
                ".grid-item",
                ".event-title",
                "a.link-box@href",
                ".event-date",
                List.of("EEE MMMM dd, HH:mm"),
                null,
                null,
                "a.ticket-box@href",
                null,
                false,
                "a.next-page@href"));
        VenueAdapter.Page page = new SelectorAdapter().read(site, fixture("selectors.html"), TODAY);

        assertThat(page.listings())
                .extracting(Listing::title)
                .containsExactly(
                        "Pale Kites, Night Bus, Glass Lanterns", "Dune Sisters"); // the club block is another class
        assertThat(page.issues()).extracting(SourceIssue::kind).containsExactly("NO_DATE");
        Listing first = page.listings().get(0);
        assertThat(first.start()).isEqualTo(LocalDateTime.of(2026, 10, 16, 19, 30));
        assertThat(first.precision()).isEqualTo(TimePrecision.DOORS);
        assertThat(first.url()).isEqualTo("https://hall.example/event.php?id=101");
        assertThat(first.eventId()).isEqualTo("/event.php?id=101");
        assertThat(first.ticketUrl()).isEqualTo("https://tickets.example/pk");
        assertThat(acts(site, first)).containsExactly("Pale Kites", "Night Bus", "Glass Lanterns");
        assertThat(page.listings().get(1).start()).isEqualTo(LocalDateTime.of(2027, 1, 9, 20, 0)); // next January
        assertThat(page.next()).hasToString("https://hall.example/whats-on?page=2");
    }

    @Test
    void selectorsReadOneActPerLineDoorsFromThePriceCellAndKeyBlocksWithASharedLinkByTitleAndDate() {
        VenueSite site = selectorSite(new VenueSite.Selectors(
                ".listing_block1, .listing_block2",
                ".listing_title",
                ".listing_title a@href",
                ".listing_date",
                List.of("EEE, dd MMM yyyy"),
                ".listing_price",
                ".listing_price",
                null,
                null,
                true,
                null));
        VenueAdapter.Page page = new SelectorAdapter().read(site, fixture("selectors.html"), TODAY);

        assertThat(page.listings()).hasSize(2);
        Listing bill = page.listings().get(0);
        assertThat(bill.title()).isEqualTo("Harbour Hounds + Rope Burns + DJs");
        assertThat(acts(site, bill)).containsExactly("Harbour Hounds", "Rope Burns");
        assertThat(bill.start()).isEqualTo(LocalDateTime.of(2026, 10, 17, 19, 15)); // "Doors 7.15"
        assertThat(bill.priceMin()).isEqualByComparingTo("12"); // the £ amount, not the doors time
        assertThat(bill.eventId()).isEqualTo("Harbour Hounds + Rope Burns + DJs@2026-10-17");
        Listing free = page.listings().get(1);
        assertThat(free.precision()).isEqualTo(TimePrecision.DATE_ONLY); // no time in "Free entry"
        assertThat(free.priceMin()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void selectorsReadMicrodataWithAnIsoDateAndTheDoorsOfADoorsToCurfewRange() {
        VenueSite site = selectorSite(new VenueSite.Selectors(
                "li.list--events__item",
                ".list--events__item__title",
                ".list--events__item__image a@href",
                "time[itemprop=startDate]@datetime",
                List.of("iso"),
                "time.time",
                null,
                null,
                "img@src",
                false,
                null));
        VenueAdapter.Page page = new SelectorAdapter().read(site, fixture("selectors.html"), TODAY);

        assertThat(page.listings()).hasSize(1);
        Listing owls = page.listings().get(0);
        assertThat(owls.start()).isEqualTo(LocalDateTime.of(2026, 10, 20, 18, 30));
        assertThat(owls.eventId()).isEqualTo("/events/the-ondine-owls-20th-oct/");
        assertThat(acts(site, owls)).containsExactly("The Ondine Owls");
        assertThat(Listings.toSourceGig(site, owls).imageUrl()).isNull(); // read, never stored
    }

    @Test
    void clockTimesAreReadTheWayVenuesWriteThem() {
        assertThat(SelectorAdapter.time("19:00")).isEqualTo(java.time.LocalTime.of(19, 0));
        assertThat(SelectorAdapter.time("7PM")).isEqualTo(java.time.LocalTime.of(19, 0));
        assertThat(SelectorAdapter.time("Doors 7.15 £12")).isEqualTo(java.time.LocalTime.of(19, 15));
        assertThat(SelectorAdapter.time("18:30 - 22:00")).isEqualTo(java.time.LocalTime.of(18, 30));
        assertThat(SelectorAdapter.time("12pm")).isEqualTo(java.time.LocalTime.of(12, 0));
        assertThat(SelectorAdapter.time("£12 on the door")).isNull();
        // A date pattern that carries the time, with an ordinal in the text.
        assertThat(SelectorAdapter.timeInDate("Saturday 3rd October, 7:00PM", List.of("EEEE d MMMM, h:mma")))
                .isEqualTo(java.time.LocalTime.of(19, 0));
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
        assertThat(Listings.prices("Doors 7.15 £12 on the door"))
                .containsExactly(new BigDecimal("12"), new BigDecimal("12"));
        assertThat(Listings.prices(null)).isNull();
    }
}

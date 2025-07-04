package app.soundcheck.ingestion.internal.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceVenue;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** A gig only a package listing announces keeps its show's own title (real Ticketmaster titles, 2025-07-04). */
class PackageTitleTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            textBlock =
                    """
            Westlife: Venue Premium Tickets                                   | Westlife
            Bob Dylan : Venue Premium Tickets                                 | Bob Dylan
            2026 Mercury Prize - Venue Premium Tickets                        | 2026 Mercury Prize
            The World of Hans Zimmer - Premium Ticket and Hotel Experiences   | The World of Hans Zimmer
            An Evening with Phil Taylor - Live Lounge Premium Package         | An Evening with Phil Taylor
            Bloc Party & Interpol - Venue Premium                             | Bloc Party & Interpol
            Leo Sayer: Can't Stop Loving You - Venue Premium Tickets          | Leo Sayer: Can't Stop Loving You
            Midge Ure 'A Man Of Two Worlds' Tour - Venue Premium Tickets      | Midge Ure 'A Man Of Two Worlds' Tour
            Haifa Wehbe & Ahmed Saad Live - Venue Premium Tickets             | Haifa Wehbe & Ahmed Saad Live
            Venue Premium - Kevin Bridges:  Here If You Need Me               | Kevin Bridges: Here If You Need Me
            Venue Premium - Adrian Mills B2B Cloudy                           | Adrian Mills B2B Cloudy
            Venue Premium Tickets - Waterloo: A Tribute to ABBA               | Waterloo: A Tribute to ABBA
            ABBA Voyage - VIP Premium Package                                 | ABBA Voyage
            Meet & Greet Upgrade: Pale Kites                                  | Pale Kites
            Pale Kites (VIP Package)                                          | Pale Kites
            VIP Package                                                       | VIP Package
            """)
    void packageWordsAreStrippedAndTheShowsTitleKept(String title, String plain) {
        assertThat(EntityResolver.plainTitle(title)).isEqualTo(plain);
    }

    @Test
    void onlyTicketmasterPackageListingsAreRetitled() {
        assertThat(EntityResolver.storedTitle(gig("TICKETMASTER", "Westlife: Venue Premium Tickets")))
                .isEqualTo("Westlife");
        assertThat(EntityResolver.storedTitle(gig("TICKETMASTER", "Westlife"))).isEqualTo("Westlife");
        // On a venue's own site "Hospitality" is as likely to be the band: left alone.
        assertThat(EntityResolver.storedTitle(gig("VENUE", "Hospitality - Live at the Lexington")))
                .isEqualTo("Hospitality - Live at the Lexington");
    }

    private static SourceGig gig(String source, String title) {
        return new SourceGig(
                source,
                "id-1",
                null,
                title,
                Instant.parse("2026-11-01T19:30:00Z"),
                null,
                "SCHEDULED",
                null,
                null,
                null,
                null,
                null,
                new SourceVenue("v-1", "Hall", null, null, null, null, null, "Europe/London"),
                List.of());
    }
}

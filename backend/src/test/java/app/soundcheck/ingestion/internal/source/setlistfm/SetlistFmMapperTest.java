package app.soundcheck.ingestion.internal.source.setlistfm;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.ingestion.internal.source.SourceGig;
import app.soundcheck.ingestion.internal.source.SourceIssue;
import app.soundcheck.ingestion.internal.source.TimePrecision;
import app.soundcheck.shared.config.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A page of setlist.fm's search as date-only gig listings, and what is kept of it. */
class SetlistFmMapperTest {

    static final UUID PALE_KITES = UUID.fromString("5b11f4ce-a62d-471e-81fc-a69a8278c7da");
    static final LocalDate FROM = LocalDate.of(2024, 10, 1);
    static final LocalDate TO = LocalDate.of(2026, 10, 4);

    /** Shaped like setlist.fm's documented response: newest first, 20 a page, 45 in all. */
    static final String PAGE =
            """
            {"type":"setlists","itemsPerPage":20,"page":1,"total":45,"setlist":[
              {"id":"future1","eventDate":"10-10-2026","url":"https://www.setlist.fm/setlist/x/future1.html",
               "artist":{"mbid":"5b11f4ce-a62d-471e-81fc-a69a8278c7da","name":"Pale Kites"},
               "venue":{"id":"v-snug","name":"The Snug","city":{"name":"Manchester","coords":{"lat":53.48,"long":-2.24}}}},
              {"id":"63d6a2b3","eventDate":"17-07-2025","url":"https://www.setlist.fm/setlist/x/63d6a2b3.html",
               "artist":{"mbid":"5b11f4ce-a62d-471e-81fc-a69a8278c7da","name":"Pale Kites"},
               "venue":{"id":"v-snug","name":"The Snug ","city":{"name":"Manchester","coords":{"lat":53.48,"long":-2.24}}},
               "tour":{"name":"Summer"},"info":"a note","sets":{"set":[{"song":[{"name":"Opener"}]}]}},
              {"id":"winter1","eventDate":"20-01-2025","url":"https://www.setlist.fm/setlist/x/winter1.html",
               "artist":{"mbid":"5b11f4ce-a62d-471e-81fc-a69a8278c7da","name":"Pale Kites"},
               "venue":{"id":"v-snug","name":"The Snug","city":{"name":"Manchester","coords":{"lat":53.48,"long":-2.24}}},
               "sets":{"set":[]}},
              {"id":"namesake","eventDate":"01-06-2025",
               "artist":{"mbid":"00000000-0000-0000-0000-000000000001","name":"Pale Kite"},
               "venue":{"id":"v-2","name":"Elsewhere","city":{"name":"Leeds"}}},
              {"id":"various","eventDate":"02-06-2025",
               "artist":{"mbid":"89ad4ac3-39f7-470e-963a-56509c546377","name":"Various Artists"},
               "venue":{"id":"v-3","name":"A Field","city":{"name":"Leeds"}}},
              {"id":"novenue","eventDate":"03-06-2025",
               "artist":{"mbid":"5b11f4ce-a62d-471e-81fc-a69a8278c7da","name":"Pale Kites"},"venue":{}},
              {"id":"old1","eventDate":"14-09-2024",
               "artist":{"mbid":"5b11f4ce-a62d-471e-81fc-a69a8278c7da","name":"Pale Kites"},
               "venue":{"id":"v-snug","name":"The Snug","city":{"name":"Manchester"}}}
            ]}
            """;

    final JsonMapper json = JsonMappers.builder().build();

    @Test
    void aSetlistInTheWindowBecomesADateOnlyGigWithItsActAndTownPlacedVenue() throws Exception {
        SetlistFmMapper.Page page = SetlistFmMapper.map(json.readTree(PAGE), PALE_KITES, "Pale Kites", FROM, TO);

        assertThat(page.gigs()).extracting(SourceGig::externalId).containsExactly("63d6a2b3", "winter1");
        SourceGig summer = page.gigs().getFirst();
        assertThat(summer.source()).isEqualTo("SETLISTFM");
        assertThat(summer.url()).isEqualTo("https://www.setlist.fm/setlist/x/63d6a2b3.html");
        assertThat(summer.title()).isEqualTo("Pale Kites");
        assertThat(summer.timePrecision()).isEqualTo(TimePrecision.DATE_ONLY);
        // 20:00 UK time: BST in July, GMT in January.
        assertThat(summer.startsAt()).isEqualTo(Instant.parse("2025-07-17T19:00:00Z"));
        assertThat(page.gigs().get(1).startsAt()).isEqualTo(Instant.parse("2025-01-20T20:00:00Z"));
        assertThat(summer.venue().externalId()).isEqualTo("v-snug");
        assertThat(summer.venue().name()).isEqualTo("The Snug");
        assertThat(summer.venue().city()).isEqualTo("Manchester");
        assertThat(summer.venue().lat()).isEqualTo(53.48);
        assertThat(summer.venue().lng()).isEqualTo(-2.24);
        assertThat(summer.artists()).singleElement().satisfies(a -> {
            assertThat(a.name()).isEqualTo("Pale Kites");
            assertThat(a.mbid()).isEqualTo(PALE_KITES);
            assertThat(a.externalId()).isEmpty(); // resolved by MBID; setlist.fm has no act ids of its own
        });
        assertThat(summer.ticketUrl()).isNull();
    }

    @Test
    void skipsFutureAndOlderDatesNamesakesVariousArtistsAndSetlistsWithoutAVenue() throws Exception {
        SetlistFmMapper.Page page = SetlistFmMapper.map(json.readTree(PAGE), PALE_KITES, "Pale Kites", FROM, TO);

        assertThat(page.gigs())
                .extracting(SourceGig::externalId)
                .doesNotContain("future1", "namesake", "various", "novenue", "old1");
        assertThat(page.issues()).extracting(SourceIssue::kind).containsExactly("NO_VENUE");
        assertThat(page.oldest()).isEqualTo(LocalDate.of(2024, 9, 14)); // older than FROM: paging can stop
        assertThat(page.lastPage()).isFalse(); // 45 in all, 20 a page
    }

    @Test
    void aSearchByNameKeepsOnlyThatNameOnceNormalised() throws Exception {
        SetlistFmMapper.Page page = SetlistFmMapper.map(json.readTree(PAGE), null, "pale  KITES", FROM, TO);

        assertThat(page.gigs()).extracting(SourceGig::externalId).containsExactly("63d6a2b3", "winter1");
    }

    @Test
    void theLastPageIsTheOneThatReachesTheTotal() throws Exception {
        JsonNode third = json.readTree(PAGE.replace("\"page\":1", "\"page\":3"));
        assertThat(SetlistFmMapper.map(third, PALE_KITES, "Pale Kites", FROM, TO)
                        .lastPage())
                .isTrue();
        JsonNode empty = json.readTree("{\"page\":1,\"itemsPerPage\":20,\"total\":0,\"setlist\":[]}");
        SetlistFmMapper.Page none = SetlistFmMapper.map(empty, PALE_KITES, "Pale Kites", FROM, TO);
        assertThat(none.lastPage()).isTrue();
        assertThat(none.oldest()).isNull();
    }

    @Test
    void theStoredPageHasNoSongs() throws Exception {
        JsonNode kept = SetlistFmMapper.withoutSongs(json.readTree(PAGE));

        assertThat(kept.toString()).doesNotContain("\"sets\"", "Opener", "a note");
        assertThat(kept.path("setlist").get(1).path("id").asText("")).isEqualTo("63d6a2b3");
        assertThat(kept.path("setlist").get(1).path("tour").path("name").asText(""))
                .isEqualTo("Summer");
    }
}

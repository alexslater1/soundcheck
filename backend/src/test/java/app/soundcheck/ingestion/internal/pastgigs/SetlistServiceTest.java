package app.soundcheck.ingestion.internal.pastgigs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.soundcheck.catalog.Catalog;
import app.soundcheck.catalog.GigFacts;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.RateLimiter;
import app.soundcheck.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Which setlist a past gig gets, and the guards around setlist.fm. */
class SetlistServiceTest {

    static final UUID MBID = UUID.fromString("5b11f4ce-a62d-471e-81fc-a69a8278c7da");

    Catalog catalog = mock(Catalog.class);
    SetlistFmClient client = mock(SetlistFmClient.class);
    MutableClock clock = new MutableClock();
    UUID fan = UUID.randomUUID();
    UUID gigId = UUID.randomUUID();
    UUID paleKites = UUID.randomUUID();
    UUID nightBus = UUID.randomUUID();
    SetlistService service;

    @BeforeEach
    void setUp() {
        when(client.configured()).thenReturn(true);
        when(client.setlistsOn(any(), any(), any())).thenReturn(List.of());
        var properties = PastGigServiceTest.properties(100);
        service = new SetlistService(
                catalog, client, new SetlistFmBudget(properties, clock), new RateLimiter(), properties, clock);
    }

    private GigFacts gig(Instant startsAt, String town, String setlistFmId) {
        GigFacts facts = new GigFacts(
                gigId,
                startsAt,
                "The Snug",
                town,
                List.of(new GigFacts.Act(paleKites, "Pale Kites", MBID), new GigFacts.Act(nightBus, "Night Bus", null)),
                setlistFmId);
        when(catalog.gigFacts(gigId)).thenReturn(Optional.of(facts));
        return facts;
    }

    private static Setlist setlist(String id, String city) {
        return setlist(id, city, "Pale Kites");
    }

    private static Setlist setlist(String id, String city, String artist) {
        return new Setlist(
                id,
                "https://www.setlist.fm/setlist/" + id,
                artist,
                "The Snug",
                city,
                LocalDate.of(2025, 10, 17),
                null,
                List.of(new Setlist.SetlistPart(
                        null, List.of(new Setlist.SetlistSong("Harbour Lights", null, null, false)))));
    }

    @Test
    void looksForTheHeadlinerOnTheGigsUkDayAndPrefersTheSetlistFromTheGigsTown() {
        // 20:30 UK time on 17 October 2025 is 19:30 UTC; 00:30 the next day UK time would be the 18th.
        Instant evening = Instant.parse("2025-10-17T19:30:00Z");
        gig(evening, "Manchester", null);
        when(client.setlistsOn(MBID, "Pale Kites", LocalDate.of(2025, 10, 17)))
                .thenReturn(List.of(setlist("elsewhere", "Leeds"), setlist("here", "manchester")));

        assertThat(service.forGig(fan, gigId).id()).isEqualTo("here");
        verify(client, never()).setlistsOn(null, "Night Bus", LocalDate.of(2025, 10, 17)); // found already
    }

    @Test
    void triesTheSupportWhenTheHeadlinerHasNoneAndUsesAKnownSetlistIdFirst() {
        gig(Instant.parse("2025-10-17T19:30:00Z"), "Manchester", "7bd6a2a8");
        when(client.setlist("7bd6a2a8")).thenReturn(Optional.empty());
        when(client.setlistsOn(null, "Night Bus", LocalDate.of(2025, 10, 17)))
                .thenReturn(List.of(setlist("support", "Salford")));

        // Only one setlist that day, in another town's name (Salford is next door): it is taken.
        assertThat(service.forGig(fan, gigId).id()).isEqualTo("support");
        verify(client).setlist("7bd6a2a8");
        verify(client).setlistsOn(MBID, "Pale Kites", LocalDate.of(2025, 10, 17));
    }

    @Test
    void cachesAnswersFoundOrNotForAnHourThenAsksAgain() {
        gig(Instant.parse("2025-10-17T19:30:00Z"), "Manchester", null);

        assertThatThrownBy(() -> service.forGig(fan, gigId))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("SETLIST_NOT_FOUND"));
        assertThatThrownBy(() -> service.forGig(UUID.randomUUID(), gigId)).isInstanceOf(ApiException.class);
        verify(client, times(1)).setlistsOn(MBID, "Pale Kites", LocalDate.of(2025, 10, 17));

        clock.advance(Duration.ofMinutes(61));
        when(client.setlistsOn(MBID, "Pale Kites", LocalDate.of(2025, 10, 17)))
                .thenReturn(List.of(setlist("later", "Manchester")));
        assertThat(service.forGig(fan, gigId).id()).isEqualTo("later");
    }

    @Test
    void aGigStillToComeHasNoSetlistAndCostsNoCall() {
        gig(Instant.now().plus(Duration.ofDays(1)), "Manchester", null);

        assertThatThrownBy(() -> service.forGig(fan, gigId))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("SETLIST_NOT_FOUND"));
        verify(client, never()).setlistsOn(any(), any(), any());
    }

    @Test
    void aSupportActsOwnSetlistIsLookedUpForThatActAloneAndCachedApartFromTheGigs() {
        gig(Instant.parse("2025-10-17T19:30:00Z"), "Manchester", null);
        LocalDate day = LocalDate.of(2025, 10, 17);
        when(client.setlistsOn(MBID, "Pale Kites", day)).thenReturn(List.of(setlist("headline", "Manchester")));
        when(client.setlistsOn(null, "Night Bus", day))
                .thenReturn(List.of(setlist("support", "Manchester", "Night Bus")));

        assertThat(service.forGig(fan, gigId).id()).isEqualTo("headline");
        assertThat(service.forGig(fan, gigId, nightBus).id()).isEqualTo("support");
        assertThat(service.forGig(fan, gigId, nightBus).id()).isEqualTo("support");
        assertThat(service.forGig(fan, gigId).id()).isEqualTo("headline");
        verify(client, times(1)).setlistsOn(MBID, "Pale Kites", day);
        verify(client, times(1)).setlistsOn(null, "Night Bus", day);
    }

    @Test
    void theHeadlinersSetlistAlreadyFoundForTheGigIsReusedWithoutAnotherCall() {
        gig(Instant.parse("2025-10-17T19:30:00Z"), "Manchester", null);
        when(client.setlistsOn(MBID, "Pale Kites", LocalDate.of(2025, 10, 17)))
                .thenReturn(List.of(setlist("headline", "Manchester")));

        service.forGig(fan, gigId);
        assertThat(service.forGig(fan, gigId, paleKites).id()).isEqualTo("headline");
        verify(client, times(1)).setlistsOn(any(), any(), any());
    }

    @Test
    void aKnownSetlistIdIsUsedForAnActOnlyWhenItIsThatActs() {
        gig(Instant.parse("2025-10-17T19:30:00Z"), "Manchester", "7bd6a2a8");
        when(client.setlist("7bd6a2a8")).thenReturn(Optional.of(setlist("7bd6a2a8", "Manchester")));
        when(client.setlistsOn(null, "Night Bus", LocalDate.of(2025, 10, 17)))
                .thenReturn(List.of(setlist("support", "Manchester", "Night Bus")));

        assertThat(service.forGig(fan, gigId, paleKites).id()).isEqualTo("7bd6a2a8");
        // The known setlist is the headliner's: the support act is searched for by name and date.
        assertThat(service.forGig(fan, gigId, nightBus).id()).isEqualTo("support");
    }

    @Test
    void anActNotOnTheBillHasNoSetlistAndCostsNoCall() {
        gig(Instant.parse("2025-10-17T19:30:00Z"), "Manchester", null);

        assertThatThrownBy(() -> service.forGig(fan, gigId, UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("SETLIST_NOT_FOUND"));
        verify(client, never()).setlistsOn(any(), any(), any());
    }

    @Test
    void withoutAKeyOrForAnUnknownGigItSaysSo() {
        when(catalog.gigFacts(gigId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.forGig(fan, gigId))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("GIG_NOT_FOUND"));

        when(client.configured()).thenReturn(false);
        assertThatThrownBy(() -> service.forGig(fan, gigId))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("SETLISTS_UNAVAILABLE"));
    }
}

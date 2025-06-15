package app.soundcheck.ingestion.internal.pastgigs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.soundcheck.ingestion.internal.IngestionProperties;
import app.soundcheck.shared.web.ApiException;
import app.soundcheck.shared.web.RateLimitExceededException;
import app.soundcheck.shared.web.RateLimiter;
import app.soundcheck.support.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Past-gig search guards: short cache, daily budget, "not configured", per-user limit. */
class PastGigServiceTest {

    static final PastGigPage EMPTY = new PastGigPage(List.of(), 1, 0, 20);

    SetlistFmClient client = mock(SetlistFmClient.class);
    MutableClock clock = new MutableClock();
    UUID fan = UUID.randomUUID();
    PastGigService service;

    @BeforeEach
    void setUp() {
        when(client.configured()).thenReturn(true);
        when(client.search(any(), any(), anyInt())).thenReturn(EMPTY);
        service = new PastGigService(
                client, new RateLimiter(), properties(3), new SetlistFmBudget(properties(3), clock), clock);
    }

    static IngestionProperties properties(int dailyBudget) {
        var source = new MapConfigurationPropertySource(java.util.Map.of(
                "soundcheck.ingestion.setlistfm.daily-budget",
                String.valueOf(dailyBudget),
                "soundcheck.ingestion.setlistfm.per-user-per-hour",
                "100"));
        return new Binder(source).bindOrCreate("soundcheck.ingestion", IngestionProperties.class);
    }

    @Test
    void theSameSearchWithinTheHourIsAnsweredFromTheCacheWhoeverAsks() {
        service.search(fan, "Pale Kites", 2025, 1);
        service.search(UUID.randomUUID(), "  pale   KITES ", 2025, 1);
        verify(client, times(1)).search("Pale Kites", 2025, 1);

        clock.advance(Duration.ofMinutes(61));
        service.search(fan, "Pale Kites", 2025, 1);
        verify(client, times(2)).search(any(), any(), anyInt());
    }

    @Test
    void theDailyBudgetIsNeverExceededAndResetsTheNextDay() {
        service.search(fan, "Act One", null, 1);
        service.search(fan, "Act Two", null, 1);
        service.search(fan, "Act Three", null, 1);
        assertThatThrownBy(() -> service.search(fan, "Act Four", null, 1))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("PAST_GIGS_BUSY"));

        clock.advance(Duration.ofDays(1));
        service.search(fan, "Act Four", null, 1);
        verify(client, times(4)).search(any(), any(), anyInt());
    }

    @Test
    void withoutAKeyTheLookupSaysItIsUnavailable() {
        when(client.configured()).thenReturn(false);
        assertThatThrownBy(() -> service.search(fan, "Pale Kites", null, 1))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("PAST_GIGS_UNAVAILABLE"));
    }

    /** The per-user limit: thirty searches an hour by default. */
    @Test
    void aFanMayRunThirtySearchesAnHourAndAnswersFromTheCacheAreFree() {
        IngestionProperties defaults = new Binder(new MapConfigurationPropertySource(
                        java.util.Map.of("soundcheck.ingestion.setlistfm.daily-budget", "1000")))
                .bindOrCreate("soundcheck.ingestion", IngestionProperties.class);
        assertThat(defaults.setlistfm().perUserPerHour()).isEqualTo(30);
        PastGigService limited =
                new PastGigService(client, new RateLimiter(), defaults, new SetlistFmBudget(defaults, clock), clock);

        for (int i = 0; i < 30; i++) {
            limited.search(fan, "Act " + i, 2025, 1);
        }
        limited.search(fan, "act 0", 2025, 1); // the same search: from the cache, not counted
        assertThatThrownBy(() -> limited.search(fan, "Act 30", 2025, 1)).isInstanceOf(RateLimitExceededException.class);
        limited.search(UUID.randomUUID(), "Act 30", 2025, 1); // another fan has their own allowance
        verify(client, times(31)).search(any(), any(), anyInt());
    }
}

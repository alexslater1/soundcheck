package app.soundcheck.social.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.soundcheck.catalog.NewGigs;
import app.soundcheck.catalog.NewGigs.ArtistRef;
import app.soundcheck.catalog.NewGigs.NewGig;
import app.soundcheck.catalog.NewGigs.Position;
import app.soundcheck.catalog.NewGigs.SearchHit;
import app.soundcheck.social.NewGigsFound;
import app.soundcheck.social.NewGigsFound.AlertedGig;
import app.soundcheck.social.NewGigsFound.Reason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Which new gigs alert whom, and why. The pass end to end is {@code GigAlertsIT}. */
class GigAlertServiceTest {

    static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    final UUID keo = UUID.randomUUID();
    final UUID support = UUID.randomUUID();
    final UUID social = UUID.randomUUID();

    final UUID keoFan = UUID.randomUUID();
    final UUID supportFan = UUID.randomUUID();
    final UUID venueFan = UUID.randomUUID();
    final UUID searcher = UUID.randomUUID();

    FakeNewGigs catalog;
    GigAlertRepository repository;
    List<NewGigsFound> published;
    GigAlertService service;

    @BeforeEach
    void setUp() {
        catalog = new FakeNewGigs();
        repository = mock(GigAlertRepository.class);
        when(repository.startedAt()).thenReturn(Optional.of(NOW.minus(Duration.ofDays(30))));
        // Nothing sent before: markSent answers "all of them are new".
        when(repository.markSent(any(), anyList(), any())).thenAnswer(inv -> {
            List<GigAlertRepository.Sent> gigs = inv.getArgument(1);
            Set<UUID> ids = new HashSet<>();
            gigs.forEach(g -> ids.add(g.gigId()));
            return ids;
        });
        published = new ArrayList<>();
        service = new GigAlertService(
                catalog, repository, event -> published.add((NewGigsFound) event), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void theFirstPassOnlyRecordsWhenAlertsStarted() {
        when(repository.startedAt()).thenReturn(Optional.empty());
        catalog.gigs.add(gig("Keo", NOW.plus(Duration.ofDays(9)), "SCHEDULED", null, keo));

        GigAlertService.PassResult result = service.run();

        assertThat(result.started()).isTrue();
        verify(repository).start(NOW);
        assertThat(published).isEmpty();
    }

    @Test
    void eachFollowerHearsOnceWithOneReasonActBeforeVenueBeforeSearch() {
        NewGig gig = gig("Keo + Support", NOW.plus(Duration.ofDays(9)), "SCHEDULED", null, keo, support);
        catalog.gigs.add(gig);
        followers(
                Map.of(keo, List.of(keoFan), support, List.of(supportFan, keoFan)),
                Map.of(social, List.of(venueFan, keoFan)));
        catalog.hits.add(new SearchHit(searcher, UUID.randomUUID(), "Keo in London", gig.id()));
        catalog.hits.add(new SearchHit(venueFan, UUID.randomUUID(), "Anything at The Social", gig.id()));

        service.run();

        assertThat(published).hasSize(4);
        assertThat(only(keoFan)).isEqualTo(new Why(Reason.ARTIST, "Keo")); // the first act on the bill wins
        assertThat(only(supportFan)).isEqualTo(new Why(Reason.ARTIST, "Support"));
        assertThat(only(venueFan)).isEqualTo(new Why(Reason.VENUE, "The Social"));
        assertThat(only(searcher)).isEqualTo(new Why(Reason.SEARCH, "Keo in London"));
    }

    @Test
    void pastCancelledPostponedAndUnlistedGigsNeverAlert() {
        catalog.gigs.add(gig("Yesterday", NOW.minus(Duration.ofDays(1)), "SCHEDULED", null, keo));
        catalog.gigs.add(gig("Cancelled", NOW.plus(Duration.ofDays(3)), "CANCELLED", null, keo));
        catalog.gigs.add(gig("Postponed", NOW.plus(Duration.ofDays(3)), "POSTPONED", null, keo));
        catalog.gigs.add(gig("Unlisted", NOW.plus(Duration.ofDays(3)), "UNLISTED", null, keo));
        followers(Map.of(keo, List.of(keoFan)), Map.of());

        GigAlertService.PassResult result = service.run();

        assertThat(result.gigsSeen()).isZero();
        assertThat(published).isEmpty();
    }

    @Test
    void theFanWhoAddedAGigIsNotToldAboutIt() {
        catalog.gigs.add(gig("Keo", NOW.plus(Duration.ofDays(2)), "SCHEDULED", keoFan, keo));
        followers(Map.of(keo, List.of(keoFan, supportFan)), Map.of());

        service.run();

        assertThat(published).extracting(NewGigsFound::userId).containsExactly(supportFan);
    }

    @Test
    void nothingIsPublishedWhenEveryGigWasAlreadyAnnounced() {
        catalog.gigs.add(gig("Keo", NOW.plus(Duration.ofDays(2)), "SCHEDULED", null, keo));
        followers(Map.of(keo, List.of(keoFan)), Map.of());
        when(repository.markSent(any(), anyList(), any())).thenReturn(Set.of());

        GigAlertService.PassResult result = service.run();

        assertThat(published).isEmpty();
        assertThat(result.usersAlerted()).isZero();
    }

    @Test
    void oneAlertListsAUsersNewGigsSoonestFirst() {
        catalog.gigs.add(gig("Later", NOW.plus(Duration.ofDays(20)), "SCHEDULED", null, keo));
        catalog.gigs.add(gig("Sooner", NOW.plus(Duration.ofDays(2)), "SCHEDULED", null, keo));
        followers(Map.of(keo, List.of(keoFan)), Map.of());

        service.run();

        assertThat(published).hasSize(1);
        assertThat(published.getFirst().gigs()).extracting(AlertedGig::title).containsExactly("Sooner", "Later");
    }

    @Test
    void theWindowIsTheLookbackButNeverBeforeAlertsStarted() {
        when(repository.startedAt()).thenReturn(Optional.of(NOW.minus(Duration.ofHours(3))));
        service.run();
        assertThat(catalog.sinces).containsExactly(NOW.minus(Duration.ofHours(3)));

        when(repository.startedAt()).thenReturn(Optional.of(NOW.minus(Duration.ofDays(30))));
        catalog.sinces.clear();
        service.run();
        assertThat(catalog.sinces).containsExactly(NOW.minus(GigAlertService.LOOKBACK));
    }

    @Test
    void aWindowLongerThanOnePageIsReadPageByPage() {
        for (int i = 0; i < GigAlertService.PAGE + 5; i++) {
            catalog.gigs.add(gig("Gig " + i, NOW.plus(Duration.ofDays(5)), "SCHEDULED", null, keo));
        }
        followers(Map.of(keo, List.of(keoFan)), Map.of());

        GigAlertService.PassResult result = service.run();

        assertThat(catalog.pagesRead).isEqualTo(2);
        assertThat(result.gigsSeen()).isEqualTo(GigAlertService.PAGE + 5);
        assertThat(published.getFirst().gigs()).hasSize(GigAlertService.PAGE + 5);
    }

    @Test
    void noFollowersNoSearchesNoAlert() {
        catalog.gigs.add(gig("Keo", NOW.plus(Duration.ofDays(2)), "SCHEDULED", null, keo));
        followers(Map.of(), Map.of());

        service.run();

        assertThat(published).isEmpty();
        verify(repository, never()).markSent(any(), anyList(), any());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    record Why(Reason reason, String because) {}

    private Why only(UUID user) {
        List<NewGigsFound> mine =
                published.stream().filter(e -> e.userId().equals(user)).toList();
        assertThat(mine).hasSize(1);
        assertThat(mine.getFirst().gigs()).hasSize(1);
        AlertedGig gig = mine.getFirst().gigs().getFirst();
        return new Why(gig.reason(), gig.because());
    }

    private void followers(Map<UUID, List<UUID>> artistFans, Map<UUID, List<UUID>> venueFans) {
        when(repository.artistFollowers(any())).thenReturn(artistFans);
        when(repository.venueFollowers(any())).thenReturn(venueFans);
    }

    private int created = 0;

    private NewGig gig(String title, Instant startsAt, String status, @Nullable UUID addedBy, UUID... artists) {
        List<ArtistRef> bill = new ArrayList<>();
        for (UUID id : artists) {
            bill.add(new ArtistRef(id, id.equals(keo) ? "Keo" : "Support"));
        }
        return new NewGig(
                UUID.randomUUID(),
                title,
                startsAt,
                status,
                social,
                "The Social",
                "London",
                bill,
                NOW.minus(Duration.ofMinutes(60)).plusSeconds(created++),
                addedBy);
    }

    /** The catalog's side, in memory: pages by (createdAt, id) like the real one. */
    static final class FakeNewGigs implements NewGigs {
        final List<NewGig> gigs = new ArrayList<>();
        final List<SearchHit> hits = new ArrayList<>();
        final List<Instant> sinces = new ArrayList<>();
        int pagesRead = 0;

        @Override
        public List<NewGig> createdSince(Instant since, @Nullable Position after, int limit) {
            if (after == null) {
                sinces.add(since);
            }
            pagesRead++;
            return gigs.stream()
                    .filter(g -> !g.createdAt().isBefore(since))
                    .filter(g -> after == null
                            || g.createdAt().isAfter(after.createdAt())
                            || (g.createdAt().equals(after.createdAt()) && g.id().compareTo(after.gigId()) > 0))
                    .sorted((a, b) -> a.createdAt().equals(b.createdAt())
                            ? a.id().compareTo(b.id())
                            : a.createdAt().compareTo(b.createdAt()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<SearchHit> savedSearchHits(Collection<UUID> gigIds) {
            return hits.stream().filter(h -> gigIds.contains(h.gigId())).toList();
        }
    }
}

package app.soundcheck.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.shared.partitions.DailyPartitions;
import app.soundcheck.shared.partitions.PartitionedTable;
import app.soundcheck.signals.internal.EventPartitionMaintenance;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.MutableClock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/**
 * The maintenance job creates the partitions for today and the next seven days,
 * idempotently, and drops those past retention (90 days for the event log, 30 for the served-item log).
 */
class EventPartitionMaintenanceIT extends IntegrationTest {

    static final PartitionedTable EVENTS = new PartitionedTable("signals", "interaction_event", 90);
    static final PartitionedTable SERVED = new PartitionedTable("feed", "served_item", 30);

    @Autowired
    EventPartitionMaintenance maintenance;

    @Autowired
    DailyPartitions partitions;

    @Autowired
    List<PartitionedTable> declared;

    @Autowired
    MutableClock clock;

    @Autowired
    DSLContext dsl;

    @AfterEach
    void backToRealTime() {
        clock.reset();
    }

    @Test
    void bothEventTablesAreDeclaredWithTheirRetention() {
        assertThat(declared).containsExactlyInAnyOrder(EVENTS, SERVED);
    }

    @Test
    void startupAlreadyCreatedTodayAndTheNextSevenDays() {
        LocalDate today = today();
        for (PartitionedTable table : List.of(EVENTS, SERVED)) {
            assertThat(partitions.partitions(table)).containsAll(names(table, today, today.plusDays(7)));
        }
    }

    @Test
    void theJobCreatesSevenDaysAheadAndRunningItAgainChangesNothing() {
        // Twenty days on, none of the partitions the job needs exists yet.
        clock.advance(Duration.ofDays(20));
        LocalDate today = today();
        List<String> wanted = names(EVENTS, today, today.plusDays(7));
        assertThat(partitions.partitions(EVENTS)).doesNotContainAnyElementsOf(wanted);

        maintenance.run();

        assertThat(partitions.partitions(EVENTS)).containsAll(wanted);
        assertThat(partitions.partitions(EVENTS)).doesNotContain(DailyPartitions.nameFor(EVENTS, today.plusDays(8)));
        assertThat(partitions.partitions(SERVED)).containsAll(names(SERVED, today, today.plusDays(7)));
        List<String> afterFirstRun = partitions.partitions(EVENTS);

        maintenance.run();

        assertThat(partitions.partitions(EVENTS)).isEqualTo(afterFirstRun);
        // A row for the last covered day finds its partition; one for the day after does not.
        insertEvent(today.plusDays(7));
        assertThatThrownBy(() -> insertEvent(today.plusDays(8)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("no partition");

        // Leave the database as other tests expect it: no partitions in their future.
        dsl.execute(
                "delete from signals.interaction_event where occurred_at >= ?::timestamptz",
                today.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime());
    }

    @Test
    void partitionsPastRetentionAreDroppedAndTheLastRetainedDayIsKept() {
        LocalDate today = today();
        partitions.ensure(EVENTS, today.minusDays(91), today.minusDays(90));
        partitions.ensure(SERVED, today.minusDays(31), today.minusDays(30));
        insertEvent(today.minusDays(91));

        maintenance.run();

        assertThat(partitions.partitions(EVENTS))
                .contains(DailyPartitions.nameFor(EVENTS, today.minusDays(90)))
                .doesNotContain(DailyPartitions.nameFor(EVENTS, today.minusDays(91)));
        assertThat(partitions.partitions(SERVED))
                .contains(DailyPartitions.nameFor(SERVED, today.minusDays(30)))
                .doesNotContain(DailyPartitions.nameFor(SERVED, today.minusDays(31)));
        // The rows went with the partition.
        assertThat(dsl.fetchCount(dsl.selectFrom("signals.interaction_event")
                        .where(
                                "occurred_at < ?::timestamptz",
                                today.minusDays(90).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime())))
                .isZero();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    private static List<String> names(PartitionedTable table, LocalDate from, LocalDate to) {
        List<String> names = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            names.add(DailyPartitions.nameFor(table, day));
        }
        return names;
    }

    private void insertEvent(LocalDate day) {
        dsl.execute(
                """
                insert into signals.interaction_event (occurred_at, user_id, post_id, surface, event_type)
                values (?::timestamptz, ?, ?, 'FOR_YOU', 'IMPRESSION')
                """,
                day.atTime(12, 0).atOffset(ZoneOffset.UTC),
                UUID.randomUUID(),
                UUID.randomUUID());
    }
}

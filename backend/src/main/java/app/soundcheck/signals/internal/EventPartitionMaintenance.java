package app.soundcheck.signals.internal;

import app.soundcheck.shared.partitions.DailyPartitions;
import app.soundcheck.shared.partitions.PartitionedTable;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the daily partitions of the event tables in step with the calendar: a partition
 * for today and each of the next seven days, and none older than the table's retention. It handles
 * every {@link PartitionedTable} a module declares: the event log here, the served-item log in feed.
 *
 * <p>Partitions are created at start-up in every process, while the bean is initialised and so before
 * any request is served (an insert into a day without a partition fails, and the API must accept events
 * even where no scheduler runs; Flyway has already migrated by then), and hourly by the scheduler,
 * which also drops the expired ones. {@code @Scheduled} only fires where scheduling is enabled, that
 * is in the scheduler role.
 */
@Component
public class EventPartitionMaintenance implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(EventPartitionMaintenance.class);
    static final int DAYS_AHEAD = 7;

    private final List<PartitionedTable> tables;
    private final DailyPartitions partitions;
    private final Clock clock;

    EventPartitionMaintenance(List<PartitionedTable> tables, DailyPartitions partitions, Clock clock) {
        this.tables = tables;
        this.partitions = partitions;
        this.clock = clock;
    }

    /** At start-up, before the web server accepts its first request. */
    @Override
    public void afterPropertiesSet() {
        createAhead();
    }

    @Scheduled(cron = "0 5 * * * *")
    @SchedulerLock(name = "event-partition-maintenance", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    void hourly() {
        run();
    }

    /** Creates the partitions for today and the next seven days, and drops those past retention. */
    public void run() {
        createAhead();
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        for (PartitionedTable table : tables) {
            List<String> dropped = partitions.dropBefore(table, today.minusDays(table.retentionDays()));
            if (!dropped.isEmpty()) {
                log.info("dropped partitions of {}.{}: {}", table.schema(), table.table(), dropped);
            }
        }
    }

    private void createAhead() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        for (PartitionedTable table : tables) {
            List<String> created = partitions.ensure(table, today, today.plusDays(DAYS_AHEAD));
            if (!created.isEmpty()) {
                log.info("created partitions of {}.{}: {}", table.schema(), table.table(), created);
            }
        }
    }
}

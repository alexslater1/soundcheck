package app.soundcheck.shared.partitions;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and drops the daily partitions of a {@link PartitionedTable}. A partition holds one UTC day:
 * {@code [day 00:00Z, next day 00:00Z)}.
 *
 * <p>Both operations are safe to repeat and safe to run from several instances at once: each takes a
 * transaction-scoped advisory lock first, so two processes starting together do not both try to create
 * the same table.
 */
@Component
public class DailyPartitions {

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.BASIC_ISO_DATE; // 20261002

    private final DSLContext dsl;

    DailyPartitions(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Makes sure a partition exists for every day from {@code from} to {@code to}, inclusive.
     *
     * @return the names of the partitions that were created by this call
     */
    @Transactional
    public List<String> ensure(PartitionedTable table, LocalDate from, LocalDate to) {
        lock();
        List<String> existing = partitions(table);
        List<String> created = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            String name = nameFor(table, day);
            if (existing.contains(name)) {
                continue;
            }
            dsl.execute(
                    "create table {0} partition of {1} for values from ({2}) to ({3})",
                    DSL.name(table.schema(), name),
                    DSL.name(table.schema(), table.table()),
                    DSL.inline(day + " 00:00:00+00"),
                    DSL.inline(day.plusDays(1) + " 00:00:00+00"));
            created.add(name);
        }
        return created;
    }

    /**
     * Drops the partitions whose day is before {@code keepFrom}. Dropping a partition removes its rows
     * without touching the others, which is why the event tables are partitioned at all.
     *
     * @return the names of the partitions that were dropped
     */
    @Transactional
    public List<String> dropBefore(PartitionedTable table, LocalDate keepFrom) {
        lock();
        List<String> dropped = new ArrayList<>();
        for (String name : partitions(table)) {
            LocalDate day = dayOf(table, name);
            if (day != null && day.isBefore(keepFrom)) {
                dsl.execute("drop table {0}", DSL.name(table.schema(), name));
                dropped.add(name);
            }
        }
        return dropped;
    }

    /** The table's partitions, by name. */
    public List<String> partitions(PartitionedTable table) {
        return dsl.fetch(
                        """
                        select child.relname
                          from pg_inherits
                          join pg_class child on child.oid = pg_inherits.inhrelid
                          join pg_class parent on parent.oid = pg_inherits.inhparent
                          join pg_namespace schema on schema.oid = parent.relnamespace
                         where schema.nspname = ? and parent.relname = ?
                         order by child.relname
                        """,
                        table.schema(),
                        table.table())
                .map(r -> r.get(0, String.class));
    }

    public static String nameFor(PartitionedTable table, LocalDate day) {
        return table.table() + "_p" + SUFFIX.format(day);
    }

    /** The day a partition holds, from its name; null for a child table this class did not create. */
    private static LocalDate dayOf(PartitionedTable table, String partition) {
        String prefix = table.table() + "_p";
        if (!partition.startsWith(prefix)) {
            return null;
        }
        try {
            return LocalDate.parse(partition.substring(prefix.length()), SUFFIX);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private void lock() {
        dsl.fetch("select pg_advisory_xact_lock(hashtextextended('daily-partitions', 0))");
    }
}

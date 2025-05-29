package app.soundcheck.shared.partitions;

/**
 * A table partitioned by day whose partitions the application creates ahead of time and drops when they
 * are older than the retention. The module that owns the table declares one bean of this
 * type; the maintenance job handles every declared table without knowing the modules.
 *
 * @param schema        the table's schema, lower case
 * @param table         the partitioned (parent) table; partitions are named {@code <table>_pYYYYMMDD}
 * @param retentionDays a partition is dropped once its day is more than this many days ago
 */
public record PartitionedTable(String schema, String table, int retentionDays) {

    public PartitionedTable {
        if (!schema.matches("[a-z_]+") || !table.matches("[a-z_]+")) {
            // The names are concatenated into DDL: only plain identifiers are accepted.
            throw new IllegalArgumentException("not a plain identifier: " + schema + "." + table);
        }
        if (retentionDays < 1) {
            throw new IllegalArgumentException("retentionDays must be at least 1");
        }
    }
}

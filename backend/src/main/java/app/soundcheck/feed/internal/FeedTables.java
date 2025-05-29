package app.soundcheck.feed.internal;

import app.soundcheck.shared.partitions.PartitionedTable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The feed module's partitioned table and its retention. */
@Configuration(proxyBeanMethods = false)
class FeedTables {

    static final int SERVED_ITEM_RETENTION_DAYS = 30;

    @Bean
    PartitionedTable servedItemPartitions() {
        return new PartitionedTable("feed", "served_item", SERVED_ITEM_RETENTION_DAYS);
    }
}

package app.soundcheck.signals.internal;

import app.soundcheck.shared.partitions.PartitionedTable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The signals module's partitioned table and its retention. */
@Configuration(proxyBeanMethods = false)
class SignalsTables {

    static final int EVENT_RETENTION_DAYS = 90;

    @Bean
    PartitionedTable interactionEventPartitions() {
        return new PartitionedTable("signals", "interaction_event", EVENT_RETENTION_DAYS);
    }
}

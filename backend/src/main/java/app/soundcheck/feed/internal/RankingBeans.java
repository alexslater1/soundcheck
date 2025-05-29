package app.soundcheck.feed.internal;

import app.soundcheck.feed.internal.ranking.FeatureSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The feature schema, read once from the shared contract file at start-up. */
@Configuration(proxyBeanMethods = false)
class RankingBeans {

    @Bean
    FeatureSchema featureSchema() {
        return FeatureSchema.load();
    }
}

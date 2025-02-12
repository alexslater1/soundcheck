package app.soundcheck.shared.config;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * The application's one {@link JsonMapper} (HTTP bodies, outbox events, stored JSON): {@link JsonMappers}'
 * settings, plus the modules and settings Spring Boot contributes through its builder.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    JsonMapper jsonMapper(Jackson2ObjectMapperBuilder builder) {
        JsonMapper mapper = JsonMappers.builder().build();
        builder.configure(mapper);
        return mapper;
    }
}

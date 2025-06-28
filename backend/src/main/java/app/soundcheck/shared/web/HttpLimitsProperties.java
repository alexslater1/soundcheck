package app.soundcheck.shared.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * {@code soundcheck.http.*}: how large a request body the API reads.
 * 1 MB is far above any real body: clips and images go straight to S3, and the largest request, an
 * events batch of 100, is about 25 KB.
 */
@ConfigurationProperties(prefix = "soundcheck.http")
public record HttpLimitsProperties(@DefaultValue("1MB") DataSize maxRequestBody) {}

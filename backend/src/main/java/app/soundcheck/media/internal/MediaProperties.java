package app.soundcheck.media.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * {@code soundcheck.media.*}: the upload limits (3 minutes and 1 GB) and where
 * finished media is served from.
 */
@ConfigurationProperties(prefix = "soundcheck.media")
public record MediaProperties(
        /** A 3-minute 4K original on Wi-Fi fits; anything larger the phone compresses first. */
        @DefaultValue("1GB") DataSize maxVideoSize,
        @DefaultValue("10MB") DataSize maxImageSize,
        /** 3 minutes (a whole song) plus one second of tolerance. Checked again by the worker's probe. */
        @DefaultValue("181s") Duration maxVideoDuration,
        /** Up to this size an upload is one PUT; above it, multipart. */
        @DefaultValue("16MB") DataSize singleUploadMax,
        @DefaultValue("8MB") DataSize partSize,
        @DefaultValue("60m") Duration urlTtl,
        /** Uploads are limited to 30 per hour per user. */
        @DefaultValue("30") int uploadsPerHour,
        /** The origin that serves {@code /media/*}: Caddy locally, CloudFront on AWS. */
        @DefaultValue("http://localhost:8088") String publicBaseUrl) {}

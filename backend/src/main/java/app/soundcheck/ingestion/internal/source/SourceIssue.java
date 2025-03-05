package app.soundcheck.ingestion.internal.source;

import org.jspecify.annotations.Nullable;

/** A data-quality observation made while fetching or mapping; sampled into the run's issues. */
public record SourceIssue(String kind, @Nullable String externalId, String detail) {}

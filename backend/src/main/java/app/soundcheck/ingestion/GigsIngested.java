package app.soundcheck.ingestion;

import java.time.Instant;
import java.util.UUID;

/** Domain event: an ingestion run finished. Consumed by analytics only in v1. */
public record GigsIngested(
        UUID runId, String source, String status, int created, int updated, int merged, Instant finishedAt) {}

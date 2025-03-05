package app.soundcheck.ingestion.internal.enrich;

import com.fasterxml.jackson.databind.JsonNode;

/** Receives each raw API response so the run can keep it replayable in {@code ingestion.raw_payload}. */
@FunctionalInterface
public interface PayloadSink {

    /** @param request the request without secrets */
    void accept(String source, String request, JsonNode body);
}

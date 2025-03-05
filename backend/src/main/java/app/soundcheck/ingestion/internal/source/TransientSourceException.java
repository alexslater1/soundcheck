package app.soundcheck.ingestion.internal.source;

/** A source call failed in a way worth retrying: 429, 5xx or an I/O error. Messages never contain keys. */
public class TransientSourceException extends SourceException {

    public TransientSourceException(String message) {
        super(message);
    }
}

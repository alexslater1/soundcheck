package app.soundcheck.ingestion.internal.source;

/** A source call failed in a way retrying will not fix (bad key, bad request). Messages never contain keys. */
public class SourceException extends RuntimeException {

    public SourceException(String message) {
        super(message);
    }
}

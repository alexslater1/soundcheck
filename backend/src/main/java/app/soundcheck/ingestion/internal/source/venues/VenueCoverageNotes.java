package app.soundcheck.ingestion.internal.source.venues;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.catalog.VenueNote;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Venues whose own listings are not read get a note in the app wherever they
 * appear, so a short list there reads as "incomplete", not "nothing on". The registry says which and why;
 * the catalog stores the note on the matching venues. Applied at start-up and before each daily run (the
 * scheduler), so a venue a fan adds later gets its note within a day.
 */
@Component
public class VenueCoverageNotes {

    private static final Logger log = LoggerFactory.getLogger(VenueCoverageNotes.class);

    private final VenueRegistry registry;
    private final CatalogIngest catalog;

    public VenueCoverageNotes(VenueRegistry registry, CatalogIngest catalog) {
        this.registry = registry;
        this.catalog = catalog;
    }

    /** Sets the notes; returns how many catalog venues carry one. */
    public int apply() {
        List<VenueNote> notes = registry.notRead().stream()
                .map(v -> new VenueNote(v.name(), v.aliases(), v.postcodePrefix(), note(v.reason())))
                .toList();
        int noted = catalog.applyCoverageNotes(notes);
        log.info("Coverage notes: {} venues not read, {} catalog venues noted", notes.size(), noted);
        return noted;
    }

    /** The sentence the app shows: why, then what it means for the list. */
    static String note(String reason) {
        return "We don't read this venue's own listings: " + reason
                + ". Its gigs here come from Ticketmaster and fans, so some may be missing.";
    }
}

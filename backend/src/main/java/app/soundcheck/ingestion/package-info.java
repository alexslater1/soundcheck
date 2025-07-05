/**
 * Source adapters (Ticketmaster, venues' own websites, fixtures; Skiddle when its key arrives),
 * enrichment (MusicBrainz, Last.fm, postcode geocoding), past-gig lookups (setlist.fm), entity
 * resolution and ingestion runs (schema {@code ingestion}). Writes to catalog through its public API.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared", "catalog"})
package app.soundcheck.ingestion;

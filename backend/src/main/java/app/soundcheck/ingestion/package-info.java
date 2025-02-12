/**
 * Source adapters (Ticketmaster, Skiddle, fixtures), enrichment (MusicBrainz, Last.fm), entity
 * resolution and ingestion runs (schema {@code ingestion}). Writes to catalog through its public API.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared", "catalog"})
package app.soundcheck.ingestion;

package app.soundcheck.ingestion.internal.source.venues;

import app.soundcheck.ingestion.internal.source.SourceIssue;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reads one kind of venue website. Pure: the fetching is done by {@link VenueSiteSource} through
 * the {@link PoliteFetcher}, so an adapter is tested on a saved page without any network.
 */
public interface VenueAdapter {

    /** The name used in the registry's {@code adapter} field. */
    String kind();

    /** The first page to fetch (some sites take today's date or a page size). */
    default URI firstPage(VenueSite site, LocalDate today) {
        return URI.create(site.url());
    }

    Page read(VenueSite site, String body, LocalDate today);

    /** What one fetched page held, and the next page to fetch, if the site pages its listings. */
    record Page(List<Listing> listings, List<SourceIssue> issues, @Nullable URI next) {}
}

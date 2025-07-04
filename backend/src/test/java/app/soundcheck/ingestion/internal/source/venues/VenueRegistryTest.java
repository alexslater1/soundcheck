package app.soundcheck.ingestion.internal.source.venues;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.shared.config.JsonMappers;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The shipped registry ({@code venues/london.json}) is read the way the app reads it, and every entry is
 * usable: a unique key, an adapter that exists, an https URL, a place in Greater London, and a skip
 * pattern that compiles. The local profile enables only keys that exist.
 */
class VenueRegistryTest {

    static final JsonMapper JSON = JsonMappers.builder().build();

    static List<VenueSite> shipped() throws IOException {
        try (InputStream in = VenueRegistryTest.class.getResourceAsStream("/venues/london.json")) {
            return VenueRegistry.parse(JSON.readTree(in));
        }
    }

    @Test
    void everyEntryIsUsable() throws IOException {
        List<VenueSite> sites = shipped();
        Set<String> adapters = Set.of(
                new HopeAndAnchorAdapter(JSON).kind(),
                new TribeEventsAdapter(JSON).kind(),
                new MothRssAdapter().kind(),
                new MothPageAdapter(JSON).kind(),
                new JsonLdAdapter(JSON).kind(),
                new SquarespaceAdapter().kind(),
                new SquarespaceListAdapter(JSON).kind(),
                new SelectorAdapter().kind());

        assertThat(sites).hasSizeGreaterThanOrEqualTo(11);
        assertThat(sites.stream().map(VenueSite::key).distinct()).hasSameSizeAs(sites);
        assertThat(sites).allSatisfy(site -> {
            assertThat(adapters).as(site.key()).contains(site.adapter());
            assertThat(site.url()).as(site.key()).startsWith("https://");
            assertThat(site.venue().lat()).as(site.key()).isBetween(51.28, 51.70);
            assertThat(site.venue().lng()).as(site.key()).isBetween(-0.52, 0.34);
            assertThat(site.venue().postcode()).as(site.key()).isNotBlank();
            if (site.adapter().equals("html-selectors")) {
                assertThat(site.selectors()).as(site.key()).isNotNull();
                assertThat(site.selectors().dateFormats()).as(site.key()).isNotEmpty();
            }
            if (site.skipTitles() != null) {
                Pattern.compile(site.skipTitles());
            }
        });
        VenueSite dome = sites.stream()
                .filter(s -> s.key().equals("the-dome"))
                .findFirst()
                .orElseThrow();
        assertThat(dome.otherTicketmasterVenueIds()).containsExactly("KovZ9177Ho0", "KovZ9177To0");
    }

    @Test
    void theLocalProfileEnablesOnlyKeysThatExist() throws IOException {
        String local;
        try (InputStream in = VenueRegistryTest.class.getResourceAsStream("/application-local.yaml")) {
            local = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m =
                Pattern.compile("sites: \\$\\{INGESTION_VENUE_SITES:([^}]*)}").matcher(local);
        assertThat(m.find()).isTrue();
        Set<String> keys = shipped().stream().map(VenueSite::key).collect(Collectors.toSet());
        assertThat(Arrays.asList(m.group(1).split(","))).isNotEmpty().allSatisfy(k -> assertThat(keys)
                .contains(k));
    }

    @Test
    void theVenuesNotReadAreListedWithAReasonAndCommonNamesArePinnedToOnePlace() throws IOException {
        List<VenueRegistry.NotRead> notRead;
        try (InputStream in = VenueRegistryTest.class.getResourceAsStream("/venues/london.json")) {
            notRead = VenueRegistry.parseNotRead(JSON.readTree(in));
        }
        // The six venues held back and every "ask" venue.
        assertThat(notRead)
                .extracting(VenueRegistry.NotRead::name)
                .contains("The Water Rats", "The Windmill", "The Half Moon", "The Waiting Room", "The 100 Club")
                .contains("Signature Brew Haggerston", "Bush Hall", "Sebright Arms", "Rough Trade East");
        assertThat(notRead).allSatisfy(v -> {
            assertThat(v.reason()).as(v.name()).isNotBlank().doesNotEndWith(".");
            assertThat(VenueCoverageNotes.note(v.reason())).as(v.name()).startsWith("We don't read this venue's");
        });
        assertThat(notRead.stream().map(VenueRegistry.NotRead::name).distinct()).hasSameSizeAs(notRead);
        // A common pub name only where that venue is.
        assertThat(notRead)
                .filteredOn(v -> v.name().equals("The Half Moon"))
                .singleElement()
                .extracting(VenueRegistry.NotRead::postcodePrefix)
                .isEqualTo("SW15");
        // No venue is both read and not read.
        Set<String> read = shipped().stream().map(site -> site.venue().name()).collect(Collectors.toSet());
        assertThat(notRead).extracting(VenueRegistry.NotRead::name).doesNotContainAnyElementsOf(read);
    }
}

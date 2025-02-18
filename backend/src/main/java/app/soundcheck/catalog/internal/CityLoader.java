package app.soundcheck.catalog.internal;

import static app.soundcheck.shared.jooq.catalog.Tables.CITY;

import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads {@code fixtures/geo/uk-cities.csv} (GeoNames cities15000, GB, population ≥ 15k; CC BY 4.0) into
 * {@code catalog.city} when the API starts. Idempotent upsert keyed by the GeoNames id, so re-running
 * after a fixture update refreshes rows. The CSV is copied into the jar by Gradle ({@code geo/uk-cities.csv}).
 */
@Component
@ConditionalOnRole(Roles.API)
class CityLoader {

    static final String RESOURCE = "geo/uk-cities.csv";

    private static final Logger log = LoggerFactory.getLogger(CityLoader.class);

    private final DSLContext dsl;

    CityLoader(DSLContext dsl) {
        this.dsl = dsl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        int count = load();
        log.info("Loaded {} UK cities into catalog.city", count);
    }

    @Transactional
    public int load() {
        List<Query> upserts = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ClassPathResource(RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null || !header.startsWith("id,name,region,country_code,lat,lng,population")) {
                throw new IllegalStateException(RESOURCE + " has an unexpected header: " + header);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                upserts.add(upsert(CityRow.parse(line)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, e);
        }
        dsl.batch(upserts).execute();
        return upserts.size();
    }

    private Query upsert(CityRow row) {
        var location = DSL.field(
                "ST_SetSRID(ST_MakePoint({0}, {1}), 4326)::geography",
                Object.class, DSL.val(row.lng()), DSL.val(row.lat()));
        return dsl.insertInto(CITY, CITY.ID, CITY.NAME, CITY.REGION, CITY.COUNTRY_CODE, CITY.LOCATION, CITY.POPULATION)
                .values(
                        DSL.val(row.id()),
                        DSL.val(row.name()),
                        DSL.val(row.region()),
                        DSL.val(row.countryCode()),
                        location,
                        DSL.val(row.population()))
                .onConflict(CITY.ID)
                .doUpdate()
                .setAllToExcluded();
    }

    /** One CSV line: {@code id,name,region,country_code,lat,lng,population} (names contain no commas). */
    record CityRow(int id, String name, String region, String countryCode, double lat, double lng, Integer population) {

        static CityRow parse(String line) {
            String[] p = line.split(",", -1);
            if (p.length != 7) {
                throw new IllegalArgumentException("Bad city row: " + line);
            }
            return new CityRow(
                    Integer.parseInt(p[0]),
                    p[1],
                    p[2].isEmpty() ? null : p[2],
                    p[3],
                    Double.parseDouble(p[4]),
                    Double.parseDouble(p[5]),
                    p[6].isEmpty() ? null : Integer.parseInt(p[6]));
        }
    }
}

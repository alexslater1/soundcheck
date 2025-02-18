package app.soundcheck.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** {@code GET /genres} and {@code GET /cities?q=man} (Manchester first), both public. */
class ReferenceApiIT extends IntegrationTest {

    static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ReferenceData referenceData;

    @Test
    void genresAreSeededAndPublic() {
        ResponseEntity<List<Map<String, Object>>> response =
                rest.exchange("/api/v1/genres", HttpMethod.GET, null, LIST);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> genres = response.getBody();
        assertThat(genres).hasSizeGreaterThanOrEqualTo(20);
        assertThat(genres).extracting(g -> g.get("slug")).contains("indie", "techno", "hip-hop", "grime");
        Map<String, Object> indie = genres.stream()
                .filter(g -> "indie".equals(g.get("slug")))
                .findFirst()
                .orElseThrow();
        assertThat(indie.get("parentId")).isEqualTo(1); // Rock
    }

    @Test
    void cityAutocompleteReturnsManchesterFirstForMan() {
        ResponseEntity<List<Map<String, Object>>> response =
                rest.exchange("/api/v1/cities?q=man", HttpMethod.GET, null, LIST);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> cities = response.getBody();
        assertThat(cities).isNotEmpty().hasSizeLessThanOrEqualTo(10);
        assertThat(cities.getFirst().get("name")).isEqualTo("Manchester");
        assertThat(cities.getFirst().get("region")).isEqualTo("England");
        assertThat((Double) cities.getFirst().get("lat")).isBetween(53.0, 54.0);
        assertThat((Double) cities.getFirst().get("lng")).isBetween(-3.0, -2.0);
        assertThat(cities).allSatisfy(c -> assertThat(((String) c.get("name")).toLowerCase())
                .startsWith("man"));
    }

    @Test
    void cityAutocompleteIsCaseInsensitiveLimitedAndEmptyForNoMatch() {
        assertThat(referenceData.searchCities("LON", 10))
                .extracting(City::name)
                .first()
                .isEqualTo("London");
        assertThat(referenceData.searchCities("", 10))
                .hasSize(10)
                .extracting(City::name)
                .first()
                .isEqualTo("London");
        assertThat(referenceData.searchCities("xyzzy", 10)).isEmpty();
        assertThat(referenceData.searchCities("%", 10)).isEmpty(); // LIKE wildcards are escaped
    }

    @Test
    void cityLoaderIsIdempotent() {
        int before = referenceData.searchCities("", 1000).size();
        assertThat(before).isGreaterThan(800);
        assertThat(rest.getForEntity("/api/v1/cities?q=man", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(referenceData.searchCities("", 1000)).hasSize(before);
    }
}

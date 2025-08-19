package app.soundcheck.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.soundcheck.catalog.CatalogIngest;
import app.soundcheck.support.IntegrationTest;
import app.soundcheck.support.TestApi;
import app.soundcheck.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

/** The console's user picker — search, the synthetic tag, post counts and paging. */
class AdminUsersIT extends IntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    CatalogIngest catalogIngest;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ApplicationEventPublisher events;

    TestApi api;
    TestData data;

    @BeforeEach
    void setUp() {
        api = new TestApi(rest);
        data = new TestData(catalogIngest, dsl, tx, events);
    }

    @Test
    void searchFindsByHandleAndTagsSyntheticAccounts() {
        String prefix = "pick" + UUID.randomUUID().toString().substring(0, 6);
        TestApi.User real = api.register(prefix);
        TestApi.User persona = api.register(prefix, "seed.soundcheck.test");
        api.register("other");

        JsonNode items =
                ok(api.get("/api/v1/admin/users?q=" + prefix, api.admin())).path("items");

        assertThat(handles(items)).containsExactly(persona.handle(), real.handle()); // newest first
        JsonNode first = items.get(0);
        assertThat(first.path("synthetic").asBoolean()).isTrue();
        assertThat(first.path("email").asText("")).isEqualTo(persona.email());
        assertThat(first.path("role").asText("")).isEqualTo("USER");
        assertThat(first.path("createdAt").asText("")).isNotBlank();
        assertThat(items.get(1).path("synthetic").asBoolean()).isFalse();
    }

    @Test
    void theSyntheticFilterKeepsOneKindAndAnExactEmailFindsItsAccount() {
        String prefix = "syn" + UUID.randomUUID().toString().substring(0, 6);
        TestApi.User real = api.register(prefix);
        TestApi.User sim = api.register(prefix, "sim.soundcheck.test");

        assertThat(handles(ok(api.get("/api/v1/admin/users?synthetic=true&q=" + prefix, api.admin()))
                        .path("items")))
                .containsExactly(sim.handle());
        assertThat(handles(ok(api.get("/api/v1/admin/users?synthetic=false&q=" + prefix, api.admin()))
                        .path("items")))
                .containsExactly(real.handle());
        assertThat(handles(ok(api.get("/api/v1/admin/users?q=" + real.email(), api.admin()))
                        .path("items")))
                .containsExactly(real.handle());
    }

    @Test
    void eachRowCountsTheUsersPublishedPosts() {
        String prefix = "cnt" + UUID.randomUUID().toString().substring(0, 6);
        TestApi.User poster = api.register(prefix);
        for (int i = 0; i < 2; i++) {
            ResponseEntity<JsonNode> created = api.post(
                    "/api/v1/posts",
                    Map.of("clips", List.of(Map.of("mediaId", data.video(poster.id(), "READY")))),
                    poster);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        JsonNode row = ok(api.get("/api/v1/admin/users?q=" + prefix, api.admin()))
                .path("items")
                .get(0);

        assertThat(row.path("posts").asLong()).isEqualTo(2);
    }

    @Test
    void pagesWalkEveryMatchOnceWithACursor() {
        String prefix = "pg" + UUID.randomUUID().toString().substring(0, 6);
        List<String> registered = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            registered.add(api.register(prefix).handle());
        }

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String path = "/api/v1/admin/users?limit=2&q=" + prefix + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = ok(api.get(path, api.admin()));
            seen.addAll(handles(page.path("items")));
            cursor = page.path("nextCursor").isNull()
                    ? null
                    : page.path("nextCursor").asText("");
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(2);
        assertThat(seen).containsExactlyElementsOf(registered.reversed());
    }

    @Test
    void aUserCannotListAccounts() {
        TestApi.User user = api.register("nosy");
        assertThat(api.get("/api/v1/admin/users", user).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static List<String> handles(JsonNode items) {
        List<String> handles = new ArrayList<>();
        items.forEach(i -> handles.add(i.path("handle").asText("")));
        return handles;
    }
}

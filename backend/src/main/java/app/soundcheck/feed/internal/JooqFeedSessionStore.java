package app.soundcheck.feed.internal;

import static app.soundcheck.shared.jooq.feed.Tables.FEED_SESSION;

import app.soundcheck.feed.internal.ranking.CandidateSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.UncheckedIOException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

/**
 * Feed sessions in {@code feed.feed_session} (UNLOGGED: no write-ahead log, so cheap to write and
 * emptied by a crash, after which clients get 410 and start a new session). The items are one JSON
 * document, {@code [{p, s, f, x, src}]}: a session is always read and written whole.
 */
@Repository
class JooqFeedSessionStore implements FeedSessionStore {

    private final DSLContext dsl;
    private final JsonMapper json;

    JooqFeedSessionStore(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public void save(FeedSession session) {
        ArrayNode items = json.createArrayNode();
        for (FeedSession.Item item : session.items()) {
            ObjectNode node = items.addObject();
            node.put("p", item.postId().toString());
            node.put("s", item.score());
            ArrayNode features = node.putArray("f");
            for (double feature : item.features()) {
                features.add(feature);
            }
            node.put("x", item.exploration());
            ArrayNode sources = node.putArray("src");
            item.sources().forEach(source -> sources.add(source.name()));
        }
        String itemsJson;
        try {
            itemsJson = json.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        dsl.insertInto(FEED_SESSION)
                .set(FEED_SESSION.ID, session.id())
                .set(FEED_SESSION.USER_ID, session.userId())
                .set(FEED_SESSION.CONFIG_ID, session.configId())
                .set(FEED_SESSION.CREATED_AT, session.createdAt().atOffset(ZoneOffset.UTC))
                .set(FEED_SESSION.EXPIRES_AT, session.expiresAt().atOffset(ZoneOffset.UTC))
                .set(FEED_SESSION.ITEMS, JSONB.valueOf(itemsJson))
                .execute();
    }

    @Override
    public Optional<FeedSession> find(UUID id) {
        return dsl.selectFrom(FEED_SESSION).where(FEED_SESSION.ID.eq(id)).fetchOptional(r -> {
            JsonNode stored;
            try {
                stored = json.readTree(r.getItems().data());
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
            List<FeedSession.Item> items = new ArrayList<>();
            for (JsonNode node : stored) {
                double[] features = new double[node.path("f").size()];
                for (int i = 0; i < features.length; i++) {
                    features[i] = node.path("f").get(i).asDouble();
                }
                Set<CandidateSource> sources = EnumSet.noneOf(CandidateSource.class);
                node.path("src").forEach(source -> sources.add(CandidateSource.valueOf(source.asText(""))));
                items.add(new FeedSession.Item(
                        UUID.fromString(node.path("p").asText("")),
                        node.path("s").asDouble(),
                        features,
                        node.path("x").asBoolean(),
                        sources));
            }
            return new FeedSession(
                    r.getId(),
                    r.getUserId(),
                    r.getConfigId(),
                    r.getCreatedAt().toInstant(),
                    r.getExpiresAt().toInstant(),
                    items);
        });
    }

    @Override
    public void deleteOf(UUID userId) {
        dsl.deleteFrom(FEED_SESSION).where(FEED_SESSION.USER_ID.eq(userId)).execute();
    }
}

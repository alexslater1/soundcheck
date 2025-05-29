package app.soundcheck.feed.internal;

import app.soundcheck.feed.RankingConfigActivated;
import app.soundcheck.feed.internal.RankingConfigRepository.Row;
import app.soundcheck.feed.internal.ranking.FeatureSchema;
import app.soundcheck.feed.internal.ranking.RankingConfig;
import app.soundcheck.shared.Ids;
import app.soundcheck.shared.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The ranking configs: which one is active, cached in memory, and how a new
 * one becomes active.
 *
 * <p>Every feed request needs the active config, so it is kept in a field and not read from the database
 * each time. The field is refreshed when this instance activates a config, when another instance says
 * so over {@code LISTEN/NOTIFY} ({@link RankingConfigListener}), and at the latest every 60 seconds.
 */
@Service
public class RankingConfigs {

    private static final Logger log = LoggerFactory.getLogger(RankingConfigs.class);
    private static final TypeReference<Map<String, Double>> WEIGHTS = new TypeReference<>() {};

    private final RankingConfigRepository repository;
    private final FeatureSchema schema;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    private volatile @Nullable RankingConfig active;
    /** Configs that sessions still refer to after they stopped being active; they never change once used. */
    private final Map<UUID, RankingConfig> byId = new ConcurrentHashMap<>();

    RankingConfigs(
            RankingConfigRepository repository,
            FeatureSchema schema,
            JsonMapper json,
            ApplicationEventPublisher events,
            Clock clock) {
        this.repository = repository;
        this.schema = schema;
        this.json = json;
        this.events = events;
        this.clock = clock;
    }

    /** The active config, from memory. */
    public RankingConfig active() {
        RankingConfig current = active;
        return current != null ? current : reload();
    }

    /** Reads the active config from the database again and keeps it. */
    public RankingConfig reload() {
        Row row = repository.findActive().orElseThrow(() -> new IllegalStateException("no ACTIVE ranking config"));
        RankingConfig current = active;
        if (current == null || !current.id().equals(row.id())) {
            current = parse(row);
            active = current;
            byId.put(current.id(), current);
            log.info("active ranking config is now version {} ({})", current.version(), current.id());
        }
        return current;
    }

    /** A config by id: the one a feed session was built with, whether or not it is still active. */
    @Transactional(readOnly = true)
    public RankingConfig byId(UUID id) {
        return byId.computeIfAbsent(
                id,
                key -> parse(
                        repository.find(key).orElseThrow(() -> new IllegalStateException("no ranking config " + key))));
    }

    /**
     * Stores a new DRAFT after checking it: a config that could not be activated cannot be
     * saved either.
     *
     * @return the draft's id
     */
    @Transactional
    public UUID createDraft(
            Map<String, Double> weights,
            RankingConfig.Params params,
            String source,
            @Nullable String notes,
            @Nullable UUID createdBy) {
        UUID id = Ids.newId();
        RankingConfig.of(id, 0, schema.version(), weights, params, schema); // throws when invalid
        try {
            repository.insertDraft(
                    id,
                    schema.version(),
                    json.writeValueAsString(weights),
                    json.writeValueAsString(params),
                    source,
                    notes,
                    createdBy,
                    Instant.now(clock));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        return id;
    }

    /**
     * Makes a config the active one: validated again, the previous one archived, the change announced to
     * every instance. Feed sessions that already exist keep the config they were built with.
     */
    @Transactional
    public RankingConfig activate(UUID id) {
        Row row = repository
                .find(id)
                .orElseThrow(() -> ApiException.notFound("RANKING_CONFIG_NOT_FOUND", "No ranking config " + id));
        RankingConfig config = parse(row);
        repository.activate(id, Instant.now(clock));
        events.publishEvent(new RankingConfigActivated(id, row.version()));
        // This instance switches as soon as the change is committed; the others hear the NOTIFY.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reload();
            }
        });
        return config;
    }

    private RankingConfig parse(Row row) {
        Map<String, Double> weights;
        RankingConfig.Params params;
        try {
            weights = new HashMap<>(json.readValue(row.weightsJson(), WEIGHTS));
            // A parameter left out of the JSON would be read as 0, which is a valid value for some of them.
            List<String> missing =
                    RankingConfig.Params.missingFrom(json.readTree(row.paramsJson()).properties().stream()
                            .map(Map.Entry::getKey)
                            .toList());
            if (!missing.isEmpty()) {
                throw new RankingConfig.InvalidRankingConfigException(List.of("params must also name " + missing));
            }
            params = json.readValue(row.paramsJson(), RankingConfig.Params.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        return RankingConfig.of(row.id(), row.version(), row.featureSchemaVersion(), weights, params, schema);
    }
}

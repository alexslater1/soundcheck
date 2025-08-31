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
    private static final String DRAFT = "DRAFT";

    private final RankingConfigRepository repository;
    private final FeatureSchema schema;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    private volatile @Nullable RankingConfig active;
    /**
     * ACTIVE and ARCHIVED configs by id: the ones sessions refer to, which never change once used. Drafts
     * are not kept here, because a draft can still be edited.
     */
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

    /**
     * A config by id: the one a feed session was built with, whether or not it is still active, or a
     * draft the console previews (read from the database each time).
     */
    @Transactional(readOnly = true)
    public RankingConfig byId(UUID id) {
        RankingConfig cached = byId.get(id);
        if (cached != null) {
            return cached;
        }
        Row row = repository.find(id).orElseThrow(() -> new IllegalStateException("no ranking config " + id));
        RankingConfig config = parse(row);
        if (!DRAFT.equals(row.status())) {
            byId.put(id, config);
        }
        return config;
    }

    /** Every stored config, newest first, as stored (the console's config list). */
    @Transactional(readOnly = true)
    public List<Row> list() {
        return repository.list();
    }

    /** One stored config, or 404 {@code RANKING_CONFIG_NOT_FOUND}. */
    @Transactional(readOnly = true)
    public Row find(UUID id) {
        return repository.find(id).orElseThrow(() -> notFound(id));
    }

    /**
     * The config the console names: a stored one by id, or one given inline (weights plus
     * params, checked like a draft) as the playground's sliders have it before it is saved.
     *
     * @throws ApiException 404 {@code RANKING_CONFIG_NOT_FOUND}; 400 {@code CONFIG_REF_INVALID} when it is
     *     neither
     */
    @Transactional(readOnly = true)
    public RankingConfig resolve(
            @Nullable UUID id, @Nullable Map<String, Double> weights, RankingConfig.@Nullable Params params) {
        if (id != null) {
            find(id); // 404 rather than byId's IllegalStateException
            return byId(id);
        }
        if (weights == null || params == null) {
            throw ApiException.badRequest("CONFIG_REF_INVALID", "Name a config by id, or give its weights and params");
        }
        return validated(INLINE, weights, params);
    }

    /** The id an inline config is given: it is never stored. */
    public static final UUID INLINE = new UUID(0, 0);

    /** A config checked against the schema and the rules, as it would be parsed from the database. */
    public RankingConfig validated(UUID id, Map<String, Double> weights, RankingConfig.Params params) {
        return RankingConfig.of(id, 0, schema.version(), weights, params, schema);
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
        return createDraft(weights, params, source, null, notes, createdBy);
    }

    /**
     * As above, with the offline metrics a lab-trained draft comes with ({@code feed.ranking_config.metrics}).
     *
     * @return the draft's id
     */
    @Transactional
    public UUID createDraft(
            Map<String, Double> weights,
            RankingConfig.Params params,
            String source,
            @Nullable Map<String, Object> metrics,
            @Nullable String notes,
            @Nullable UUID createdBy) {
        UUID id = Ids.newId();
        validated(id, weights, params); // throws when invalid
        try {
            repository.insertDraft(
                    id,
                    schema.version(),
                    json.writeValueAsString(weights),
                    json.writeValueAsString(params),
                    source,
                    metrics == null ? null : json.writeValueAsString(metrics),
                    notes,
                    createdBy,
                    Instant.now(clock));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        return id;
    }

    /**
     * Replaces a draft's weights, parameters and notes, after the same checks as a new draft.
     * Only a DRAFT can change: an ACTIVE or ARCHIVED config is what sessions were built with.
     *
     * @throws ApiException 404 {@code RANKING_CONFIG_NOT_FOUND}; 409 {@code CONFIG_NOT_DRAFT}
     */
    @Transactional
    public void updateDraft(UUID id, Map<String, Double> weights, RankingConfig.Params params, @Nullable String notes) {
        Row row = repository.findForUpdate(id).orElseThrow(() -> notFound(id));
        if (!DRAFT.equals(row.status())) {
            throw notDraft(row);
        }
        validated(id, weights, params);
        try {
            repository.updateDraft(id, json.writeValueAsString(weights), json.writeValueAsString(params), notes);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Makes a config the active one: validated again, the previous one archived, the change announced to
     * every instance. Feed sessions that already exist keep the config they were built with.
     */
    @Transactional
    public RankingConfig activate(UUID id) {
        return activate(id, null);
    }

    /**
     * As above, recording who did it. A DRAFT goes live; an ARCHIVED config goes live again, which is the
     * console's "Re-activate previous" (a rollback).
     *
     * @throws ApiException 404 {@code RANKING_CONFIG_NOT_FOUND}; 409 {@code CONFIG_ALREADY_ACTIVE}
     */
    @Transactional
    public RankingConfig activate(UUID id, @Nullable UUID activatedBy) {
        Row row = repository.findForUpdate(id).orElseThrow(() -> notFound(id));
        if ("ACTIVE".equals(row.status())) {
            throw ApiException.conflict(
                    "CONFIG_ALREADY_ACTIVE", "Ranking config version " + row.version() + " is already active");
        }
        RankingConfig config = parse(row);
        repository.activate(id, activatedBy, Instant.now(clock));
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

    private static ApiException notFound(UUID id) {
        return ApiException.notFound("RANKING_CONFIG_NOT_FOUND", "No ranking config " + id);
    }

    private static ApiException notDraft(Row row) {
        return ApiException.conflict(
                "CONFIG_NOT_DRAFT",
                "Ranking config version " + row.version() + " is " + row.status() + "; only a DRAFT can be edited");
    }

    /** Parses a stored config; package-private for the console's list, which shows each one's weights. */
    RankingConfig parse(Row row) {
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

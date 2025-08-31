package app.soundcheck.feed.internal;

import app.soundcheck.feed.internal.RankingConfigRepository.Row;
import app.soundcheck.feed.internal.ranking.RankingConfig;
import app.soundcheck.identity.UserSummary;
import app.soundcheck.identity.Users;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The console's view of the ranking configs: every version with who made and activated it, and
 * the edits the playground makes. The rules themselves (validation, only drafts change, activation and
 * its announcement) are {@link RankingConfigs}'.
 */
@Service
@ConditionalOnRole(Roles.API)
public class AdminRankingConfigs {

    /** Where a config came from: the seed migration, the console, or the lab. */
    public enum Source {
        SEED,
        MANUAL,
        LAB
    }

    /**
     * One stored config as the console shows it. {@code weights} and {@code params} are as stored;
     * {@code metrics} are what the lab measured offline (LAB drafts), or null.
     */
    @Schema(name = "RankingConfigView")
    public record ConfigView(
            UUID id,
            int version,
            @Schema(allowableValues = {"DRAFT", "ACTIVE", "ARCHIVED"}) String status,
            @Schema(allowableValues = {"SEED", "MANUAL", "LAB"}) String source,
            int featureSchemaVersion,
            Map<String, Double> weights,
            RankingConfig.Params params,
            @Schema(nullable = true) Map<String, Object> metrics,
            @Schema(nullable = true) String notes,
            @Schema(nullable = true) UUID createdBy,
            @Schema(nullable = true) String createdByHandle,
            Instant createdAt,
            @Schema(nullable = true) Instant activatedAt,
            @Schema(nullable = true) UUID activatedBy,
            @Schema(nullable = true) String activatedByHandle) {}

    private static final TypeReference<Map<String, Double>> WEIGHTS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> METRICS = new TypeReference<>() {};

    private final RankingConfigs configs;
    private final Users users;
    private final JsonMapper json;

    AdminRankingConfigs(RankingConfigs configs, Users users, JsonMapper json) {
        this.configs = configs;
        this.users = users;
        this.json = json;
    }

    /** Every config, newest first. */
    public List<ConfigView> list() {
        List<Row> rows = configs.list();
        Map<UUID, UserSummary> people = people(rows);
        return rows.stream().map(row -> view(row, people)).toList();
    }

    public ConfigView get(UUID id) {
        Row row = configs.find(id);
        return view(row, people(List.of(row)));
    }

    /** Saves a new DRAFT; the console's drafts are MANUAL, the lab's LAB with its offline metrics. */
    public ConfigView create(
            Map<String, Double> weights,
            RankingConfig.Params params,
            Source source,
            @Nullable Map<String, Object> metrics,
            @Nullable String notes,
            UUID actor) {
        if (source == Source.SEED) {
            throw ApiException.badRequest("SOURCE_INVALID", "Only the seed migration writes SEED configs");
        }
        UUID id = configs.createDraft(weights, params, source.name(), metrics, notes, actor);
        return get(id);
    }

    /** Changes a draft; any part left null stays as it is. */
    public ConfigView update(
            UUID id,
            @Nullable Map<String, Double> weights,
            RankingConfig.@Nullable Params params,
            @Nullable String notes) {
        Row row = configs.find(id);
        Map<String, Double> newWeights = weights != null ? weights : weights(row);
        RankingConfig.Params newParams = params != null ? params : params(row);
        configs.updateDraft(id, newWeights, newParams, notes != null ? notes : row.notes());
        return get(id);
    }

    public ConfigView activate(UUID id, UUID actor) {
        configs.activate(id, actor);
        return get(id);
    }

    /** A stored config's params as stored (parameters missing from the document read as 0). */
    RankingConfig.Params params(Row row) {
        try {
            return json.readValue(row.paramsJson(), RankingConfig.Params.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, Double> weights(Row row) {
        try {
            return json.readValue(row.weightsJson(), WEIGHTS);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ConfigView view(Row row, Map<UUID, UserSummary> people) {
        try {
            return new ConfigView(
                    row.id(),
                    row.version(),
                    row.status(),
                    row.source(),
                    row.featureSchemaVersion(),
                    weights(row),
                    params(row),
                    row.metricsJson() == null ? null : json.readValue(row.metricsJson(), METRICS),
                    row.notes(),
                    row.createdBy(),
                    handle(people, row.createdBy()),
                    row.createdAt(),
                    row.activatedAt(),
                    row.activatedBy(),
                    handle(people, row.activatedBy()));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<UUID, UserSummary> people(List<Row> rows) {
        Set<UUID> ids = new HashSet<>();
        rows.stream()
                .flatMap(row -> Stream.of(row.createdBy(), row.activatedBy()))
                .filter(Objects::nonNull)
                .forEach(ids::add);
        return ids.isEmpty() ? Map.of() : users.summaries(ids);
    }

    private static @Nullable String handle(Map<UUID, UserSummary> people, @Nullable UUID id) {
        UserSummary person = id == null ? null : people.get(id);
        return person == null ? null : person.handle();
    }
}

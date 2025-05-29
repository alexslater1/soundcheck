package app.soundcheck.content.internal;

import app.soundcheck.catalog.CatalogViews;
import app.soundcheck.content.PostFacts;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link PostFacts} for the signals module: three queries for any number of posts. */
@Component
@Transactional(readOnly = true)
class PostFactsApi implements PostFacts {

    private final PostRepository posts;
    private final CatalogViews catalog;

    PostFactsApi(PostRepository posts, CatalogViews catalog) {
        this.posts = posts;
        this.catalog = catalog;
    }

    @Override
    public Set<UUID> existing(Collection<UUID> postIds) {
        return posts.findAll(postIds).stream()
                .filter(row -> !row.status().equals("DELETED"))
                .map(PostRow::id)
                .collect(Collectors.toSet());
    }

    @Override
    public Map<UUID, Facts> facts(Collection<UUID> postIds) {
        List<PostRow> rows = posts.findAll(postIds).stream()
                .filter(row -> !row.status().equals("DELETED"))
                .toList();
        Map<UUID, List<UUID>> artists =
                posts.artistIds(rows.stream().map(PostRow::id).toList());
        Map<UUID, Map<Short, Double>> genres = catalog.genreWeights(
                artists.values().stream().flatMap(List::stream).distinct().toList());
        Map<UUID, Facts> byId = new LinkedHashMap<>();
        for (PostRow row : rows) {
            List<UUID> tagged = artists.getOrDefault(row.id(), List.of());
            byId.put(row.id(), new Facts(row.id(), row.authorId(), tagged, meanGenres(tagged, genres)));
        }
        return byId;
    }

    /** The mean of the artists' genre vectors; an artist without genres counts as all zeros. */
    static Map<Short, Double> meanGenres(List<UUID> artistIds, Map<UUID, Map<Short, Double>> genres) {
        Map<Short, Double> mean = new HashMap<>();
        if (artistIds.isEmpty()) {
            return mean;
        }
        for (UUID artistId : artistIds) {
            genres.getOrDefault(artistId, Map.of()).forEach((genre, weight) -> mean.merge(genre, weight, Double::sum));
        }
        mean.replaceAll((genre, sum) -> sum / artistIds.size());
        return mean;
    }
}

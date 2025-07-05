package app.soundcheck.identity.internal;

import app.soundcheck.identity.UserSummary;
import app.soundcheck.identity.Users;
import app.soundcheck.shared.media.PublicImages;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link Users} for other modules: summaries with avatar URLs resolved through the shared image lookup. */
@Service
@Transactional(readOnly = true)
public class UsersApi implements Users {

    private final UserRepository users;
    private final PublicImages images;

    UsersApi(UserRepository users, PublicImages images) {
        this.users = users;
        this.images = images;
    }

    @Override
    public Map<Short, Double> genrePreferences(UUID userId) {
        Map<Short, Double> preferences = new HashMap<>();
        users.genres(userId).forEach(p -> preferences.put(p.genreId(), (double) p.weight()));
        return preferences;
    }

    @Override
    public Map<UUID, UserSummary> summaries(Collection<UUID> userIds) {
        List<UserRepository.SummaryRow> rows = users.summaries(userIds);
        Map<UUID, String> avatars = images.urls(rows.stream()
                .map(UserRepository.SummaryRow::avatarMediaId)
                .filter(Objects::nonNull)
                .toList());
        Map<UUID, UserSummary> byId = new LinkedHashMap<>();
        for (UserRepository.SummaryRow row : rows) {
            String avatar = row.avatarMediaId() == null ? null : avatars.get(row.avatarMediaId());
            byId.put(row.id(), new UserSummary(row.id(), row.handle(), row.displayName(), avatar, null));
        }
        return byId;
    }

    @Override
    public Optional<UserSummary> summary(UUID userId) {
        return Optional.ofNullable(summaries(List.of(userId)).get(userId));
    }

    @Override
    public Optional<UUID> idByHandle(String handle) {
        return users.findByHandle(handle.strip().toLowerCase(Locale.ROOT))
                .filter(u -> !u.deleted())
                .map(UserRecord::id);
    }

    @Override
    public Set<UUID> privateAmong(Collection<UUID> userIds) {
        return users.privateAmong(userIds);
    }

    @Override
    public boolean exists(UUID userId) {
        return users.findById(userId).filter(u -> !u.deleted()).isPresent();
    }
}

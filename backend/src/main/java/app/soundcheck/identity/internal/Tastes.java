package app.soundcheck.identity.internal;

import app.soundcheck.catalog.ViewerTaste;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Identity's implementation of the catalog's {@link ViewerTaste}: the genres the user chose. */
@Component
class Tastes implements ViewerTaste {

    private final UserRepository users;

    Tastes(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Short> genresOf(UUID userId) {
        return users.genres(userId).stream()
                .map(UserRepository.GenrePreference::genreId)
                .collect(Collectors.toUnmodifiableSet());
    }
}

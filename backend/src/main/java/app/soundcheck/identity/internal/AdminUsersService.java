package app.soundcheck.identity.internal;

import app.soundcheck.identity.ProfileFacts;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import app.soundcheck.shared.web.Cursor;
import app.soundcheck.shared.web.Page;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The console's user picker: a read-only list of live accounts, newest first, that tags
 * the synthetic ones (seed and simulator personas, by email domain) so nobody mistakes them for real
 * people. No suspend or ban this phase. API role only, like {@link AdminBootstrap} whose
 * properties it reads: a worker or cli process has no console to serve.
 */
@Service
@ConditionalOnRole(Roles.API)
@Transactional(readOnly = true)
public class AdminUsersService {

    /** One row of the list. {@code posts}: published posts the user wrote. */
    public record AdminUser(
            UUID id,
            String handle,
            String displayName,
            String email,
            String role,
            Instant createdAt,
            boolean synthetic,
            long posts) {}

    private final UserRepository users;
    private final ProfileFacts facts;
    private final List<String> syntheticDomains;

    public AdminUsersService(UserRepository users, ProfileFacts facts, AdminBootstrap.AdminProperties properties) {
        this.users = users;
        this.facts = facts;
        this.syntheticDomains = properties.syntheticEmailDomains();
    }

    /**
     * @param q a handle, part of a name (a leading {@code @} is ignored) or an exact email; blank lists everyone
     * @param synthetic {@code true} only synthetic accounts, {@code false} only real ones, null both
     */
    public Page<AdminUser> page(
            @Nullable String q, @Nullable Boolean synthetic, @Nullable String cursor, @Nullable Integer limit) {
        int size = Cursor.limit(limit);
        List<UserRecord> rows = users.adminPage(text(q), synthetic, syntheticDomains, Cursor.decode(cursor), size + 1);
        boolean more = rows.size() > size;
        List<UserRecord> page = more ? rows.subList(0, size) : rows;
        Map<UUID, Long> posts =
                facts.postCounts(page.stream().map(UserRecord::id).toList());
        List<AdminUser> items = page.stream()
                .map(u -> new AdminUser(
                        u.id(),
                        u.handle(),
                        u.displayName(),
                        u.email(),
                        u.role(),
                        u.createdAt(),
                        isSynthetic(u.email()),
                        posts.getOrDefault(u.id(), 0L)))
                .toList();
        String next =
                more ? new Cursor(page.getLast().createdAt(), page.getLast().id()).encode() : null;
        return new Page<>(items, next);
    }

    boolean isSynthetic(String email) {
        int at = email.lastIndexOf('@');
        return at >= 0 && syntheticDomains.contains(email.substring(at + 1).toLowerCase(Locale.ROOT));
    }

    private static @Nullable String text(@Nullable String q) {
        if (q == null) {
            return null;
        }
        String text = q.strip();
        text = (text.startsWith("@") ? text.substring(1) : text).strip().toLowerCase(Locale.ROOT);
        return text.isEmpty() ? null : text;
    }
}

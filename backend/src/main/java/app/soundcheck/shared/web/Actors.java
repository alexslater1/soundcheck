package app.soundcheck.shared.web;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is calling, read from the access token. One place for the admin check that controllers make inline
 * (an admin may delete anyone's post or comment, and see the feed's score breakdown), and for the one
 * structured log line every console action writes.
 *
 * <p>The admin endpoints themselves don't use {@link #isAdmin}: they are locked twice, by the URL rule in
 * {@code SecurityConfig} and by {@code @PreAuthorize("hasRole('ADMIN')")} on the controller.
 */
public final class Actors {

    /** The value of the token's {@code role} claim for an operator ({@code identity.app_user.role}). */
    public static final String ADMIN_ROLE = "ADMIN";

    /** The expression every admin controller carries at class level (an architecture test checks it). */
    public static final String ADMIN_ONLY = "hasRole('ADMIN')";

    private static final Logger auditLog = LoggerFactory.getLogger("admin.action");

    private Actors() {}

    /** True when the token belongs to an ADMIN account. */
    public static boolean isAdmin(@Nullable Jwt jwt) {
        return jwt != null && ADMIN_ROLE.equals(jwt.getClaimAsString("role"));
    }

    /**
     * Writes the {@code admin.action} log line: who did what to which row. Never pass a token, a password
     * or a presigned URL as the target.
     */
    public static void audit(Jwt actor, String action, Object target) {
        auditLog.atInfo()
                .addKeyValue("actor", actor.getSubject())
                .addKeyValue("action", action)
                .addKeyValue("target", String.valueOf(target))
                .log("admin action {} on {}", action, target);
    }
}

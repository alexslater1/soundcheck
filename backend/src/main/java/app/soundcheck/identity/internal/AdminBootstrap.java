package app.soundcheck.identity.internal;

import app.soundcheck.identity.ProfileVisibility;
import app.soundcheck.shared.Ids;
import app.soundcheck.shared.Redacted;
import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the ADMIN account from {@code SOUNDCHECK_ADMIN_EMAIL/PASSWORD} on API start if it does not
 * exist. Idempotent; never changes an existing user's password (rotate via settings instead).
 */
@Component
@ConditionalOnRole(Roles.API)
@EnableConfigurationProperties(AdminBootstrap.AdminProperties.class)
public class AdminBootstrap {

    @ConfigurationProperties(prefix = "soundcheck.admin")
    public record AdminProperties(String email, String password, List<String> syntheticEmailDomains) {

        /**
         * {@code synthetic-email-domains}: accounts whose email is at one of these domains are synthetic
         * (seed and simulator personas); the console tags them and analytics reports their share.
         */
        public AdminProperties {
            syntheticEmailDomains = syntheticEmailDomains == null
                    ? List.of()
                    : syntheticEmailDomains.stream()
                            .map(d -> d.strip().toLowerCase(Locale.ROOT))
                            .filter(d -> !d.isEmpty())
                            .toList();
        }

        /** Never prints the password. */
        @Override
        public String toString() {
            return "AdminProperties[email=" + email + ", password=" + Redacted.of(password) + "]";
        }
    }

    static final String ADMIN_HANDLE = "admin";

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AdminProperties properties;
    private final Clock clock;

    public AdminBootstrap(
            UserRepository users, PasswordEncoder passwordEncoder, AdminProperties properties, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    /** Transactional here (not on {@code ensureAdmin}): a self-invocation would bypass the proxy. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onReady() {
        ensureAdmin();
    }

    boolean ensureAdmin() {
        if (properties.email() == null || properties.email().isBlank()) {
            log.info("No SOUNDCHECK_ADMIN_EMAIL configured — skipping admin bootstrap");
            return false;
        }
        if (properties.password() == null || properties.password().length() < 10) {
            throw new IllegalStateException(
                    "SOUNDCHECK_ADMIN_PASSWORD must be set (≥ 10 characters) with SOUNDCHECK_ADMIN_EMAIL");
        }
        String email = properties.email().strip().toLowerCase(Locale.ROOT);
        if (users.emailExists(email)) {
            users.findByEmail(email)
                    .filter(u -> !"ADMIN".equals(u.role()))
                    .ifPresent(u ->
                            log.warn("Admin bootstrap: {} exists but has role {} — not promoted", email, u.role()));
            return false;
        }
        Instant now = Instant.now(clock);
        users.insert(new UserRecord(
                Ids.newId(),
                email,
                users.handleExists(ADMIN_HANDLE) ? "admin_" + Long.toHexString(now.toEpochMilli()) : ADMIN_HANDLE,
                "Soundcheck admin",
                null,
                "ADMIN",
                passwordEncoder.encode(properties.password()),
                null,
                now,
                now,
                null,
                ProfileVisibility.PUBLIC));
        log.info("Bootstrapped ADMIN account {}", email);
        return true;
    }
}

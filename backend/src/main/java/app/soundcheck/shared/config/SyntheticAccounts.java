package app.soundcheck.shared.config;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The one definition of a synthetic account: an account whose email is at one of
 * the domains in {@code soundcheck.admin.synthetic-email-domains} (the seed personas'
 * {@code seed.soundcheck.test} and the simulator's {@code sim.soundcheck.test}). The console's user list
 * tags them and analytics reports their share of the activity; both ask this class, so the two can never
 * disagree. In SQL the same test is {@code email_domain = any(:domains)} over {@link #domains()}.
 */
@Component
public class SyntheticAccounts {

    /** The property that lists the domains (comma-separated, or a YAML list). */
    public static final String PROPERTY = "soundcheck.admin.synthetic-email-domains";

    private final List<String> domains;

    @Autowired
    SyntheticAccounts(Environment environment) {
        this(Binder.get(environment)
                .bind(PROPERTY, Bindable.listOf(String.class))
                .orElse(List.of()));
    }

    /** For tests: the given domains, trimmed and lower-cased; blanks are dropped. */
    public SyntheticAccounts(Collection<String> domains) {
        this.domains = domains.stream()
                .map(d -> d.strip().toLowerCase(Locale.ROOT))
                .filter(d -> !d.isEmpty())
                .distinct()
                .toList();
    }

    /** The synthetic domains, lower-case: compare with {@code lower(split_part(email, '@', 2))}. */
    public List<String> domains() {
        return domains;
    }

    /** Whether the address is at a synthetic domain (case-insensitive; the part after the last {@code @}). */
    public boolean isSynthetic(String email) {
        int at = email.lastIndexOf('@');
        return at >= 0 && domains.contains(email.substring(at + 1).strip().toLowerCase(Locale.ROOT));
    }
}

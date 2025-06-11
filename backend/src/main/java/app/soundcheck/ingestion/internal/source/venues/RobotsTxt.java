package app.soundcheck.ingestion.internal.source.venues;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A site's {@code robots.txt} as it applies to us (RFC 9309), read before every venue run.
 * The group naming our product token wins over {@code *}; within a group the
 * longest matching rule decides, and {@code Allow} wins a tie. {@code *} and {@code $} in paths are
 * supported. {@code Crawl-delay} is not in the RFC but many venue sites use it, so it is honoured.
 * A missing or unreadable file allows everything (RFC 9309 §2.3.1.3).
 */
public final class RobotsTxt {

    private record Rule(boolean allow, String path) {}

    private final List<Rule> rules;
    private final @Nullable Duration crawlDelay;

    private RobotsTxt(List<Rule> rules, @Nullable Duration crawlDelay) {
        this.rules = rules;
        this.crawlDelay = crawlDelay;
    }

    public static RobotsTxt allowAll() {
        return new RobotsTxt(List.of(), null);
    }

    /** The rules for {@code productToken} (e.g. "SoundcheckBot"), else for {@code *}. */
    public static RobotsTxt parse(String text, String productToken) {
        String token = productToken.toLowerCase(Locale.ROOT);
        List<Rule> forUs = new ArrayList<>();
        List<Rule> forAll = new ArrayList<>();
        Duration delayUs = null;
        Duration delayAll = null;
        boolean matchedUs = false;
        List<String> agents = new ArrayList<>();
        boolean inRules = false;
        for (String raw : text.split("\\R")) {
            String line = raw.replaceFirst("#.*", "").strip();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (key.equals("user-agent")) {
                if (inRules) { // a new group starts
                    agents.clear();
                    inRules = false;
                }
                agents.add(value.toLowerCase(Locale.ROOT));
                continue;
            }
            if (agents.isEmpty()) {
                continue;
            }
            inRules = true;
            boolean us = agents.stream().anyMatch(a -> !a.equals("*") && token.startsWith(a));
            boolean all = agents.contains("*");
            if (us) {
                matchedUs = true;
            }
            switch (key) {
                case "allow", "disallow" -> {
                    if (key.equals("disallow") && value.isEmpty()) {
                        continue; // "Disallow:" with nothing allows everything
                    }
                    Rule rule = new Rule(key.equals("allow"), value);
                    if (us) {
                        forUs.add(rule);
                    }
                    if (all) {
                        forAll.add(rule);
                    }
                }
                case "crawl-delay" -> {
                    Duration delay = seconds(value);
                    if (us) {
                        delayUs = delay;
                    }
                    if (all) {
                        delayAll = delay;
                    }
                }
                default -> {}
            }
        }
        return matchedUs ? new RobotsTxt(forUs, delayUs) : new RobotsTxt(forAll, delayAll);
    }

    /** Whether a path (with its query string) may be fetched. */
    public boolean allows(String pathAndQuery) {
        Rule best = null;
        for (Rule rule : rules) {
            if (matches(rule.path(), pathAndQuery)
                    && (best == null
                            || rule.path().length() > best.path().length()
                            || (rule.path().length() == best.path().length() && rule.allow()))) {
                best = rule;
            }
        }
        return best == null || best.allow();
    }

    public @Nullable Duration crawlDelay() {
        return crawlDelay;
    }

    /** Robots path patterns: a prefix, where {@code *} matches anything and a final {@code $} anchors. */
    static boolean matches(String pattern, String path) {
        boolean anchored = pattern.endsWith("$");
        String body = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
        StringBuilder regex = new StringBuilder();
        for (String part : body.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(java.util.regex.Pattern.quote(part));
        }
        return path.matches(regex + (anchored ? "" : ".*"));
    }

    private static @Nullable Duration seconds(String value) {
        try {
            return Duration.ofMillis(Math.round(Double.parseDouble(value) * 1000));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

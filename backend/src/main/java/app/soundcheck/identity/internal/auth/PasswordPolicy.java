package app.soundcheck.identity.internal.auth;

import app.soundcheck.shared.web.ApiException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Password rules: 10–128 characters and not in the bundled list of the 10k most common
 * passwords (SecLists, MIT). Hashing itself is Argon2id via {@link PasswordConfig}.
 */
@Component
public class PasswordPolicy {

    static final int MIN_LENGTH = 10;
    static final int MAX_LENGTH = 128;
    static final String COMMON_LIST = "security/common-passwords.txt";

    private final Set<String> common;

    public PasswordPolicy() {
        this.common = loadCommon();
    }

    /** Throws {@code 400 PASSWORD_TOO_SHORT | PASSWORD_TOO_LONG | PASSWORD_TOO_COMMON}. */
    public void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw ApiException.badRequest(
                    "PASSWORD_TOO_SHORT", "Password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.length() > MAX_LENGTH) {
            throw ApiException.badRequest(
                    "PASSWORD_TOO_LONG", "Password must be at most " + MAX_LENGTH + " characters");
        }
        if (common.contains(password.toLowerCase(Locale.ROOT))) {
            throw ApiException.badRequest("PASSWORD_TOO_COMMON", "That password is too common");
        }
    }

    private static Set<String> loadCommon() {
        Set<String> set = new HashSet<>(16_384);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ClassPathResource(COMMON_LIST).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    set.add(line.strip().toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + COMMON_LIST, e);
        }
        return set;
    }
}

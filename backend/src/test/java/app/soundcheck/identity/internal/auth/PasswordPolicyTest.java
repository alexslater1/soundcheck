package app.soundcheck.identity.internal.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.soundcheck.shared.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** Length bounds, the common-password list, and Argon2id output. */
class PasswordPolicyTest {

    final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void rejectsShortLongAndCommonPasswords() {
        assertThatThrownBy(() -> policy.validate("short1234"))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("PASSWORD_TOO_SHORT");
        assertThatThrownBy(() -> policy.validate("x".repeat(129)))
                .extracting("code")
                .isEqualTo("PASSWORD_TOO_LONG");
        assertThatThrownBy(() -> policy.validate("1234567890"))
                .extracting("code")
                .isEqualTo("PASSWORD_TOO_COMMON");
        assertThatThrownBy(() -> policy.validate("Qwertyuiop"))
                .extracting("code")
                .isEqualTo("PASSWORD_TOO_COMMON");
    }

    @Test
    void acceptsAReasonablePassphrase() {
        assertThatCode(() -> policy.validate("correct horse battery")).doesNotThrowAnyException();
    }

    @Test
    void argon2idHashesVerifyAndAreSaltedPhcStrings() {
        var encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        String hash = encoder.encode("correct horse battery");
        org.assertj.core.api.Assertions.assertThat(hash).startsWith("$argon2id$");
        org.assertj.core.api.Assertions.assertThat(encoder.matches("correct horse battery", hash))
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(encoder.matches("wrong", hash))
                .isFalse();
        org.assertj.core.api.Assertions.assertThat(encoder.encode("correct horse battery"))
                .isNotEqualTo(hash);
    }
}

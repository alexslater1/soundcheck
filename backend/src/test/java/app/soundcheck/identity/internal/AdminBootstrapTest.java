package app.soundcheck.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.soundcheck.shared.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin account on API start. On ECS two API tasks can start together: both find no
 * admin, both insert, and the unique constraint turns the second insert into a conflict.
 */
class AdminBootstrapTest {

    final UserRepository users = mock(UserRepository.class);
    final PasswordEncoder encoder = mock(PasswordEncoder.class);
    final AdminBootstrap bootstrap = new AdminBootstrap(
            users,
            encoder,
            new AdminBootstrap.AdminProperties("Admin@Example.test", "a-long-enough-passphrase"),
            Clock.fixed(Instant.parse("2026-10-05T02:00:00Z"), ZoneOffset.UTC));

    @Test
    void createsTheAdminWhenNoneExists() {
        when(encoder.encode(any())).thenReturn("hash");

        assertThat(bootstrap.ensureAdmin()).isTrue();

        verify(users).insert(any(UserRecord.class));
    }

    @Test
    void losingTheRaceToAnotherTaskIsNotAFailure() {
        when(encoder.encode(any())).thenReturn("hash");
        doThrow(ApiException.conflict("EMAIL_TAKEN", "An account with that email already exists"))
                .when(users)
                .insert(any(UserRecord.class));

        assertThat(bootstrap.ensureAdmin()).isFalse();
    }

    @Test
    void anyOtherFailureStillStopsTheStart() {
        when(encoder.encode(any())).thenReturn("hash");
        doThrow(new DataIntegrityViolationException("a check constraint"))
                .when(users)
                .insert(any(UserRecord.class));

        assertThatThrownBy(bootstrap::ensureAdmin).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Inside a transaction the failed insert would abort it, and the commit after the caught conflict
     * would fail, which fails the ApplicationReadyEvent and so the start of the task.
     */
    @Test
    void theReadyListenerRunsOutsideATransaction() throws Exception {
        assertThat(AdminBootstrap.class.getMethod("onReady").getAnnotation(Transactional.class))
                .isNull();
        assertThat(AdminBootstrap.class.getAnnotation(Transactional.class)).isNull();
    }
}

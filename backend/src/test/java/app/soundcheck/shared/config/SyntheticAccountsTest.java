package app.soundcheck.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** One definition of a synthetic account, for the user list and analytics alike. */
class SyntheticAccountsTest {

    @Test
    void anAddressAtAListedDomainIsSyntheticWhateverItsCase() {
        SyntheticAccounts accounts =
                new SyntheticAccounts(List.of(" Seed.Soundcheck.Test ", "sim.soundcheck.test", ""));

        assertThat(accounts.domains()).containsExactly("seed.soundcheck.test", "sim.soundcheck.test");
        assertThat(accounts.isSynthetic("persona-1@seed.soundcheck.test")).isTrue();
        assertThat(accounts.isSynthetic("Persona@SIM.soundcheck.TEST")).isTrue();
        assertThat(accounts.isSynthetic("fan@example.com")).isFalse();
        // The domain must match whole: a look-alike or a sub-domain is a real account.
        assertThat(accounts.isSynthetic("fan@seed.soundcheck.test.example.com")).isFalse();
        assertThat(accounts.isSynthetic("fan@x.seed.soundcheck.test")).isFalse();
        assertThat(accounts.isSynthetic("no-at-sign")).isFalse();
    }

    @Test
    void theDomainsAreReadFromTheAdminPropertyAsACommaSeparatedList() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(SyntheticAccounts.PROPERTY, "seed.soundcheck.test, sim.soundcheck.test");

        assertThat(new SyntheticAccounts(environment).domains())
                .containsExactly("seed.soundcheck.test", "sim.soundcheck.test");
    }

    @Test
    void noPropertyMeansNoSyntheticAccounts() {
        SyntheticAccounts accounts = new SyntheticAccounts(new MockEnvironment());

        assertThat(accounts.domains()).isEmpty();
        assertThat(accounts.isSynthetic("persona@seed.soundcheck.test")).isFalse();
    }
}

package app.soundcheck.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** {@code soundcheck.roles} parsing and the {@link ConditionalOnRole} condition. */
class RolesTest {

    @Test
    void parsesCommaSeparatedRolesIgnoringCaseAndWhitespace() {
        assertThat(Roles.parse(" API, Worker ,,scheduler")).containsExactlyInAnyOrder("api", "worker", "scheduler");
        assertThat(Roles.parse(null)).isEmpty();
        assertThat(Roles.parse("  ")).isEmpty();
    }

    @Test
    void beansAreRegisteredOnlyForTheConfiguredRoles() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(RoleBeans.class);

        runner.withPropertyValues("soundcheck.roles=api,scheduler").run(ctx -> {
            assertThat(ctx).hasBean("apiOnly");
            assertThat(ctx).doesNotHaveBean("workerOnly");
        });
        runner.withPropertyValues("soundcheck.roles=worker").run(ctx -> {
            assertThat(ctx).doesNotHaveBean("apiOnly");
            assertThat(ctx).hasBean("workerOnly");
        });
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean("apiOnly");
            assertThat(ctx).doesNotHaveBean("workerOnly");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class RoleBeans {

        @Bean
        @ConditionalOnRole(Roles.API)
        String apiOnly() {
            return "api";
        }

        @Bean
        @ConditionalOnRole(Roles.WORKER)
        String workerOnly() {
            return "worker";
        }
    }
}

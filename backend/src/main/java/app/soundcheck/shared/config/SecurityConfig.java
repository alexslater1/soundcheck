package app.soundcheck.shared.config;

import app.soundcheck.shared.web.ProblemAuthHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless API security: public paths are listed here; everything else needs a bearer
 * token. JWT validation (resource server) is added once the ES256 key exists.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    static final String[] PUBLIC_PATHS = {
        "/api/v1/health",
        "/actuator/health",
        "/actuator/health/**",
        "/v3/api-docs",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
    };

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ProblemAuthHandlers problemAuthHandlers) throws Exception {
        return http.csrf(csrf ->
                        csrf.disable()) // bearer tokens, no cookies on the API (the web refresh cookie comes later)
                .exceptionHandling(
                        ex -> ex.authenticationEntryPoint(problemAuthHandlers).accessDeniedHandler(problemAuthHandlers))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth.requestMatchers(PUBLIC_PATHS)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .headers(Customizer.withDefaults())
                .build();
    }
}

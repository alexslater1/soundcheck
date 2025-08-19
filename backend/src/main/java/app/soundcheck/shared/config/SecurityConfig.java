package app.soundcheck.shared.config;

import app.soundcheck.shared.web.Actors;
import app.soundcheck.shared.web.ProblemAuthHandlers;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless API security: public paths are listed here; everything else needs a bearer
 * token, validated as an ES256 JWT; {@code @PreAuthorize} guards admin endpoints.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    static final String[] PUBLIC_PATHS = {
        "/api/v1/health",
        "/api/v1/genres",
        "/api/v1/cities",
        "/api/v1/auth/register",
        "/api/v1/auth/login",
        "/api/v1/auth/refresh",
        "/api/v1/auth/logout",
        "/actuator/health",
        "/actuator/health/**",
        "/v3/api-docs",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
    };

    /**
     * Operator-only paths: every console endpoint, and the actuator endpoints other than
     * health ({@code metrics}, {@code modulith}, {@code info}: they describe the system's internals).
     * Each admin controller also carries {@code @PreAuthorize}, so one forgotten annotation
     * or one mistyped matcher doesn't open anything.
     */
    static final String[] ADMIN_PATHS = {"/api/v1/admin/**", "/actuator/**"};

    /**
     * The app's origins are the Expo dev server (:8081) and the local edge (:8088). The
     * edge must be listed even though it proxies to us: with forwarded headers Tomcat reports port 80, so
     * a browser request from :8088 looks cross-origin to Spring's CORS check. On AWS (CloudFront) nothing
     * else is needed — same origin.
     */
    static final List<String> LOCAL_DEV_ORIGINS = List.of("http://localhost:8081", "http://localhost:8088");

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            ProblemAuthHandlers problemAuthHandlers,
            Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter)
            throws Exception {
        // CSRF tokens are unnecessary: APIs use bearer tokens; the only cookie (web refresh) is SameSite=Strict,
        // scoped to /api/v1/auth and its endpoint also requires a custom header.
        return http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .exceptionHandling(
                        ex -> ex.authenticationEntryPoint(problemAuthHandlers).accessDeniedHandler(problemAuthHandlers))
                // ES256 access tokens issued by identity; decoder + role converter are identity beans.
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(problemAuthHandlers)
                        .accessDeniedHandler(problemAuthHandlers))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                // First match wins: the public health paths sit under /actuator, so they come first.
                .authorizeHttpRequests(auth -> auth.requestMatchers(PUBLIC_PATHS)
                        .permitAll()
                        .requestMatchers(ADMIN_PATHS)
                        .hasRole(Actors.ADMIN_ROLE)
                        .anyRequest()
                        .authenticated())
                .headers(Customizer.withDefaults())
                .build();
    }

    /** {@code soundcheck.cors.allowed-origins}: the two local origins by default, empty under the aws profile. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${soundcheck.cors.allowed-origins}") List<String> allowedOrigins) {
        return corsFor(allowedOrigins);
    }

    /**
     * CORS for {@code /api/**}. With no origins (AWS: the app and the API share CloudFront's origin, so no
     * cross-origin call is legitimate) nothing is registered and no browser is let in from elsewhere: the
     * local list must not ride along into production, credentials allowed.
     */
    static CorsConfigurationSource corsFor(List<String> allowedOrigins) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = allowedOrigins.stream().filter(o -> !o.isBlank()).toList();
        if (origins.isEmpty()) {
            return source;
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "X-Client", "X-Requested-With", "Idempotency-Key"));
        config.setAllowCredentials(true); // the web refresh cookie
        config.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /** No form/basic users exist; this stops Boot generating (and logging) a default password. */
    @Bean
    UserDetailsService noUsers() {
        return new InMemoryUserDetailsManager();
    }
}

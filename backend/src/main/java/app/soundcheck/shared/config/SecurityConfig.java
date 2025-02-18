package app.soundcheck.shared.config;

import app.soundcheck.shared.web.ProblemAuthHandlers;
import java.util.List;
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
        "/actuator/health",
        "/actuator/health/**",
        "/v3/api-docs",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
    };

    /** No CORS on AWS (same origin via CloudFront); locally only the Expo dev server origin. */
    static final List<String> LOCAL_DEV_ORIGINS = List.of("http://localhost:8081");

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            ProblemAuthHandlers problemAuthHandlers,
            Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter)
            throws Exception {
        return http.csrf(csrf ->
                        csrf.disable()) // bearer tokens, no cookies on the API (the web refresh cookie comes later)
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
                .authorizeHttpRequests(auth -> auth.requestMatchers(PUBLIC_PATHS)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .headers(Customizer.withDefaults())
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(LOCAL_DEV_ORIGINS);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "X-Client", "X-Requested-With", "Idempotency-Key"));
        config.setAllowCredentials(true); // the web refresh cookie
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /** No form/basic users exist; this stops Boot generating (and logging) a default password. */
    @Bean
    UserDetailsService noUsers() {
        return new InMemoryUserDetailsManager();
    }
}

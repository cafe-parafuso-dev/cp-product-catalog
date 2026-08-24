package com.hubinity.catalog.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Wires Spring Security for the catalog service.
 *
 * <p>The catalog is a stateless JWT-protected REST API. All real endpoints
 * live under {@code /api/**} and require an authenticated request bearing a
 * Keycloak-signed access token. Method-level {@code @PreAuthorize} expressions
 * refine which role is needed per endpoint.
 *
 * <p>Profile-aware Swagger access: Swagger UI and the OpenAPI JSON are
 * permitted only when the {@code local} profile is active (see the
 * {@code localChain} bean). Outside local, they are not registered as
 * {@code permitAll} — any non-local environment requires authentication for
 * them, matching the {@code springdoc.swagger-ui.enabled=false} flag already
 * set in {@code application-staging.yml} / {@code application-prod.yml}.
 *
 * <p>Decision: a single {@link SecurityFilterChain} bean covers non-local
 * profiles; a second, higher-priority bean is added in the {@code local}
 * profile only. Two beans was simpler than juggling {@code WebSecurityCustomizer}
 * ignore patterns across profiles. Spring Security does not order multiple
 * {@code SecurityFilterChain} beans by declaration order when neither is
 * annotated {@code @Order} — both chains below carry explicit {@link Order}
 * values so the any-request chain is always evaluated last, as Spring
 * Security's own multi-chain validation requires (an unordered pair, or the
 * any-request chain published before a narrower one, fails fast at startup
 * with {@code UnreachableFilterChainException}).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Value("${app.security.keycloak.client-id:hb-catalog-service}")
    private String keycloakClientId;

    @Value("${app.cors.allowed-origins:http://localhost:4200,http://localhost:4201,http://localhost:4202,http://localhost:4203}")
    private String corsAllowedOrigins;

    /**
     * Default chain — applies to all profiles. In non-local environments it is
     * the only chain; in local it runs <em>after</em> {@link #localSwaggerChain}
     * (see its lower {@link Order} value) and never matches the Swagger paths
     * because those are fully handled by the local chain.
     */
    @Bean
    @Order(2)
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(CsrfConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(Customizer.withDefaults())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info"
                ).permitAll()
                .requestMatchers("/api/v1/_diagnostics/public").permitAll()
                .requestMatchers(
                    "/actuator/prometheus",
                    "/actuator/metrics/**"
                ).hasRole("admin")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll()
            )
            .oauth2ResourceServer(o -> o
                .jwt(j -> j.jwtAuthenticationConverter(keycloakJwtAuthenticationConverter()))
            );
        return http.build();
    }

    /**
     * Local-profile chain: permits Swagger UI and OpenAPI JSON without
     * authentication so that {@code mvn spring-boot:run -Dspring-boot.run.profiles=local}
     * is friction-free for developers. {@code @Order(1)} gives it priority over
     * {@link #defaultSecurityFilterChain}'s any-request match — Spring Security
     * requires the any-request chain to be evaluated last among multiple chains.
     */
    @Bean
    @Profile("local")
    @Order(1)
    SecurityFilterChain localSwaggerChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher(
                "/swagger-ui/**",
                "/swagger-ui.html",
                "/v3/api-docs",
                "/v3/api-docs/**",
                "/v3/api-docs.yaml"
            )
            .csrf(CsrfConfigurer::disable)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    JwtAuthenticationConverter keycloakJwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter(keycloakClientId));
        // Use the standard "preferred_username" claim as the principal name so
        // controllers see the human-readable username instead of the JWT "sub".
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(corsAllowedOrigins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}

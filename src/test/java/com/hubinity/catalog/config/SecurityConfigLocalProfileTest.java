package com.hubinity.catalog.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hubinity.catalog.api._diagnostics.DiagnosticsController;

/**
 * Regression test for the {@code local} profile's dual {@link SecurityFilterChain}
 * registration: {@link SecurityConfig#defaultSecurityFilterChain} (any-request)
 * plus {@link SecurityConfig#localSwaggerChain} (Swagger-only, {@code @Profile("local")}).
 *
 * <p>With both beans registered, the Spring context must come up cleanly and
 * the Swagger chain must actually take priority for Swagger paths, permitting
 * them without authentication, while {@code /api/**} still requires a JWT.
 * Before the fix, activating {@code local} threw
 * {@link org.springframework.security.web.UnreachableFilterChainException} at
 * context startup because the two {@code SecurityFilterChain} beans have no
 * explicit {@code @Order} and the any-request chain was evaluated first.
 */
@WebMvcTest(DiagnosticsController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "app.security.keycloak.client-id=hb-catalog-service",
    "app.cors.allowed-origins=http://localhost:4200"
})
class SecurityConfigLocalProfileTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * Satisfies the OAuth2 Resource Server autoconfiguration; never actually
     * invoked because authenticated requests below use
     * {@code SecurityMockMvcRequestPostProcessors.jwt()}.
     */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    /**
     * This {@code @WebMvcTest} slice only loads {@link DiagnosticsController},
     * so springdoc's own controllers aren't registered and these paths 404 as
     * unmapped static resources rather than serving real content. The point of
     * this test isn't the 404 itself: it's that the response is 404, not
     * 401/403. {@link SecurityConfig#defaultSecurityFilterChain}'s
     * {@code anyRequest().denyAll()} would produce 403 for a path it
     * intercepts, so a 404 here proves {@link SecurityConfig#localSwaggerChain}
     * matched first and let the (unauthenticated) request reach the
     * dispatcher.
     */
    @Test
    void swaggerUiHtml_noAuth_isNotBlockedBySecurity() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
            .andExpect(status().isNotFound());
    }

    @Test
    void apiDocs_noAuth_isNotBlockedBySecurity() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isNotFound());
    }

    @Test
    void apiEndpoint_noAuth_stillReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/_diagnostics/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void apiEndpoint_withJwt_stillReturns200() throws Exception {
        mockMvc.perform(get("/api/v1/_diagnostics/me").with(jwt()))
            .andExpect(status().isOk());
    }
}

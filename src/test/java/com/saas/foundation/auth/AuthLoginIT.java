package com.saas.foundation.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.user.MembershipRole;
import com.saas.foundation.domain.user.RefreshToken;
import com.saas.foundation.domain.user.RefreshTokenRepository;
import com.saas.foundation.domain.user.TenantMembership;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.security.Hashing;
import com.saas.foundation.support.PostgresRedisContainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class AuthLoginIT {

    private static final String PASSWORD = "correct horse battery staple";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresRedisContainers.postgres();

    @Container
    static final RedisContainer REDIS = PostgresRedisContainers.redis();

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        PostgresRedisContainers.register(registry, POSTGRES, REDIS);
        registry.add("saas.rate-limit.enabled", () -> "false");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    UserAccountRepository userAccountRepository;

    @Autowired
    TenantRepository tenantRepository;

    @Autowired
    TenantMembershipRepository tenantMembershipRepository;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Test
    void loginTokenScopesProductRequestsToTheUsersTenant() throws Exception {
        String email = uniqueEmail();
        UUID tenantId = givenUserInNewTenant(email);

        String token = login(email, PASSWORD);

        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Login Widget","sku":"LOGIN-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()));

        mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sku").value("LOGIN-1"));
    }

    @Test
    void loginIsCaseInsensitiveOnEmail() throws Exception {
        String email = uniqueEmail();
        givenUserInNewTenant(email);

        mockMvc.perform(loginRequest(email.toUpperCase(), PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void wrongPasswordIsUnauthorized() throws Exception {
        String email = uniqueEmail();
        givenUserInNewTenant(email);

        mockMvc.perform(loginRequest(email, "not the password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(AuthService.INVALID_CREDENTIALS));
    }

    @Test
    void refreshIssuesWorkingTokensAndRotatesRefreshToken() throws Exception {
        String email = uniqueEmail();
        UUID tenantId = givenUserInNewTenant(email);
        JsonNode login = loginTokens(email, PASSWORD);

        JsonNode refreshed = refresh(login.get("refreshToken").asText(), status().isOk());

        assertThat(refreshed.get("refreshToken").asText()).isNotEqualTo(login.get("refreshToken").asText());
        assertThat(refreshed.get("tenantId").asText()).isEqualTo(tenantId.toString());
        mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshed.get("accessToken").asText()))
                .andExpect(status().isOk());
    }

    @Test
    void reusingRotatedRefreshTokenRevokesTheSessionDurably() throws Exception {
        String email = uniqueEmail();
        givenUserInNewTenant(email);
        String original = loginTokens(email, PASSWORD).get("refreshToken").asText();
        String rotated = refresh(original, status().isOk()).get("refreshToken").asText();

        refresh(original, status().isUnauthorized());

        // The revocation must have been committed even though the reuse request failed.
        refresh(rotated, status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        String email = uniqueEmail();
        givenUserInNewTenant(email);
        String refreshToken = loginTokens(email, PASSWORD).get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isNoContent());

        refresh(refreshToken, status().isUnauthorized());
    }

    @Test
    void refreshTokenIsStoredOnlyAsHash() throws Exception {
        String email = uniqueEmail();
        givenUserInNewTenant(email);
        String refreshToken = loginTokens(email, PASSWORD).get("refreshToken").asText();

        assertThat(refreshTokenRepository.findAll())
                .extracting(RefreshToken::getTokenHash)
                .contains(Hashing.sha256Hex(refreshToken))
                .doesNotContain(refreshToken);
    }

    @Test
    void invalidRequestBodyIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    private UUID givenUserInNewTenant(String email) {
        Tenant tenant = tenantRepository.save(new Tenant("Tenant for " + email));
        UserAccount user = userAccountRepository.save(new UserAccount(email, passwordEncoder.encode(PASSWORD)));
        tenantMembershipRepository.save(new TenantMembership(tenant.getId(), user.getId(), MembershipRole.OWNER));
        return tenant.getId();
    }

    private String login(String email, String password) throws Exception {
        return loginTokens(email, password).get("accessToken").asText();
    }

    private JsonNode loginTokens(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(loginRequest(email, password))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode refresh(String refreshToken, ResultMatcher expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(expectedStatus)
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return body.isEmpty() ? null : objectMapper.readTree(body);
    }

    private static String refreshBody(String refreshToken) {
        return """
                {"refreshToken":"%s"}
                """.formatted(refreshToken);
    }

    private static org.springframework.test.web.servlet.RequestBuilder loginRequest(String email, String password) {
        return post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, password));
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@acme.test";
    }
}

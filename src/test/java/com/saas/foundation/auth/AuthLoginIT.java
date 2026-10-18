package com.saas.foundation.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import com.saas.foundation.domain.product.ProductRepository;
import com.saas.foundation.domain.tenant.SubscriptionStatus;
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
import com.saas.foundation.tenant.TenantContext;

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
        // Login, refresh, logout, and password changes share one client address in MockMvc.
        registry.add("saas.auth-rate-limit.enabled", () -> "false");
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
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
    ProductRepository productRepository;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void loginTokenScopesProductRequestsToTheUsersTenant() throws Exception {
        String ownerEmail = uniqueEmail();
        String otherEmail = uniqueEmail();
        UUID tenantId = givenUserInNewTenant(ownerEmail);
        givenUserInNewTenant(otherEmail);

        String ownerToken = login(ownerEmail, PASSWORD);
        String otherToken = login(otherEmail, PASSWORD);

        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Login Widget","sku":"LOGIN-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()));

        mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].sku").value("LOGIN-1"));

        mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void loginIsCaseInsensitiveOnEmail() throws Exception {
        String email = uniqueEmail();
        UUID tenantId = givenUserInNewTenant(email);

        mockMvc.perform(loginRequest(email.toUpperCase(), PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
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

        JsonNode refreshed = refresh(text(login, "refreshToken"), status().isOk());

        assertThat(text(refreshed, "refreshToken")).isNotEqualTo(text(login, "refreshToken"));
        assertThat(text(refreshed, "tenantId")).isEqualTo(tenantId.toString());
        mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(text(refreshed, "accessToken"))))
                .andExpect(status().isOk());
    }

    @Test
    void reusingRotatedRefreshTokenRevokesTheSessionDurably() throws Exception {
        String email = uniqueEmail();
        givenUserInNewTenant(email);
        String original = text(loginTokens(email, PASSWORD), "refreshToken");
        String rotated = text(refresh(original, status().isOk()), "refreshToken");

        refresh(original, unauthorizedRefresh());

        // The revocation must have been committed even though the reuse request failed.
        refresh(rotated, unauthorizedRefresh());
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        String email = uniqueEmail();
        givenUserInNewTenant(email);
        String refreshToken = text(loginTokens(email, PASSWORD), "refreshToken");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isNoContent());

        refresh(refreshToken, unauthorizedRefresh());
    }

    @Test
    void refreshTokenIsStoredOnlyAsHash() throws Exception {
        String email = uniqueEmail();
        givenUserInNewTenant(email);
        String refreshToken = text(loginTokens(email, PASSWORD), "refreshToken");
        String hash = Hashing.sha256Hex(refreshToken);

        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash).orElse(null);
        assertThat(stored).isNotNull();
        // CHAR(64) can come back blank-padded; the digest itself is what must be stored.
        assertThat(stored.getTokenHash().trim()).isEqualTo(hash);
        // A row stored under the raw token would mean the secret itself was persisted.
        assertThat(refreshTokenRepository.findByTokenHash(refreshToken)).isEmpty();
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

    @Test
    void changePasswordKeepsThisSessionAndRejectsThePreviousRefreshToken() throws Exception {
        String email = uniqueEmail();
        givenUserInNewTenant(email);
        JsonNode login = loginTokens(email, PASSWORD);

        MvcResult changed = mockMvc.perform(post("/api/v1/auth/password")
                        .header(HttpHeaders.AUTHORIZATION, bearer(text(login, "accessToken")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"a different passphrase"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();
        JsonNode session = objectMapper.readTree(changed.getResponse().getContentAsString());

        refresh(text(login, "refreshToken"), unauthorizedRefresh());
        refresh(text(session, "refreshToken"), status().isOk());
        mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(text(session, "accessToken"))))
                .andExpect(status().isOk());

        mockMvc.perform(loginRequest(email, "a different passphrase")).andExpect(status().isOk());
        mockMvc.perform(loginRequest(email, PASSWORD)).andExpect(status().isUnauthorized());
    }

    @Test
    void deleteAccountRemovesTheOnlyMemberTenantAndItsProducts() throws Exception {
        String email = uniqueEmail();
        UUID tenantId = givenUserInNewTenant(email);
        String sku = "sku-" + UUID.randomUUID();
        JsonNode login = loginTokens(email, PASSWORD);

        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(text(login, "accessToken")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Widget","sku":"%s"}
                                """.formatted(sku)))
                .andExpect(status().isCreated());
        assertThat(productVisible(tenantId, sku)).as("product visible before delete").isTrue();

        mockMvc.perform(delete("/api/v1/auth/account")
                        .header(HttpHeaders.AUTHORIZATION, bearer(text(login, "accessToken")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isNoContent());

        assertThat(userAccountRepository.findByEmail(email)).isEmpty();
        assertThat(tenantRepository.findById(tenantId)).isEmpty();
        assertThat(productVisible(tenantId, sku)).as("product removed with the tenant").isFalse();
        refresh(text(login, "refreshToken"), unauthorizedRefresh());
        mockMvc.perform(loginRequest(email, PASSWORD)).andExpect(status().isUnauthorized());
    }

    @Test
    void deleteAccountRefusesWhenOtherMembersWouldLoseTheirOwner() throws Exception {
        String email = uniqueEmail();
        UUID tenantId = givenUserInNewTenant(email);
        UserAccount guest = userAccountRepository.save(
                new UserAccount(uniqueEmail(), passwordEncoder.encode(PASSWORD)));
        tenantMembershipRepository.save(
                new TenantMembership(tenantId, guest.getId(), MembershipRole.MEMBER));
        String accessToken = login(email, PASSWORD);

        mockMvc.perform(delete("/api/v1/auth/account")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(AuthService.TRANSFER_OWNERSHIP));

        assertThat(userAccountRepository.findByEmail(email)).isPresent();
        assertThat(tenantRepository.findById(tenantId)).isPresent();
        mockMvc.perform(loginRequest(email, PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void deleteAccountRejectsTheWrongPassword() throws Exception {
        String email = uniqueEmail();
        givenUserInNewTenant(email);
        String accessToken = login(email, PASSWORD);

        mockMvc.perform(delete("/api/v1/auth/account")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"not the current password"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(AuthService.CURRENT_PASSWORD_INCORRECT));

        mockMvc.perform(loginRequest(email, PASSWORD)).andExpect(status().isOk());
    }

    private UUID givenUserInNewTenant(String email) {
        Tenant tenant = new Tenant("Tenant for " + email);
        tenant.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        tenantRepository.save(tenant);
        UserAccount user = userAccountRepository.save(new UserAccount(email, passwordEncoder.encode(PASSWORD)));
        tenantMembershipRepository.save(new TenantMembership(tenant.getId(), user.getId(), MembershipRole.OWNER));
        return tenant.getId();
    }

    private String login(String email, String password) throws Exception {
        return text(loginTokens(email, password), "accessToken");
    }

    private JsonNode loginTokens(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(loginRequest(email, password))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode refresh(String refreshToken, ResultMatcher... expected) throws Exception {
        ResultActions actions = mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(refreshToken)));
        for (ResultMatcher matcher : expected) {
            actions = actions.andExpect(matcher);
        }
        MvcResult result = actions.andReturn();
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    private static ResultMatcher unauthorizedRefresh() {
        return result -> {
            status().isUnauthorized().match(result);
            jsonPath("$.message").value(RefreshTokenService.INVALID_REFRESH_TOKEN).match(result);
        };
    }

    /**
     * Same read used before and after account deletion. An empty result only counts as
     * "deleted" when this query could see the row beforehand.
     *
     * <p>{@code findBySku} does not start a transaction, and PostgreSQL only applies
     * {@code app.current_tenant} for the transaction that is already open. The tenant has to be
     * bound before that transaction begins.
     */
    private boolean productVisible(UUID tenantId, String sku) {
        TenantContext.setTenantId(tenantId.toString());
        try {
            TransactionTemplate reads = new TransactionTemplate(transactionManager);
            reads.setReadOnly(true);
            Boolean found = reads.execute(status -> productRepository.findBySku(sku).isPresent());
            return Boolean.TRUE.equals(found);
        } finally {
            TenantContext.clear();
        }
    }

    private static String text(JsonNode node, String field) {
        assertThat(node).as(field).isNotNull();
        JsonNode value = node.get(field);
        assertThat(value).as(field).isNotNull();
        assertThat(value.isNull()).as(field).isFalse();
        assertThat(value.asText()).as(field).isNotBlank();
        return value.asText();
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private static String refreshBody(String refreshToken) {
        return """
                {"refreshToken":"%s"}
                """.formatted(refreshToken);
    }

    private static RequestBuilder loginRequest(String email, String password) {
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

package com.saas.foundation.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.user.MembershipRole;
import com.saas.foundation.domain.user.TenantMembership;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.support.PostgresRedisContainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class MembershipIT {

    private static final String PASSWORD = "correct horse battery staple";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresRedisContainers.postgres();

    @Container
    static final RedisContainer REDIS = PostgresRedisContainers.redis();

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        PostgresRedisContainers.register(registry, POSTGRES, REDIS);
        registry.add("saas.rate-limit.enabled", () -> "false");
        registry.add("saas.auth-rate-limit.enabled", () -> "false");
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

    @Test
    void ownerCanInviteChangeRoleAndRemove() throws Exception {
        Account owner = account("owner");
        Account guest = account("guest");
        String ownerToken = login(owner.email(), null);

        mockMvc.perform(post("/api/v1/members")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","role":"MEMBER"}
                                """.formatted(guest.email())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(guest.userId().toString()))
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.emailAccepted").value(true));

        mockMvc.perform(post("/api/v1/members")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","role":"ADMIN"}
                                """.formatted(guest.email())))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/members")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        JsonNode guestTokens = loginTokens(guest.email(), owner.tenantId());
        String memberToken = guestTokens.get("accessToken").asText();

        mockMvc.perform(get("/api/v1/members")
                        .header(HttpHeaders.AUTHORIZATION, bearer(memberToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(memberToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Nope","sku":"MEM-1"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/members/" + guest.userId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ADMIN"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        JsonNode refreshedTokens = refresh(guestTokens.get("refreshToken").asText());
        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(refreshedTokens.get("accessToken").asText()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Admin widget","sku":"MEM-ADMIN"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/members/" + guest.userId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(refreshedTokens.get("refreshToken").asText())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void soleOwnerCannotLeaveTheTenantWithoutAnOwner() throws Exception {
        Account owner = account("sole");
        String token = login(owner.email(), null);

        mockMvc.perform(patch("/api/v1/members/" + owner.userId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"MEMBER"}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/api/v1/members/" + owner.userId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict());

        assertThat(tenantMembershipRepository.findByTenantIdAndUserId(owner.tenantId(), owner.userId()))
                .get()
                .extracting(TenantMembership::getRole)
                .isEqualTo(MembershipRole.OWNER);
    }

    @Test
    void unknownEmailIsNotFound() throws Exception {
        Account owner = account("lookup");
        String token = login(owner.email(), null);

        mockMvc.perform(post("/api/v1/members")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody-%s@acme.test","role":"MEMBER"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    private Account account(String label) {
        String email = label + "-" + UUID.randomUUID() + "@acme.test";
        Tenant tenant = tenantRepository.save(new Tenant("Tenant " + label));
        UserAccount user = userAccountRepository.save(new UserAccount(email, passwordEncoder.encode(PASSWORD)));
        tenantMembershipRepository.save(new TenantMembership(tenant.getId(), user.getId(), MembershipRole.OWNER));
        return new Account(tenant.getId(), user.getId(), email);
    }

    private String login(String email, UUID tenantId) throws Exception {
        return loginTokens(email, tenantId).get("accessToken").asText();
    }

    private JsonNode loginTokens(String email, UUID tenantId) throws Exception {
        String tenantField = tenantId == null ? "" : ",\"tenantId\":\"" + tenantId + "\"";
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"%s}
                                """.formatted(email, PASSWORD, tenantField)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode refresh(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private record Account(UUID tenantId, UUID userId, String email) {
    }
}

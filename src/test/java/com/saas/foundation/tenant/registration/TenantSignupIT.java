package com.saas.foundation.tenant.registration;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import com.saas.foundation.domain.user.MembershipRole;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.support.PostgresRedisContainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class TenantSignupIT {

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
    UserAccountRepository userAccountRepository;

    @Autowired
    TenantMembershipRepository tenantMembershipRepository;

    @Test
    void signupThenLoginGivesTokenForTheNewTenant() throws Exception {
        String email = uniqueEmail();

        MvcResult signup = mockMvc.perform(signupRequest("Acme Corp", email, PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizationName").value("Acme Corp"))
                .andExpect(jsonPath("$.adminEmail").value(email))
                .andReturn();
        String tenantId = objectMapper.readTree(signup.getResponse().getContentAsString()).get("tenantId").asText();

        var admin = userAccountRepository.findByEmail(email).orElseThrow();
        assertThat(admin.getPasswordHash()).startsWith("{bcrypt}");
        assertThat(tenantMembershipRepository.findByUserId(admin.getId()))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.getTenantId()).hasToString(tenantId);
                    assertThat(m.getRole()).isEqualTo(MembershipRole.OWNER);
                });

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantId))
                .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void duplicateEmailIsConflict() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(signupRequest("First", email, PASSWORD)).andExpect(status().isCreated());

        mockMvc.perform(signupRequest("Second", email.toUpperCase(), PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(TenantRegistrationService.EMAIL_TAKEN));
    }

    @Test
    void shortPasswordIsBadRequest() throws Exception {
        mockMvc.perform(signupRequest("Acme", uniqueEmail(), "short"))
                .andExpect(status().isBadRequest());
    }

    private static RequestBuilder signupRequest(String organizationName, String email, String password) {
        return post("/api/v1/tenants/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationName":"%s","adminEmail":"%s","adminPassword":"%s"}
                        """.formatted(organizationName, email, password));
    }

    private static String uniqueEmail() {
        return "admin-" + UUID.randomUUID() + "@acme.test";
    }
}

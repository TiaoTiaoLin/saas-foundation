package com.saas.foundation.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import com.saas.foundation.security.JwtTokenService;
import com.saas.foundation.support.PostgresRedisContainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class ProductTenantIsolationIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String TENANT_C = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    private static final String TENANT_E = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";

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

    @Test
    void missingTenantHeaderIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("X-Tenant-ID")));
    }

    @Test
    void tenantsCannotSeeEachOthersProducts() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header("X-Tenant-ID", TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Widget A","sku":"W-A-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(TENANT_A));

        mockMvc.perform(post("/api/v1/products")
                        .header("X-Tenant-ID", TENANT_B)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Widget B","sku":"W-B-1"}
                                """))
                .andExpect(status().isCreated());

        MvcResult tenantAList = mockMvc.perform(get("/api/v1/products")
                        .header("X-Tenant-ID", TENANT_A))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode aProducts = objectMapper.readTree(tenantAList.getResponse().getContentAsString());
        assertThat(aProducts).hasSize(1);
        assertThat(aProducts.get(0).get("sku").asText()).isEqualTo("W-A-1");
        assertThat(aProducts.get(0).get("tenantId").asText()).isEqualTo(TENANT_A);

        MvcResult tenantBList = mockMvc.perform(get("/api/v1/products")
                        .header("X-Tenant-ID", TENANT_B))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode bProducts = objectMapper.readTree(tenantBList.getResponse().getContentAsString());
        assertThat(bProducts).hasSize(1);
        assertThat(bProducts.get(0).get("sku").asText()).isEqualTo("W-B-1");
    }

    @Test
    void crossTenantGetReturnsNotFound() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/products")
                        .header("X-Tenant-ID", TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Secret","sku":"SEC-1"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID productId = UUID.fromString(
                objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/api/v1/products/" + productId)
                        .header("X-Tenant-ID", TENANT_B))
                .andExpect(status().isNotFound());
    }

    @Test
    void authenticatedRequestUsesTenantFromToken() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .with(jwt().jwt(token -> token.claim(JwtTokenService.TENANT_ID_CLAIM, TENANT_C)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Token Widget","sku":"TOK-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(TENANT_C));
    }

    @Test
    void headerMatchingTokenTenantIsAccepted() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .with(jwt().jwt(token -> token.claim(JwtTokenService.TENANT_ID_CLAIM, TENANT_C)))
                        .header("X-Tenant-ID", TENANT_C.toUpperCase()))
                .andExpect(status().isOk());
    }

    @Test
    void headerCannotSwitchTenantForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .with(jwt().jwt(token -> token.claim(JwtTokenService.TENANT_ID_CLAIM, TENANT_C)))
                        .header("X-Tenant-ID", TENANT_E))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenWithoutTenantIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .with(jwt())
                        .header("X-Tenant-ID", TENANT_C))
                .andExpect(status().isForbidden());
    }
}

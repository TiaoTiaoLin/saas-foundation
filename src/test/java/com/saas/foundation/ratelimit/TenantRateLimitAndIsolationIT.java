package com.saas.foundation.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import com.saas.foundation.security.JwtTokenService;
import com.saas.foundation.support.PostgresRedisContainers;
import com.saas.foundation.support.TestTokens;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class TenantRateLimitAndIsolationIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresRedisContainers.postgres();

    @Container
    static final RedisContainer REDIS = PostgresRedisContainers.redis();

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        PostgresRedisContainers.register(registry, POSTGRES, REDIS);
        // Tight limit so the IT can exhaust quota quickly
        registry.add("saas.rate-limit.enabled", () -> "true");
        registry.add("saas.rate-limit.capacity", () -> "5");
        registry.add("saas.rate-limit.window", () -> "1m");
        registry.add("saas.rate-limit.key-prefix", () -> "rl:test:");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JwtTokenService tokenService;

    @Test
    void tenantIsolationIsPreservedUnderRateLimiting() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Alpha","sku":"ISO-A"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Beta","sku":"ISO-B"}
                                """))
                .andExpect(status().isCreated());

        MvcResult aList = mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_A)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode aProducts = objectMapper.readTree(aList.getResponse().getContentAsString());
        assertThat(aProducts).hasSize(1);
        assertThat(aProducts.get(0).get("sku").asText()).isEqualTo("ISO-A");

        MvcResult bList = mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_B)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode bProducts = objectMapper.readTree(bList.getResponse().getContentAsString());
        assertThat(bProducts).hasSize(1);
        assertThat(bProducts.get(0).get("sku").asText()).isEqualTo("ISO-B");
    }

    @Test
    void rateLimitIsEnforcedPerTenantIndependently() throws Exception {
        String tenantA = bearer(TENANT_A);
        String tenantB = bearer(TENANT_B);

        // Exhaust tenant A's budget (capacity = 5)
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, tenantA))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, tenantA))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // Tenant B still has its own independent quota
        mockMvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, tenantB))
                .andExpect(status().isOk());
    }

    private String bearer(String tenantId) {
        return TestTokens.bearer(tokenService, tenantId);
    }
}

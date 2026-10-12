package com.saas.foundation.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.tenant.TenantStatus;
import com.saas.foundation.security.JwtConfig;
import com.saas.foundation.security.JwtProperties;
import com.saas.foundation.security.JwtTokenService;
import com.saas.foundation.support.PostgresRedisContainers;
import com.saas.foundation.support.TestTenants;
import com.saas.foundation.support.TestTokens;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class ProductTenantIsolationIT {

    private static final String TENANT_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String TENANT_C = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    private static final String TENANT_E = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
    private static final String TENANT_SUSPENDED = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
    private static final String TENANT_UNKNOWN = "ffffffff-ffff-4fff-8fff-ffffffffffff";

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
    JwtTokenService tokenService;

    @Autowired
    JwtProperties jwtProperties;

    @Autowired
    TenantRepository tenantRepository;

    @BeforeEach
    void seedTenants() {
        TestTenants.ensure(tenantRepository, TENANT_A, TenantStatus.ACTIVE);
        TestTenants.ensure(tenantRepository, TENANT_B, TenantStatus.ACTIVE);
        TestTenants.ensure(tenantRepository, TENANT_C, TenantStatus.ACTIVE);
    }

    @Test
    void requestWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantHeaderAloneIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/products").header("X-Tenant-ID", TENANT_A))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithAnotherSecretIsUnauthorized() throws Exception {
        JwtProperties forgedProperties = new JwtProperties(
                jwtProperties.issuer(), "attacker-controlled-secret-32-bytes-long!", Duration.ofMinutes(15));
        JwtTokenService forger = new JwtTokenService(new JwtConfig().jwtEncoder(forgedProperties), forgedProperties);

        mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(forger, TENANT_A)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantsCannotSeeEachOthersProducts() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Widget A","sku":"W-A-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(TENANT_A));

        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Widget B","sku":"W-B-1"}
                                """))
                .andExpect(status().isCreated());

        MvcResult tenantAList = mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_A)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode aProducts = objectMapper.readTree(tenantAList.getResponse().getContentAsString());
        assertThat(aProducts).hasSize(1);
        assertThat(aProducts.get(0).get("sku").asText()).isEqualTo("W-A-1");
        assertThat(aProducts.get(0).get("tenantId").asText()).isEqualTo(TENANT_A);

        MvcResult tenantBList = mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_B)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode bProducts = objectMapper.readTree(tenantBList.getResponse().getContentAsString());
        assertThat(bProducts).hasSize(1);
        assertThat(bProducts.get(0).get("sku").asText()).isEqualTo("W-B-1");
    }

    @Test
    void crossTenantGetReturnsNotFound() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Secret","sku":"SEC-1"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        UUID productId = UUID.fromString(
                objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/api/v1/products/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_B)))
                .andExpect(status().isNotFound());
    }

    @Test
    void headerMatchingTokenTenantIsAccepted() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_C))
                        .header("X-Tenant-ID", TENANT_C.toUpperCase()))
                .andExpect(status().isOk());
    }

    @Test
    void headerCannotSwitchTenantForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_C))
                        .header("X-Tenant-ID", TENANT_E))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownTenantTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_UNKNOWN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void suspendingTenantRejectsAnExistingAccessToken() throws Exception {
        TestTenants.ensure(tenantRepository, TENANT_SUSPENDED, TenantStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_SUSPENDED)))
                .andExpect(status().isOk());

        Tenant tenant = tenantRepository.findById(UUID.fromString(TENANT_SUSPENDED)).orElseThrow();
        tenant.setStatus(TenantStatus.SUSPENDED);
        tenantRepository.saveAndFlush(tenant);

        mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_SUSPENDED)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void memberCanReadProductsButCannotChangeThem() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Owned","sku":"ROLE-1"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String productId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_A, "MEMBER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_A, "MEMBER")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_A, "MEMBER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Nope","sku":"ROLE-2"}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/products/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_A, "MEMBER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Renamed"}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/products/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_A, "MEMBER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void managersSeeInactiveProductsThatMembersDoNot() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Retired","sku":"INACTIVE-1"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String productId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(put("/api/v1/products/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active":false}
                                """))
                .andExpect(status().isOk());

        assertThat(skus(bearer(TENANT_A))).contains("INACTIVE-1");
        assertThat(skus(bearer(TENANT_A, "ADMIN"))).contains("INACTIVE-1");
        assertThat(skus(bearer(TENANT_A, "MEMBER"))).doesNotContain("INACTIVE-1");

        mockMvc.perform(get("/api/v1/products/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_A, "MEMBER")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/products/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_A, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void searchMatchesNameOrSkuInsideTheCurrentTenant() throws Exception {
        String token = UUID.randomUUID().toString().substring(0, 8);
        createProduct(TENANT_C, "Bolt " + token, "OTHER-" + token);
        createProduct(TENANT_C, "Unrelated", "SKU-" + token);
        createProduct(TENANT_C, "Hidden " + token, "OFF-" + token);
        String hiddenId = productId(TENANT_C, "OFF-" + token);
        mockMvc.perform(put("/api/v1/products/" + hiddenId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_C))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active":false}
                                """))
                .andExpect(status().isOk());
        String otherTenant = "abababab-abab-4aba-8aba-abababababab";
        TestTenants.ensure(tenantRepository, otherTenant, TenantStatus.ACTIVE);
        createProduct(otherTenant, "Bolt " + token, "FOREIGN-" + token);

        mockMvc.perform(get("/api/v1/products/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_C))
                        .param("q", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].sku").value("OTHER-" + token))
                .andExpect(jsonPath("$[1].sku").value("OFF-" + token))
                .andExpect(jsonPath("$[2].sku").value("SKU-" + token));

        mockMvc.perform(get("/api/v1/products/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_C, "MEMBER"))
                        .param("q", " " + token.toUpperCase() + " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sku").value("OTHER-" + token))
                .andExpect(jsonPath("$[1].sku").value("SKU-" + token));

        mockMvc.perform(get("/api/v1/products/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_A))
                        .param("q", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/v1/products/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_C))
                        .param("q", "   "))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/products/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_C)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCanCreateProduct() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TENANT_A, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Admin widget","sku":"ROLE-ADMIN"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void tokenWithoutTenantIsRejected() throws Exception {
        // JwtTokenService always sets a tenant, so a mock JWT stands in for a token without one.
        mockMvc.perform(get("/api/v1/products")
                        .with(jwt())
                        .header("X-Tenant-ID", TENANT_C))
                .andExpect(status().isForbidden());
    }

    private void createProduct(String tenantId, String name, String sku) throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","sku":"%s"}
                                """.formatted(name, sku)))
                .andExpect(status().isCreated());
    }

    private String productId(String tenantId, String sku) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/products/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tenantId))
                        .param("q", sku))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode products = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(products).hasSize(1);
        return products.get(0).get("id").asText();
    }

    private String bearer(String tenantId) {
        return TestTokens.bearer(tokenService, tenantId);
    }

    private String bearer(String tenantId, String role) {
        return TestTokens.bearer(tokenService, tenantId, role);
    }

    private List<String> skus(String authorization) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode products = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> skus = new ArrayList<>();
        products.forEach(product -> skus.add(product.get("sku").asText()));
        return skus;
    }
}

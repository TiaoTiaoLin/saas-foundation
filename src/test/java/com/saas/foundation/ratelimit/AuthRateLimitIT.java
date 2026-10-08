package com.saas.foundation.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.redis.testcontainers.RedisContainer;
import com.saas.foundation.support.PostgresRedisContainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class AuthRateLimitIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresRedisContainers.postgres();

    @Container
    static final RedisContainer REDIS = PostgresRedisContainers.redis();

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        PostgresRedisContainers.register(registry, POSTGRES, REDIS);
        registry.add("saas.auth-rate-limit.enabled", () -> "true");
        registry.add("saas.auth-rate-limit.login-per-email.capacity", () -> "2");
        registry.add("saas.auth-rate-limit.login-per-email.window", () -> "1h");
        registry.add("saas.auth-rate-limit.login-per-ip.capacity", () -> "4");
        registry.add("saas.auth-rate-limit.login-per-ip.window", () -> "1h");
        registry.add("saas.auth-rate-limit.signup-per-ip.capacity", () -> "1");
        registry.add("saas.auth-rate-limit.signup-per-ip.window", () -> "1h");
        registry.add("saas.auth-rate-limit.refresh-per-ip.capacity", () -> "1");
        registry.add("saas.auth-rate-limit.refresh-per-ip.window", () -> "1h");
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void loginIsLimitedPerEmailAndThenPerIp() throws Exception {
        String targeted = uniqueEmail();

        mockMvc.perform(login(targeted, "10.0.0.1")).andExpect(status().isUnauthorized());
        mockMvc.perform(login(targeted, "10.0.0.1")).andExpect(status().isUnauthorized());
        mockMvc.perform(login(targeted, "10.0.0.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // Same email from another address is still blocked: the per-email limit is global.
        mockMvc.perform(login(targeted, "10.0.0.2")).andExpect(status().isTooManyRequests());

        // Fresh emails from the first address hit the per-IP limit (4) on the 5th attempt.
        mockMvc.perform(login(uniqueEmail(), "10.0.0.1")).andExpect(status().isUnauthorized());
        mockMvc.perform(login(uniqueEmail(), "10.0.0.1")).andExpect(status().isTooManyRequests());
    }

    @Test
    void signupIsLimitedPerIp() throws Exception {
        mockMvc.perform(signup(uniqueEmail(), "10.0.1.1")).andExpect(status().isCreated());
        mockMvc.perform(signup(uniqueEmail(), "10.0.1.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        mockMvc.perform(signup(uniqueEmail(), "10.0.1.2")).andExpect(status().isCreated());
    }

    @Test
    void refreshIsLimitedPerIp() throws Exception {
        mockMvc.perform(refresh("10.0.2.1")).andExpect(status().isUnauthorized());
        mockMvc.perform(refresh("10.0.2.1")).andExpect(status().isTooManyRequests());
    }

    private static MockHttpServletRequestBuilder login(String email, String clientIp) {
        return post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"wrong password"}
                        """.formatted(email));
    }

    private static MockHttpServletRequestBuilder signup(String email, String clientIp) {
        return post("/api/v1/tenants/registrations")
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationName":"Acme","adminEmail":"%s","adminPassword":"correct horse battery staple"}
                        """.formatted(email));
    }

    private static MockHttpServletRequestBuilder refresh(String clientIp) {
        return post("/api/v1/auth/refresh")
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"not-a-real-token"}
                        """);
    }

    private static String uniqueEmail() {
        return "rl-" + UUID.randomUUID() + "@acme.test";
    }
}

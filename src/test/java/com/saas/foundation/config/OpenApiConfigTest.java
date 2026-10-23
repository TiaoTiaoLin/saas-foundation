package com.saas.foundation.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;

class OpenApiConfigTest {

    @Test
    void describesTheApiWithABearerTokenScheme() {
        OpenAPI openApi = new OpenApiConfig().saasFoundationOpenApi();

        assertThat(openApi.getInfo().getTitle()).isEqualTo("saas-foundation API");
        SecurityScheme scheme = openApi.getComponents().getSecuritySchemes().get(OpenApiConfig.BEARER_SCHEME);
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(scheme.getScheme()).isEqualTo("bearer");
        assertThat(scheme.getBearerFormat()).isEqualTo("JWT");
        assertThat(openApi.getSecurity()).hasSize(1);
    }
}

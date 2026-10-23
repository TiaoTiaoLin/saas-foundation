package com.saas.foundation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Describes the API at {@code /v3/api-docs} and {@code /swagger-ui.html}.
 * Sign in with {@code POST /api/v1/auth/login}, then paste the access token into Authorize.
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI saasFoundationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("saas-foundation API")
                        .version("v1")
                        .description("Multi-tenant SaaS API. The organization comes from the access token. "
                                + "Products are isolated per organization with PostgreSQL row-level security."))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}

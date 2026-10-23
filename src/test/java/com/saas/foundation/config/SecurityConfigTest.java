package com.saas.foundation.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SecurityConfigTest {

    @Test
    void localAndProdTogetherAreRefused() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local", "prod");

        assertThatThrownBy(() -> SecurityConfig.refuseLocalProfileInProd(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local");
    }

    @Test
    void localAloneIsAllowed() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        assertThatCode(() -> SecurityConfig.refuseLocalProfileInProd(environment)).doesNotThrowAnyException();
    }

    @Test
    void prodAloneIsAllowed() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatCode(() -> SecurityConfig.refuseLocalProfileInProd(environment)).doesNotThrowAnyException();
    }
}

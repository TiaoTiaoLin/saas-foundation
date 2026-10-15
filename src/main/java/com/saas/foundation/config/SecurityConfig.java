package com.saas.foundation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.saas.foundation.security.JwtTokenService;

/**
 * Stateless API security: requests authenticate with a Bearer access token issued by
 * {@link JwtTokenService}. Signup, login, refresh, logout, password reset, and public actuator endpoints are open.
 * Under the {@code local} profile the filter chain still permits requests that have no
 * token. Product methods require a role from the access token on every profile.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment) throws Exception {
        boolean local = environment.acceptsProfiles(Profiles.of("local"));

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                            "/actuator/health",
                            "/actuator/health/**",
                            "/actuator/info",
                            "/actuator/prometheus"
                    ).permitAll();
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    auth.requestMatchers(HttpMethod.POST,
                            "/api/v1/auth/login",
                            "/api/v1/auth/refresh",
                            "/api/v1/auth/logout",
                            "/api/v1/auth/password-resets",
                            "/api/v1/auth/password-resets/confirm",
                            "/api/v1/tenants/registrations"
                    ).permitAll();
                    if (local) {
                        auth.anyRequest().permitAll();
                    } else {
                        auth.anyRequest().authenticated();
                    }
                });

        return http.build();
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtTokenService.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}

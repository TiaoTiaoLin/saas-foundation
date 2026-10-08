package com.saas.foundation.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.tenant.TenantStatus;
import com.saas.foundation.domain.user.MembershipRole;
import com.saas.foundation.domain.user.TenantMembership;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.security.JwtConfig;
import com.saas.foundation.security.JwtProperties;
import com.saas.foundation.security.JwtTokenService;

class AuthServiceTest {

    private static final String EMAIL = "owner@acme.test";
    private static final String PASSWORD = "correct horse battery staple";

    private static final JwtProperties JWT_PROPERTIES = new JwtProperties(
            "auth-service-test", "auth-service-test-secret-at-least-32-bytes", Duration.ofMinutes(15));

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final JwtDecoder jwtDecoder = new JwtConfig().jwtDecoder(JWT_PROPERTIES);

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final TenantMembershipRepository memberships = mock(TenantMembershipRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);

    private AuthService authService;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        JwtTokenService tokenService = new JwtTokenService(new JwtConfig().jwtEncoder(JWT_PROPERTIES), JWT_PROPERTIES);
        authService = new AuthService(users, memberships, tenants, passwordEncoder, tokenService);

        user = withId(new UserAccount(EMAIL, passwordEncoder.encode(PASSWORD)));
        when(users.findByEmail(any())).thenAnswer(inv ->
                EMAIL.equalsIgnoreCase(inv.getArgument(0)) ? Optional.of(user) : Optional.empty());
    }

    @Test
    void singleMembershipLogsIntoThatTenant() {
        UUID tenantId = givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);

        LoginResponse response = authService.login(new LoginRequest(EMAIL, PASSWORD, null));

        assertThat(response.tenantId()).isEqualTo(tenantId);
        assertThat(response.tokenType()).isEqualTo("Bearer");
        Jwt jwt = jwtDecoder.decode(response.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaimAsString(JwtTokenService.TENANT_ID_CLAIM)).isEqualTo(tenantId.toString());
        assertThat(jwt.getClaimAsStringList(JwtTokenService.ROLES_CLAIM)).containsExactly("OWNER");
        assertThat(jwt.getExpiresAt()).isEqualTo(response.expiresAt());
    }

    @Test
    void emailIsTrimmedAndCaseInsensitive() {
        givenMembership(MembershipRole.MEMBER, TenantStatus.ACTIVE);

        LoginResponse response = authService.login(new LoginRequest("  OWNER@Acme.test ", PASSWORD, null));

        assertThat(response.accessToken()).isNotBlank();
    }

    @Test
    void wrongPasswordIsUnauthorized() {
        givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);

        assertStatus(() -> authService.login(new LoginRequest(EMAIL, "wrong", null)), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownEmailIsUnauthorizedWithSameMessage() {
        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@acme.test", PASSWORD, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getReason()).isEqualTo(AuthService.INVALID_CREDENTIALS);
                });
    }

    @Test
    void disabledUserIsUnauthorized() {
        givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        user.setEnabled(false);

        assertStatus(() -> authService.login(new LoginRequest(EMAIL, PASSWORD, null)), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void passwordLongerThan72BytesIsRejectedEvenIfPrefixMatches() {
        String seventyTwo = "a".repeat(72);
        user.setPasswordHash(passwordEncoder.encode(seventyTwo));
        givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);

        assertStatus(() -> authService.login(new LoginRequest(EMAIL, seventyTwo + "b", null)), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void multipleMembershipsRequireTenantId() {
        UUID first = givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        UUID second = givenAdditionalMembership(first, MembershipRole.MEMBER);

        assertStatus(() -> authService.login(new LoginRequest(EMAIL, PASSWORD, null)), HttpStatus.BAD_REQUEST);

        LoginResponse response = authService.login(new LoginRequest(EMAIL, PASSWORD, second));
        assertThat(response.tenantId()).isEqualTo(second);
        assertThat(jwtDecoder.decode(response.accessToken()).getClaimAsStringList(JwtTokenService.ROLES_CLAIM))
                .containsExactly("MEMBER");
    }

    @Test
    void requestedTenantWithoutMembershipIsForbidden() {
        givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);

        assertStatus(() -> authService.login(new LoginRequest(EMAIL, PASSWORD, UUID.randomUUID())),
                HttpStatus.FORBIDDEN);
    }

    @Test
    void userWithoutMembershipsIsForbidden() {
        when(memberships.findByUserId(user.getId())).thenReturn(List.of());

        assertStatus(() -> authService.login(new LoginRequest(EMAIL, PASSWORD, null)), HttpStatus.FORBIDDEN);
    }

    @Test
    void suspendedTenantIsForbidden() {
        givenMembership(MembershipRole.OWNER, TenantStatus.SUSPENDED);

        assertStatus(() -> authService.login(new LoginRequest(EMAIL, PASSWORD, null)), HttpStatus.FORBIDDEN);
    }

    private UUID givenMembership(MembershipRole role, TenantStatus status) {
        Tenant tenant = withId(new Tenant("Acme"));
        tenant.setStatus(status);
        when(tenants.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(memberships.findByUserId(user.getId()))
                .thenReturn(List.of(new TenantMembership(tenant.getId(), user.getId(), role)));
        return tenant.getId();
    }

    private UUID givenAdditionalMembership(UUID existingTenantId, MembershipRole role) {
        Tenant tenant = withId(new Tenant("Globex"));
        when(tenants.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(memberships.findByUserId(user.getId())).thenReturn(List.of(
                new TenantMembership(existingTenantId, user.getId(), MembershipRole.OWNER),
                new TenantMembership(tenant.getId(), user.getId(), role)));
        return tenant.getId();
    }

    private static <T> T withId(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }

    private static void assertStatus(Runnable login, HttpStatusCode expected) {
        assertThatThrownBy(login::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(expected));
    }
}

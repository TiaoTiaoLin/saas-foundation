package com.saas.foundation.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.audit.AuditRecorder;
import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.tenant.TenantStatus;
import com.saas.foundation.domain.user.MembershipRole;
import com.saas.foundation.domain.user.RefreshToken;
import com.saas.foundation.domain.user.RefreshTokenRepository;
import com.saas.foundation.domain.user.TenantMembership;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.security.Hashing;
import com.saas.foundation.security.JwtConfig;
import com.saas.foundation.security.JwtProperties;
import com.saas.foundation.security.JwtTokenService;
import com.saas.foundation.security.RefreshTokenProperties;
import com.saas.foundation.storage.MemoryObjectStorage;

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
    private final EntityManager entityManager = mock(EntityManager.class);
    private final MemoryObjectStorage images = new MemoryObjectStorage();
    private Tenant currentTenant;
    private final Map<String, RefreshToken> refreshTokensByHash = new ConcurrentHashMap<>();
    private final RefreshTokenRepository refreshTokens = inMemoryRefreshTokens(refreshTokensByHash);

    private AuthService authService;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        authService = authServiceWithRefreshTtl(Duration.ofDays(30));

        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn("ok");
        when(query.executeUpdate()).thenReturn(0);

        user = withId(new UserAccount(EMAIL, passwordEncoder.encode(PASSWORD)));
        when(users.findByEmail(any())).thenAnswer(inv ->
                EMAIL.equalsIgnoreCase(inv.getArgument(0)) ? Optional.of(user) : Optional.empty());
        when(users.findById(any())).thenAnswer(inv ->
                user.getId().equals(inv.getArgument(0)) ? Optional.of(user) : Optional.empty());
    }

    // ---- login ----

    @Test
    void singleMembershipLogsIntoThatTenant() {
        UUID tenantId = givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);

        TokenResponse response = authService.login(new LoginRequest(EMAIL, PASSWORD, null));

        assertThat(response.tenantId()).isEqualTo(tenantId);
        assertThat(response.organizationName()).isEqualTo("Acme");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        Jwt jwt = jwtDecoder.decode(response.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaimAsString(JwtTokenService.TENANT_ID_CLAIM)).isEqualTo(tenantId.toString());
        assertThat(jwt.getClaimAsStringList(JwtTokenService.ROLES_CLAIM)).containsExactly("OWNER");
        assertThat(jwt.getExpiresAt()).isEqualTo(response.expiresAt());
    }

    @Test
    void loginIssuesRefreshTokenStoredOnlyAsHash() {
        UUID tenantId = givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);

        TokenResponse response = authService.login(new LoginRequest(EMAIL, PASSWORD, null));

        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.refreshTokenExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(29)));
        assertThat(refreshTokensByHash).doesNotContainKey(response.refreshToken());
        RefreshToken stored = refreshTokensByHash.get(Hashing.sha256Hex(response.refreshToken()));
        assertThat(stored).isNotNull();
        assertThat(stored.getUserId()).isEqualTo(user.getId());
        assertThat(stored.getTenantId()).isEqualTo(tenantId);
        assertThat(stored.isRevoked()).isFalse();
    }

    @Test
    void emailIsTrimmedAndCaseInsensitive() {
        givenMembership(MembershipRole.MEMBER, TenantStatus.ACTIVE);

        TokenResponse response = authService.login(new LoginRequest("  OWNER@Acme.test ", PASSWORD, null));

        assertThat(response.accessToken()).isNotBlank();
    }

    @Test
    void wrongPasswordIsUnauthorized() {
        givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);

        assertStatus(() -> authService.login(new LoginRequest(EMAIL, "wrong", null)), HttpStatus.UNAUTHORIZED);
        assertThat(refreshTokensByHash).isEmpty();
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
    void multipleActiveMembershipsAskForAnOrganization() {
        UUID first = givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        UUID second = givenAdditionalMembership(first, MembershipRole.MEMBER);

        assertStatus(() -> authService.login(new LoginRequest(EMAIL, "wrong", null)), HttpStatus.UNAUTHORIZED);
        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD, null)))
                .isInstanceOfSatisfying(OrganizationChoiceRequiredException.class, ex ->
                        assertThat(ex.getOrganizations())
                                .extracting(LoginOrganization::name, LoginOrganization::tenantId)
                                .containsExactly(
                                        tuple("Acme", first),
                                        tuple("Globex", second)));

        TokenResponse response = authService.login(new LoginRequest(EMAIL, PASSWORD, second));
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

    // ---- refresh ----

    @Test
    void refreshRotatesTheRefreshTokenAndIssuesNewAccessToken() {
        UUID tenantId = givenMembership(MembershipRole.ADMIN, TenantStatus.ACTIVE);
        TokenResponse login = authService.login(new LoginRequest(EMAIL, PASSWORD, null));

        TokenResponse refreshed = authService.refresh(login.refreshToken());

        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(refreshed.tenantId()).isEqualTo(tenantId);
        Jwt jwt = jwtDecoder.decode(refreshed.accessToken());
        assertThat(jwt.getClaimAsString(JwtTokenService.TENANT_ID_CLAIM)).isEqualTo(tenantId.toString());
        assertThat(jwt.getClaimAsStringList(JwtTokenService.ROLES_CLAIM)).containsExactly("ADMIN");

        RefreshToken old = refreshTokensByHash.get(Hashing.sha256Hex(login.refreshToken()));
        RefreshToken next = refreshTokensByHash.get(Hashing.sha256Hex(refreshed.refreshToken()));
        assertThat(old.isRevoked()).isTrue();
        assertThat(old.getReplacedBy()).isEqualTo(next.getId());
        assertThat(next.isRevoked()).isFalse();
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeSession() {
        givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        TokenResponse login = authService.login(new LoginRequest(EMAIL, PASSWORD, null));
        TokenResponse refreshed = authService.refresh(login.refreshToken());

        assertThatThrownBy(() -> authService.refresh(login.refreshToken()))
                .isInstanceOf(RefreshTokenReuseException.class);

        assertStatus(() -> authService.refresh(refreshed.refreshToken()), HttpStatus.UNAUTHORIZED);
        assertThat(refreshTokensByHash.values()).allMatch(RefreshToken::isRevoked);
    }

    @Test
    void loggedOutTokenCannotRefreshAndIsNotTreatedAsReuse() {
        givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        TokenResponse login = authService.login(new LoginRequest(EMAIL, PASSWORD, null));

        authService.logout(login.refreshToken());
        authService.logout(login.refreshToken());

        assertThatThrownBy(() -> authService.refresh(login.refreshToken()))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex).isNotInstanceOf(RefreshTokenReuseException.class);
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });
    }

    @Test
    void unknownRefreshTokenIsUnauthorized() {
        assertStatus(() -> authService.refresh("not-a-real-token"), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredRefreshTokenIsUnauthorized() {
        givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        AuthService expiringImmediately = authServiceWithRefreshTtl(Duration.ZERO);
        TokenResponse login = expiringImmediately.login(new LoginRequest(EMAIL, PASSWORD, null));

        assertStatus(() -> expiringImmediately.refresh(login.refreshToken()), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshFailsAfterMembershipIsRemoved() {
        UUID tenantId = givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        TokenResponse login = authService.login(new LoginRequest(EMAIL, PASSWORD, null));
        when(memberships.findByTenantIdAndUserId(tenantId, user.getId())).thenReturn(Optional.empty());

        assertStatus(() -> authService.refresh(login.refreshToken()), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshFailsForDisabledUser() {
        givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        TokenResponse login = authService.login(new LoginRequest(EMAIL, PASSWORD, null));
        user.setEnabled(false);

        assertStatus(() -> authService.refresh(login.refreshToken()), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshFailsForSuspendedTenant() {
        UUID tenantId = givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        TokenResponse login = authService.login(new LoginRequest(EMAIL, PASSWORD, null));
        tenants.findById(tenantId).orElseThrow().setStatus(TenantStatus.SUSPENDED);

        assertStatus(() -> authService.refresh(login.refreshToken()), HttpStatus.FORBIDDEN);
    }

    @Test
    void changePasswordReplacesTheHashAndRevokesEverySession() {
        UUID tenantA = givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        UUID tenantB = givenAdditionalMembership(tenantA, MembershipRole.MEMBER);
        TokenResponse sessionA = authService.login(new LoginRequest(EMAIL, PASSWORD, tenantA));
        TokenResponse sessionB = authService.login(new LoginRequest(EMAIL, PASSWORD, tenantB));
        String replacement = "a different passphrase";

        TokenResponse changed = authService.changePassword(user.getId(), tenantA,
                new ChangePasswordRequest(PASSWORD, replacement));

        assertThat(passwordEncoder.matches(replacement, user.getPasswordHash())).isTrue();
        assertThat(changed.tenantId()).isEqualTo(tenantA);
        assertStatus(() -> authService.refresh(sessionA.refreshToken()), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> authService.refresh(sessionB.refreshToken()), HttpStatus.UNAUTHORIZED);
        assertThat(authService.refresh(changed.refreshToken()).tenantId()).isEqualTo(tenantA);
        assertThat(authService.login(new LoginRequest(EMAIL, replacement, tenantA)).tenantId()).isEqualTo(tenantA);
        assertStatus(() -> authService.login(new LoginRequest(EMAIL, PASSWORD, tenantA)), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void changePasswordRejectsTheCurrentPasswordWhenItDoesNotMatch() {
        UUID tenantId = givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        String hashBefore = user.getPasswordHash();

        assertStatus(() -> authService.changePassword(user.getId(), tenantId,
                new ChangePasswordRequest("not the current password", "a different passphrase")),
                HttpStatus.BAD_REQUEST);
        assertThat(user.getPasswordHash()).isEqualTo(hashBefore);
    }

    @Test
    void changePasswordRejectsKeepingTheSamePassword() {
        UUID tenantId = givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);

        assertStatus(() -> authService.changePassword(user.getId(), tenantId,
                new ChangePasswordRequest(PASSWORD, PASSWORD)), HttpStatus.BAD_REQUEST);
    }

    @Test
    void deleteAccountRemovesASoleMemberTenantAndRevokesSessions() {
        UUID tenantId = givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        when(memberships.findByTenantIdForUpdate(tenantId)).thenReturn(List.of(
                new TenantMembership(tenantId, user.getId(), MembershipRole.OWNER)));
        String avatarKey = "avatars/" + user.getId() + "/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.jpg";
        String logoKey = "logos/" + tenantId + "/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.jpg";
        user.setAvatarKey(avatarKey);
        currentTenant.setLogoKey(logoKey);
        images.put(avatarKey, new byte[] {1});
        images.put(logoKey, new byte[] {2});
        TokenResponse login = authService.login(new LoginRequest(EMAIL, PASSWORD, null));

        authService.deleteAccount(user.getId(), PASSWORD);

        verify(tenants).deleteById(tenantId);
        verify(users).deleteById(user.getId());
        assertThat(images.contains(avatarKey)).isFalse();
        assertThat(images.contains(logoKey)).isFalse();
        verify(entityManager).createNativeQuery("DELETE FROM products WHERE tenant_id = CAST(:tenantId AS uuid)");
        assertStatus(() -> authService.refresh(login.refreshToken()), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void deleteAccountLeavesASharedTenantWhenAnotherOwnerRemains() {
        UUID tenantId = givenMembership(MembershipRole.MEMBER, TenantStatus.ACTIVE);
        when(memberships.findByTenantIdForUpdate(tenantId)).thenReturn(List.of(
                new TenantMembership(tenantId, user.getId(), MembershipRole.MEMBER),
                new TenantMembership(tenantId, UUID.randomUUID(), MembershipRole.OWNER)));

        authService.deleteAccount(user.getId(), PASSWORD);

        verify(tenants, never()).deleteById(any());
        verify(users).delete(user);
        verify(users, never()).deleteById(any());
    }

    @Test
    void deleteAccountRefusesToOrphanOtherMembers() {
        UUID tenantId = givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        when(memberships.findByTenantIdForUpdate(tenantId)).thenReturn(List.of(
                new TenantMembership(tenantId, user.getId(), MembershipRole.OWNER),
                new TenantMembership(tenantId, UUID.randomUUID(), MembershipRole.MEMBER)));
        TokenResponse login = authService.login(new LoginRequest(EMAIL, PASSWORD, null));

        assertStatus(() -> authService.deleteAccount(user.getId(), PASSWORD), HttpStatus.CONFLICT);

        verify(users, never()).delete(any());
        verify(users, never()).deleteById(any());
        verify(tenants, never()).deleteById(any());
        assertThat(authService.refresh(login.refreshToken()).tenantId()).isEqualTo(tenantId);
    }

    @Test
    void listOrganizationsOmitsSuspendedTenants() {
        UUID first = givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        UUID second = givenAdditionalMembership(first, MembershipRole.MEMBER);
        Tenant suspended = new Tenant("Suspended Co");
        ReflectionTestUtils.setField(suspended, "id", second);
        suspended.setStatus(TenantStatus.SUSPENDED);
        when(tenants.findById(second)).thenReturn(Optional.of(suspended));

        assertThat(authService.listOrganizations(user.getId()))
                .extracting(LoginOrganization::name, LoginOrganization::tenantId)
                .containsExactly(tuple("Acme", first));
    }

    @Test
    void switchOrganizationMovesThisSessionAndLeavesOtherDevicesSignedIn() {
        UUID first = givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        UUID second = givenAdditionalMembership(first, MembershipRole.MEMBER);
        TokenResponse otherDevice = authService.login(new LoginRequest(EMAIL, PASSWORD, first));
        TokenResponse current = authService.login(new LoginRequest(EMAIL, PASSWORD, first));

        TokenResponse switched = authService.switchOrganization(
                user.getId(), first, new SwitchOrganizationRequest(second, current.refreshToken()));

        assertThat(switched.tenantId()).isEqualTo(second);
        assertThat(switched.organizationName()).isEqualTo("Globex");
        assertThat(jwtDecoder.decode(switched.accessToken()).getClaimAsStringList(JwtTokenService.ROLES_CLAIM))
                .containsExactly("MEMBER");
        RefreshToken revoked = refreshTokensByHash.get(Hashing.sha256Hex(current.refreshToken()));
        assertThat(revoked.isRevoked()).isTrue();
        assertThat(revoked.getReplacedBy()).isNull();
        assertThat(refreshTokensByHash.get(Hashing.sha256Hex(otherDevice.refreshToken())).isRevoked()).isFalse();
        assertStatus(() -> authService.refresh(current.refreshToken()), HttpStatus.UNAUTHORIZED);
        assertThat(authService.refresh(otherDevice.refreshToken()).tenantId()).isEqualTo(first);
        assertThat(authService.refresh(switched.refreshToken()).tenantId()).isEqualTo(second);
    }

    @Test
    void switchOrganizationRejectsATenantTheUserDoesNotBelongTo() {
        UUID first = givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        TokenResponse current = authService.login(new LoginRequest(EMAIL, PASSWORD, first));

        assertStatus(() -> authService.switchOrganization(user.getId(), first,
                new SwitchOrganizationRequest(UUID.randomUUID(), current.refreshToken())), HttpStatus.FORBIDDEN);
        assertThat(refreshTokensByHash.get(Hashing.sha256Hex(current.refreshToken())).isRevoked()).isFalse();
    }

    @Test
    void switchOrganizationRejectsARefreshTokenFromAnotherSession() {
        UUID first = givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        UUID second = givenAdditionalMembership(first, MembershipRole.MEMBER);
        TokenResponse onSecond = authService.login(new LoginRequest(EMAIL, PASSWORD, second));

        assertStatus(() -> authService.switchOrganization(user.getId(), first,
                new SwitchOrganizationRequest(second, onSecond.refreshToken())), HttpStatus.UNAUTHORIZED);
        assertThat(refreshTokensByHash.get(Hashing.sha256Hex(onSecond.refreshToken())).isRevoked()).isFalse();
    }

    @Test
    void switchOrganizationRejectsASuspendedTenant() {
        UUID first = givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        UUID second = givenAdditionalMembership(first, MembershipRole.MEMBER);
        Tenant suspended = new Tenant("Globex");
        ReflectionTestUtils.setField(suspended, "id", second);
        suspended.setStatus(TenantStatus.SUSPENDED);
        when(tenants.findById(second)).thenReturn(Optional.of(suspended));
        TokenResponse current = authService.login(new LoginRequest(EMAIL, PASSWORD, first));

        assertStatus(() -> authService.switchOrganization(user.getId(), first,
                new SwitchOrganizationRequest(second, current.refreshToken())), HttpStatus.FORBIDDEN);
        assertThat(refreshTokensByHash.get(Hashing.sha256Hex(current.refreshToken())).isRevoked()).isFalse();
    }

    @Test
    void stayingInTheCurrentOrganizationKeepsTheRefreshToken() {
        UUID tenantId = givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);
        TokenResponse current = authService.login(new LoginRequest(EMAIL, PASSWORD, tenantId));

        TokenResponse same = authService.switchOrganization(
                user.getId(), tenantId, new SwitchOrganizationRequest(tenantId, current.refreshToken()));

        assertThat(same.refreshToken()).isEqualTo(current.refreshToken());
        assertThat(same.tenantId()).isEqualTo(tenantId);
        assertThat(refreshTokensByHash.get(Hashing.sha256Hex(current.refreshToken())).isRevoked()).isFalse();
    }

    @Test
    void deleteAccountRejectsTheWrongPassword() {
        givenMembership(MembershipRole.OWNER, TenantStatus.ACTIVE);

        assertStatus(() -> authService.deleteAccount(user.getId(), "not the current password"),
                HttpStatus.BAD_REQUEST);
        verify(users, never()).delete(any());
        verify(users, never()).deleteById(any());
    }

    // ---- helpers ----

    private AuthService authServiceWithRefreshTtl(Duration refreshTtl) {
        JwtTokenService tokenService = new JwtTokenService(new JwtConfig().jwtEncoder(JWT_PROPERTIES), JWT_PROPERTIES);
        RefreshTokenService refreshTokenService =
                new RefreshTokenService(refreshTokens, new RefreshTokenProperties(refreshTtl));
        return new AuthService(users, memberships, tenants, passwordEncoder, tokenService, refreshTokenService,
                entityManager, images, mock(AuditRecorder.class));
    }

    private UUID givenMembership(MembershipRole role, TenantStatus status) {
        Tenant tenant = withId(new Tenant("Acme"));
        currentTenant = tenant;
        tenant.setStatus(status);
        TenantMembership membership = new TenantMembership(tenant.getId(), user.getId(), role);
        when(tenants.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(memberships.findByUserId(user.getId())).thenReturn(List.of(membership));
        when(memberships.findByTenantIdAndUserId(tenant.getId(), user.getId())).thenReturn(Optional.of(membership));
        return tenant.getId();
    }

    private UUID givenAdditionalMembership(UUID existingTenantId, MembershipRole role) {
        Tenant tenant = withId(new Tenant("Globex"));
        TenantMembership membership = new TenantMembership(tenant.getId(), user.getId(), role);
        when(tenants.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(memberships.findByUserId(user.getId())).thenReturn(List.of(
                new TenantMembership(existingTenantId, user.getId(), MembershipRole.OWNER), membership));
        when(memberships.findByTenantIdAndUserId(tenant.getId(), user.getId())).thenReturn(Optional.of(membership));
        return tenant.getId();
    }

    private static RefreshTokenRepository inMemoryRefreshTokens(Map<String, RefreshToken> byHash) {
        RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
        when(repository.save(any(RefreshToken.class))).thenAnswer(inv -> {
            RefreshToken token = inv.getArgument(0);
            ReflectionTestUtils.invokeMethod(token, "onPersist");
            byHash.put(token.getTokenHash(), token);
            return token;
        });
        when(repository.findByTokenHash(any())).thenAnswer(inv -> Optional.ofNullable(byHash.get(inv.getArgument(0))));
        when(repository.revokeAllActiveForUser(any(), any())).thenAnswer(inv -> {
            UUID userId = inv.getArgument(0);
            Instant now = inv.getArgument(1);
            int revoked = 0;
            for (RefreshToken token : byHash.values()) {
                if (token.getUserId().equals(userId) && !token.isRevoked()) {
                    token.revoke(now);
                    revoked++;
                }
            }
            return revoked;
        });
        when(repository.revokeAllActive(any(), any(), any())).thenAnswer(inv -> {
            UUID userId = inv.getArgument(0);
            UUID tenantId = inv.getArgument(1);
            Instant now = inv.getArgument(2);
            int revoked = 0;
            for (RefreshToken token : byHash.values()) {
                if (token.getUserId().equals(userId) && token.getTenantId().equals(tenantId) && !token.isRevoked()) {
                    token.revoke(now);
                    revoked++;
                }
            }
            return revoked;
        });
        return repository;
    }

    private static <T> T withId(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }

    private static void assertStatus(Runnable action, HttpStatusCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(expected));
    }
}

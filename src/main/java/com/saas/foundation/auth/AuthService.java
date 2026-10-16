package com.saas.foundation.auth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.auth.RefreshTokenService.IssuedRefreshToken;
import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.user.MembershipRole;
import com.saas.foundation.domain.user.RefreshToken;
import com.saas.foundation.domain.user.TenantMembership;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.security.JwtTokenService;
import com.saas.foundation.security.JwtTokenService.IssuedToken;
import com.saas.foundation.security.PasswordConfig;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AuthService {

    static final String INVALID_CREDENTIALS = "Invalid email or password";
    static final String CURRENT_PASSWORD_INCORRECT = "Current password is incorrect";
    static final String PASSWORD_UNCHANGED = "Choose a different password";
    static final String TRANSFER_OWNERSHIP = "Transfer ownership before deleting this account";

    private final UserAccountRepository userAccountRepository;
    private final TenantMembershipRepository tenantMembershipRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final EntityManager entityManager;

    /** Compared against when the email is unknown, so response time does not reveal which emails exist. */
    private final String dummyPasswordHash;

    public AuthService(UserAccountRepository userAccountRepository,
                       TenantMembershipRepository tenantMembershipRepository,
                       TenantRepository tenantRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenService jwtTokenService,
                       RefreshTokenService refreshTokenService,
                       EntityManager entityManager) {
        this.userAccountRepository = userAccountRepository;
        this.tenantMembershipRepository = tenantMembershipRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenService = refreshTokenService;
        this.entityManager = entityManager;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-unknown-users");
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        UserAccount user = authenticate(request.email().trim(), request.password());
        TenantMembership membership = selectMembership(request, user);
        requireActiveTenant(membership.getTenantId());

        IssuedRefreshToken refreshToken = refreshTokenService.issue(user.getId(), membership.getTenantId());
        log.info("Login succeeded userId={} tenantId={}", user.getId(), membership.getTenantId());
        return tokenResponse(user, membership, refreshToken);
    }

    @Transactional(noRollbackFor = RefreshTokenReuseException.class)
    public TokenResponse refresh(String rawRefreshToken) {
        RefreshToken current = refreshTokenService.requireUsable(rawRefreshToken);

        UserAccount user = userAccountRepository.findById(current.getUserId())
                .filter(UserAccount::isEnabled)
                .orElseThrow(RefreshTokenService::invalid);
        TenantMembership membership = tenantMembershipRepository
                .findByTenantIdAndUserId(current.getTenantId(), current.getUserId())
                .orElseThrow(RefreshTokenService::invalid);
        requireActiveTenant(membership.getTenantId());

        IssuedRefreshToken next = refreshTokenService.rotate(current);
        return tokenResponse(user, membership, next);
    }

    /** Active organizations this user can open. Suspended organizations are omitted. */
    @Transactional(readOnly = true)
    public List<LoginOrganization> listOrganizations(UUID userId) {
        userAccountRepository.findById(userId)
                .filter(UserAccount::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again to continue."));
        return tenantMembershipRepository.findByUserId(userId).stream()
                .filter(membership -> isActive(membership.getTenantId()))
                .map(this::toLoginOrganization)
                .sorted(Comparator.comparing(LoginOrganization::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Replaces this browser's session with one for {@code request.tenantId()}.
     * Only the presented refresh token is revoked, so other devices stay signed in.
     */
    @Transactional(noRollbackFor = RefreshTokenReuseException.class)
    public TokenResponse switchOrganization(UUID userId, UUID currentTenantId, SwitchOrganizationRequest request) {
        RefreshToken current = refreshTokenService.requireUsable(request.refreshToken());
        if (!current.getUserId().equals(userId) || !current.getTenantId().equals(currentTenantId)) {
            throw RefreshTokenService.invalid();
        }
        UserAccount user = userAccountRepository.findById(userId)
                .filter(UserAccount::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again to continue."));
        TenantMembership membership = tenantMembershipRepository
                .findByTenantIdAndUserId(request.tenantId(), userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Not a member of the requested tenant"));
        requireActiveTenant(membership.getTenantId());

        if (membership.getTenantId().equals(currentTenantId)) {
            return tokenResponse(user, membership,
                    new IssuedRefreshToken(current.getId(), request.refreshToken(), current.getExpiresAt()));
        }

        current.revoke(Instant.now());
        IssuedRefreshToken refreshToken = refreshTokenService.issue(user.getId(), membership.getTenantId());
        log.info("Organization switched userId={} tenantId={}", user.getId(), membership.getTenantId());
        return tokenResponse(user, membership, refreshToken);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }

    /**
     * Replaces the account password and revokes every refresh token for this user.
     * The returned session is the only one that can still refresh.
     */
    @Transactional
    public TokenResponse changePassword(UUID userId, UUID tenantId, ChangePasswordRequest request) {
        UserAccount user = userAccountRepository.findById(userId)
                .filter(UserAccount::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again to continue."));
        if (PasswordConfig.exceedsMaxPasswordBytes(request.currentPassword())
                || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CURRENT_PASSWORD_INCORRECT);
        }
        if (request.newPassword().length() < 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 12 characters");
        }
        if (PasswordConfig.exceedsMaxPasswordBytes(request.newPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be at most " + PasswordConfig.MAX_PASSWORD_BYTES + " bytes");
        }
        if (request.currentPassword().equals(request.newPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, PASSWORD_UNCHANGED);
        }

        TenantMembership membership = tenantMembershipRepository
                .findByTenantIdAndUserId(tenantId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this tenant"));
        requireActiveTenant(tenantId);

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        refreshTokenService.revokeAllForUser(user.getId());

        IssuedRefreshToken refreshToken = refreshTokenService.issue(user.getId(), membership.getTenantId());
        log.info("Password changed userId={} tenantId={}", user.getId(), membership.getTenantId());
        return tokenResponse(user, membership, refreshToken);
    }

    /**
     * Deletes this login. Memberships in organizations that have another owner are removed.
     * An organization whose only member is this user is deleted with its products.
     * An organization that still has other people, and no other owner, is left unchanged
     * and the delete is refused.
     */
    @Transactional
    public void deleteAccount(UUID userId, String currentPassword) {
        UserAccount user = userAccountRepository.findById(userId)
                .filter(UserAccount::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again to continue."));
        if (PasswordConfig.exceedsMaxPasswordBytes(currentPassword)
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CURRENT_PASSWORD_INCORRECT);
        }

        List<UUID> tenantIds = tenantMembershipRepository.findByUserId(userId).stream()
                .map(TenantMembership::getTenantId)
                .distinct()
                .sorted()
                .toList();
        List<UUID> soleMemberTenants = new ArrayList<>();
        for (UUID tenantId : tenantIds) {
            List<TenantMembership> locked = tenantMembershipRepository.findByTenantIdForUpdate(tenantId);
            TenantMembership mine = locked.stream()
                    .filter(membership -> membership.getUserId().equals(userId))
                    .findFirst()
                    .orElse(null);
            if (mine == null) {
                continue;
            }
            boolean soleOwner = mine.getRole() == MembershipRole.OWNER && ownerCount(locked) == 1;
            if (soleOwner && locked.size() > 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, TRANSFER_OWNERSHIP);
            }
            if (soleOwner) {
                soleMemberTenants.add(tenantId);
            }
        }

        refreshTokenService.revokeAllForUser(userId);
        for (UUID tenantId : soleMemberTenants) {
            deleteSoleMemberTenant(tenantId);
        }
        if (!soleMemberTenants.isEmpty()) {
            entityManager.flush();
            entityManager.clear();
            userAccountRepository.deleteById(userId);
        } else {
            userAccountRepository.delete(user);
        }
        log.info("Account deleted userId={}", userId);
    }

    /**
     * Products are protected by row-level security, so the tenant GUC has to match
     * before those rows can be removed. Memberships and refresh tokens then follow
     * the tenant delete.
     */
    private void deleteSoleMemberTenant(UUID tenantId) {
        String tenant = tenantId.toString();
        entityManager.createNativeQuery("SELECT set_config('app.current_tenant', :tenantId, true)")
                .setParameter("tenantId", tenant)
                .getSingleResult();
        entityManager.createNativeQuery("DELETE FROM products WHERE tenant_id = CAST(:tenantId AS uuid)")
                .setParameter("tenantId", tenant)
                .executeUpdate();
        tenantRepository.deleteById(tenantId);
        entityManager.flush();
    }

    private static long ownerCount(List<TenantMembership> memberships) {
        return memberships.stream().filter(membership -> membership.getRole() == MembershipRole.OWNER).count();
    }

    private TokenResponse tokenResponse(UserAccount user, TenantMembership membership,
                                        IssuedRefreshToken refreshToken) {
        IssuedToken accessToken = jwtTokenService.issueAccessToken(
                user.getId(), membership.getTenantId(), List.of(membership.getRole().name()));
        String organizationName = tenantRepository.findById(membership.getTenantId())
                .map(Tenant::getName)
                .orElse("Organization");
        return new TokenResponse(
                accessToken.value(),
                "Bearer",
                accessToken.expiresAt(),
                refreshToken.value(),
                refreshToken.expiresAt(),
                membership.getTenantId(),
                organizationName);
    }

    private UserAccount authenticate(String email, String password) {
        if (PasswordConfig.exceedsMaxPasswordBytes(password)) {
            throw invalidCredentials();
        }

        Optional<UserAccount> candidate = userAccountRepository.findByEmail(email)
                .filter(UserAccount::isEnabled);
        if (candidate.isEmpty()) {
            passwordEncoder.matches(password, dummyPasswordHash);
            throw invalidCredentials();
        }

        UserAccount user = candidate.get();
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw invalidCredentials();
        }
        return user;
    }

    private TenantMembership selectMembership(LoginRequest request, UserAccount user) {
        List<TenantMembership> memberships = tenantMembershipRepository.findByUserId(user.getId());

        if (request.tenantId() != null) {
            return memberships.stream()
                    .filter(m -> m.getTenantId().equals(request.tenantId()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.FORBIDDEN, "Not a member of the requested tenant"));
        }
        if (memberships.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User does not belong to any tenant");
        }
        List<TenantMembership> active = memberships.stream()
                .filter(membership -> isActive(membership.getTenantId()))
                .toList();
        if (active.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant is not active");
        }
        if (active.size() == 1) {
            return active.get(0);
        }
        throw new OrganizationChoiceRequiredException(active.stream()
                .map(this::toLoginOrganization)
                .sorted(Comparator.comparing(LoginOrganization::name, String.CASE_INSENSITIVE_ORDER))
                .toList());
    }

    private boolean isActive(UUID tenantId) {
        return tenantRepository.findById(tenantId).map(Tenant::isActive).orElse(false);
    }

    private LoginOrganization toLoginOrganization(TenantMembership membership) {
        String name = tenantRepository.findById(membership.getTenantId())
                .map(Tenant::getName)
                .orElse("Organization");
        return new LoginOrganization(membership.getTenantId(), name, membership.getRole());
    }

    private void requireActiveTenant(UUID tenantId) {
        boolean active = tenantRepository.findById(tenantId).map(Tenant::isActive).orElse(false);
        if (!active) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant is not active");
        }
    }

    private static ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
    }
}

package com.saas.foundation.auth;

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
import com.saas.foundation.domain.user.RefreshToken;
import com.saas.foundation.domain.user.TenantMembership;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.security.JwtTokenService;
import com.saas.foundation.security.JwtTokenService.IssuedToken;
import com.saas.foundation.security.PasswordConfig;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AuthService {

    static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final UserAccountRepository userAccountRepository;
    private final TenantMembershipRepository tenantMembershipRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;

    /** Compared against when the email is unknown, so response time does not reveal which emails exist. */
    private final String dummyPasswordHash;

    public AuthService(UserAccountRepository userAccountRepository,
                       TenantMembershipRepository tenantMembershipRepository,
                       TenantRepository tenantRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenService jwtTokenService,
                       RefreshTokenService refreshTokenService) {
        this.userAccountRepository = userAccountRepository;
        this.tenantMembershipRepository = tenantMembershipRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenService = refreshTokenService;
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

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }

    private TokenResponse tokenResponse(UserAccount user, TenantMembership membership,
                                        IssuedRefreshToken refreshToken) {
        IssuedToken accessToken = jwtTokenService.issueAccessToken(
                user.getId(), membership.getTenantId(), List.of(membership.getRole().name()));
        return new TokenResponse(
                accessToken.value(),
                "Bearer",
                accessToken.expiresAt(),
                refreshToken.value(),
                refreshToken.expiresAt(),
                membership.getTenantId());
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
        if (memberships.size() > 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "tenantId is required: user belongs to multiple tenants");
        }
        return memberships.get(0);
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

package com.saas.foundation.auth;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.user.TenantMembership;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.security.JwtTokenService;
import com.saas.foundation.security.JwtTokenService.IssuedToken;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AuthService {

    static final String INVALID_CREDENTIALS = "Invalid email or password";

    /** BCrypt only reads the first 72 bytes; longer passwords are rejected rather than truncated. */
    static final int MAX_PASSWORD_BYTES = 72;

    private final UserAccountRepository userAccountRepository;
    private final TenantMembershipRepository tenantMembershipRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    /** Compared against when the email is unknown, so response time does not reveal which emails exist. */
    private final String dummyPasswordHash;

    public AuthService(UserAccountRepository userAccountRepository,
                       TenantMembershipRepository tenantMembershipRepository,
                       TenantRepository tenantRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenService jwtTokenService) {
        this.userAccountRepository = userAccountRepository;
        this.tenantMembershipRepository = tenantMembershipRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-unknown-users");
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        UserAccount user = authenticate(request.email().trim(), request.password());
        TenantMembership membership = selectMembership(request, user);

        boolean tenantActive = tenantRepository.findById(membership.getTenantId())
                .map(Tenant::isActive)
                .orElse(false);
        if (!tenantActive) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant is not active");
        }

        IssuedToken token = jwtTokenService.issueAccessToken(
                user.getId(), membership.getTenantId(), List.of(membership.getRole().name()));
        log.info("Login succeeded userId={} tenantId={}", user.getId(), membership.getTenantId());
        return new LoginResponse(token.value(), "Bearer", token.expiresAt(), membership.getTenantId());
    }

    private UserAccount authenticate(String email, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
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

    private static ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
    }
}

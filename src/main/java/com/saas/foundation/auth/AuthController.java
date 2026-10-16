package com.saas.foundation.auth;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.saas.foundation.ratelimit.AuthRateLimiter;
import com.saas.foundation.security.JwtTokenService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final AuthRateLimiter authRateLimiter;

    public AuthController(AuthService authService,
                          PasswordResetService passwordResetService,
                          AuthRateLimiter authRateLimiter) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
        this.authRateLimiter = authRateLimiter;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        authRateLimiter.checkLogin(httpRequest.getRemoteAddr(), request.email());
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request, HttpServletRequest httpRequest) {
        authRateLimiter.checkRefresh(httpRequest.getRemoteAddr());
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request, HttpServletRequest httpRequest) {
        authRateLimiter.checkRefresh(httpRequest.getRemoteAddr());
        authService.logout(request.refreshToken());
    }

    @GetMapping("/organizations")
    @PreAuthorize("isAuthenticated()")
    public List<LoginOrganization> organizations() {
        return authService.listOrganizations(currentUserId());
    }

    @PostMapping("/organizations/switch")
    @PreAuthorize("isAuthenticated()")
    public TokenResponse switchOrganization(@Valid @RequestBody SwitchOrganizationRequest request,
                                            HttpServletRequest httpRequest) {
        authRateLimiter.checkRefresh(httpRequest.getRemoteAddr());
        JwtAuthenticationToken jwt = currentJwt();
        String tenantId = jwt.getToken().getClaimAsString(JwtTokenService.TENANT_ID_CLAIM);
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again to continue.");
        }
        return authService.switchOrganization(currentUserId(jwt), UUID.fromString(tenantId), request);
    }

    @PostMapping("/password")
    @PreAuthorize("isAuthenticated()")
    public TokenResponse changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                        HttpServletRequest httpRequest) {
        JwtAuthenticationToken jwt = currentJwt();
        UUID userId = currentUserId(jwt);
        authRateLimiter.checkPasswordChange(httpRequest.getRemoteAddr(), userId);
        String tenantId = jwt.getToken().getClaimAsString(JwtTokenService.TENANT_ID_CLAIM);
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again to continue.");
        }
        return authService.changePassword(userId, UUID.fromString(tenantId), request);
    }

    @DeleteMapping("/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void deleteAccount(@Valid @RequestBody DeleteAccountRequest request, HttpServletRequest httpRequest) {
        UUID userId = currentUserId();
        authRateLimiter.checkPasswordChange(httpRequest.getRemoteAddr(), userId);
        authService.deleteAccount(userId, request.currentPassword());
    }

    @PostMapping("/password-resets")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestPasswordReset(@Valid @RequestBody PasswordResetRequest request,
                                     HttpServletRequest httpRequest) {
        authRateLimiter.checkPasswordReset(httpRequest.getRemoteAddr(), request.email());
        passwordResetService.requestReset(request.email());
    }

    @PostMapping("/password-resets/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmPasswordReset(@Valid @RequestBody ConfirmPasswordResetRequest request,
                                     HttpServletRequest httpRequest) {
        authRateLimiter.checkPasswordResetConfirm(httpRequest.getRemoteAddr());
        passwordResetService.confirmReset(request.token(), request.newPassword());
    }

    private static UUID currentUserId() {
        return currentUserId(currentJwt());
    }

    private static UUID currentUserId(JwtAuthenticationToken jwt) {
        return UUID.fromString(jwt.getToken().getSubject());
    }

    private static JwtAuthenticationToken currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again to continue.");
    }
}

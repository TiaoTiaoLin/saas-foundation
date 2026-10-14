package com.saas.foundation.tenant.registration;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.saas.foundation.ratelimit.AuthRateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantRegistrationController {

    private final TenantRegistrationService tenantRegistrationService;
    private final AuthRateLimiter authRateLimiter;

    public TenantRegistrationController(TenantRegistrationService tenantRegistrationService,
                                        AuthRateLimiter authRateLimiter) {
        this.tenantRegistrationService = tenantRegistrationService;
        this.authRateLimiter = authRateLimiter;
    }

    @PostMapping("/registrations")
    public ResponseEntity<RegisterTenantResponse> register(@Valid @RequestBody RegisterTenantRequest request,
                                                           HttpServletRequest httpRequest) {
        authRateLimiter.checkSignup(httpRequest.getRemoteAddr());
        RegisterTenantResponse response = tenantRegistrationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}

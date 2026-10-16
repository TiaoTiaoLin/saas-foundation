package com.saas.foundation.membership;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/membership-invites")
public class MembershipInviteController {

    private final MembershipService membershipService;

    public MembershipInviteController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @PostMapping("/preview")
    @PreAuthorize("isAuthenticated()")
    public MembershipInvitePreview preview(@Valid @RequestBody MembershipInviteTokenRequest request) {
        return membershipService.preview(currentUserId(), request.token());
    }

    @PostMapping("/accept")
    @PreAuthorize("isAuthenticated()")
    public AcceptMembershipInviteResponse accept(@Valid @RequestBody MembershipInviteTokenRequest request) {
        return membershipService.accept(currentUserId(), request.token());
    }

    private static UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return UUID.fromString(jwt.getToken().getSubject());
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again to continue.");
    }
}

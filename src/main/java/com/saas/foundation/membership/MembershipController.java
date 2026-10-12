package com.saas.foundation.membership;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Validated
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MembershipController {

    private final MembershipService membershipService;

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public List<MemberResponse> list() {
        return membershipService.list(currentUserId());
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<InviteMemberResponse> invite(@Valid @RequestBody InviteMemberRequest request) {
        InviteMemberResponse response = membershipService.invite(currentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{userId}")
    @PreAuthorize("hasRole('OWNER')")
    public MemberResponse changeRole(@PathVariable UUID userId, @Valid @RequestBody UpdateMemberRoleRequest request) {
        return membershipService.changeRole(currentUserId(), userId, request.role());
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    public void remove(@PathVariable UUID userId) {
        membershipService.remove(currentUserId(), userId);
    }

    private static UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return UUID.fromString(jwt.getToken().getSubject());
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again to continue.");
    }
}

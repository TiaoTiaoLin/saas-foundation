package com.saas.foundation.membership;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.user.MembershipRole;
import com.saas.foundation.domain.user.RefreshTokenRepository;
import com.saas.foundation.domain.user.TenantMembership;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.integration.email.EmailSendResult;
import com.saas.foundation.integration.email.ResilientEmailService;
import com.saas.foundation.tenant.TenantContext;

@Service
public class MembershipService {

    static final String OWNER_REQUIRED = "Owner role is required";
    static final String KEEP_OWNER = "Tenant must keep an owner";
    static final String ALREADY_MEMBER = "User is already a member of this tenant";
    static final String UNKNOWN_EMAIL = "No account with this email";
    static final String MEMBER_NOT_FOUND = "Member not found";

    private final UserAccountRepository userAccountRepository;
    private final TenantMembershipRepository tenantMembershipRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ResilientEmailService resilientEmailService;

    public MembershipService(UserAccountRepository userAccountRepository,
                             TenantMembershipRepository tenantMembershipRepository,
                             RefreshTokenRepository refreshTokenRepository,
                             ResilientEmailService resilientEmailService) {
        this.userAccountRepository = userAccountRepository;
        this.tenantMembershipRepository = tenantMembershipRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.resilientEmailService = resilientEmailService;
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> list(UUID callerId) {
        UUID tenantId = currentTenantId();
        TenantMembership caller = requireMembership(tenantId, callerId);
        if (caller.getRole() == MembershipRole.MEMBER) {
            throw forbidden("Owner or admin role is required");
        }

        List<TenantMembership> memberships = tenantMembershipRepository.findByTenantId(tenantId);
        Map<UUID, UserAccount> users = userAccountRepository.findAllById(
                        memberships.stream().map(TenantMembership::getUserId).toList()).stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));

        return memberships.stream()
                .map(membership -> toResponse(membership, users.get(membership.getUserId())))
                .sorted(Comparator.comparing(MemberResponse::email, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional
    public InviteMemberResponse invite(UUID callerId, InviteMemberRequest request) {
        UUID tenantId = currentTenantId();
        requireOwner(tenantId, callerId);

        String email = request.email().trim();
        UserAccount user = userAccountRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, UNKNOWN_EMAIL));
        if (tenantMembershipRepository.findByTenantIdAndUserId(tenantId, user.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ALREADY_MEMBER);
        }

        try {
            tenantMembershipRepository.saveAndFlush(
                    new TenantMembership(tenantId, user.getId(), request.role()));
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ALREADY_MEMBER);
        }

        EmailSendResult emailResult = resilientEmailService.sendMembershipInvite(
                tenantId.toString(), user.getEmail(), request.role().name());
        return new InviteMemberResponse(
                user.getId(),
                user.getEmail(),
                request.role(),
                emailResult.success(),
                emailResult.detail());
    }

    @Transactional
    public MemberResponse changeRole(UUID callerId, UUID userId, MembershipRole role) {
        UUID tenantId = currentTenantId();
        List<TenantMembership> memberships = lockedMemberships(tenantId);
        requireOwner(memberships, callerId);
        TenantMembership target = requireTarget(memberships, userId);
        if (target.getRole() == MembershipRole.OWNER && role != MembershipRole.OWNER && ownerCount(memberships) == 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, KEEP_OWNER);
        }
        target.setRole(role);
        return toResponse(target, requireUser(userId));
    }

    @Transactional
    public void remove(UUID callerId, UUID userId) {
        UUID tenantId = currentTenantId();
        List<TenantMembership> memberships = lockedMemberships(tenantId);
        requireOwner(memberships, callerId);
        TenantMembership target = requireTarget(memberships, userId);
        if (target.getRole() == MembershipRole.OWNER && ownerCount(memberships) == 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, KEEP_OWNER);
        }
        refreshTokenRepository.revokeAllActive(userId, tenantId, Instant.now());
        tenantMembershipRepository.delete(target);
    }

    private List<TenantMembership> lockedMemberships(UUID tenantId) {
        return tenantMembershipRepository.findByTenantIdForUpdate(tenantId);
    }

    private void requireOwner(UUID tenantId, UUID callerId) {
        if (requireMembership(tenantId, callerId).getRole() != MembershipRole.OWNER) {
            throw forbidden(OWNER_REQUIRED);
        }
    }

    private static void requireOwner(List<TenantMembership> memberships, UUID callerId) {
        TenantMembership caller = memberships.stream()
                .filter(membership -> membership.getUserId().equals(callerId))
                .findFirst()
                .orElseThrow(() -> forbidden(OWNER_REQUIRED));
        if (caller.getRole() != MembershipRole.OWNER) {
            throw forbidden(OWNER_REQUIRED);
        }
    }

    private TenantMembership requireMembership(UUID tenantId, UUID userId) {
        return tenantMembershipRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> forbidden("Not a member of this tenant"));
    }

    private static TenantMembership requireTarget(List<TenantMembership> memberships, UUID userId) {
        return memberships.stream()
                .filter(membership -> membership.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND));
    }

    private UserAccount requireUser(UUID userId) {
        return userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND));
    }

    private static MemberResponse toResponse(TenantMembership membership, UserAccount user) {
        return new MemberResponse(membership.getUserId(), user.getEmail(), membership.getRole());
    }

    private static long ownerCount(List<TenantMembership> memberships) {
        return memberships.stream().filter(membership -> membership.getRole() == MembershipRole.OWNER).count();
    }

    private static UUID currentTenantId() {
        return UUID.fromString(TenantContext.requireTenantId());
    }

    private static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}

package com.saas.foundation.membership;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.user.MembershipInvite;
import com.saas.foundation.domain.user.MembershipInviteRepository;
import com.saas.foundation.domain.user.MembershipRole;
import com.saas.foundation.domain.user.RefreshTokenRepository;
import com.saas.foundation.domain.user.TenantMembership;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.integration.email.EmailSendResult;
import com.saas.foundation.integration.email.ResilientEmailService;
import com.saas.foundation.security.Hashing;
import com.saas.foundation.security.MembershipInviteProperties;
import com.saas.foundation.storage.ObjectStorage;
import com.saas.foundation.tenant.TenantContext;

@Service
public class MembershipService {

    public static final String OWNER_REQUIRED = "Owner role is required";
    static final String KEEP_OWNER = "Tenant must keep an owner";
    static final String ALREADY_MEMBER = "User is already a member of this tenant";
    static final String UNKNOWN_EMAIL = "No account with this email";
    static final String MEMBER_NOT_FOUND = "Member not found";
    static final String INVITE_INVALID = "This invitation is invalid or has expired";
    static final String INVITE_WRONG_ACCOUNT = "This invitation was sent to a different account";
    static final String TENANT_INACTIVE = "This organization is not active";

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserAccountRepository userAccountRepository;
    private final TenantMembershipRepository tenantMembershipRepository;
    private final MembershipInviteRepository membershipInviteRepository;
    private final TenantRepository tenantRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ResilientEmailService resilientEmailService;
    private final MembershipInviteProperties inviteProperties;
    private final ObjectStorage objectStorage;

    public MembershipService(UserAccountRepository userAccountRepository,
                             TenantMembershipRepository tenantMembershipRepository,
                             MembershipInviteRepository membershipInviteRepository,
                             TenantRepository tenantRepository,
                             RefreshTokenRepository refreshTokenRepository,
                             ResilientEmailService resilientEmailService,
                             MembershipInviteProperties inviteProperties,
                             ObjectStorage objectStorage) {
        this.userAccountRepository = userAccountRepository;
        this.tenantMembershipRepository = tenantMembershipRepository;
        this.membershipInviteRepository = membershipInviteRepository;
        this.tenantRepository = tenantRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.resilientEmailService = resilientEmailService;
        this.inviteProperties = inviteProperties;
        this.objectStorage = objectStorage;
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
        Map<String, String> avatarUrls = objectStorage.signedUrls(users.values().stream()
                .map(UserAccount::getAvatarKey)
                .filter(key -> key != null && !key.isBlank())
                .toList());

        return memberships.stream()
                .map(membership -> {
                    UserAccount user = users.get(membership.getUserId());
                    String avatarUrl = user.getAvatarKey() == null ? null : avatarUrls.get(user.getAvatarKey());
                    return toResponse(membership, user, avatarUrl);
                })
                .sorted(Comparator.comparing(MemberResponse::email, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional
    public InviteMemberResponse invite(UUID callerId, InviteMemberRequest request) {
        UUID tenantId = currentTenantId();
        requireOwner(tenantId, callerId);

        String email = request.email().trim();
        UserAccount user = userAccountRepository.findByEmail(email)
                .filter(UserAccount::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, UNKNOWN_EMAIL));
        if (tenantMembershipRepository.findByTenantIdAndUserId(tenantId, user.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ALREADY_MEMBER);
        }

        Tenant tenant = requireActiveTenant(tenantId);
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        membershipInviteRepository.deletePending(tenantId, user.getId());
        membershipInviteRepository.save(new MembershipInvite(
                tenantId,
                user.getId(),
                request.role(),
                Hashing.sha256Hex(token),
                Instant.now().plus(inviteProperties.ttl())));

        String link = acceptLink(token);
        EmailSendResult emailResult = resilientEmailService.sendMembershipInvite(
                user.getEmail(), tenant.getName(), displayRole(request.role()), link);
        return new InviteMemberResponse(
                user.getId(),
                user.getEmail(),
                request.role(),
                emailResult.success(),
                emailResult.detail());
    }

    /**
     * Describes an open invitation for the signed-in user. Does not create a membership.
     */
    @Transactional(readOnly = true)
    public MembershipInvitePreview preview(UUID callerId, String token) {
        MembershipInvite invite = requireOpenInvite(token, callerId);
        Tenant tenant = requireActiveTenant(invite.getTenantId());
        return new MembershipInvitePreview(tenant.getName(), invite.getRole());
    }

    /**
     * Creates the membership for the signed-in user. No email is sent.
     */
    @Transactional
    public AcceptMembershipInviteResponse accept(UUID callerId, String token) {
        MembershipInvite invite = requireOpenInvite(token, callerId);
        Tenant tenant = requireActiveTenant(invite.getTenantId());
        if (tenantMembershipRepository.findByTenantIdAndUserId(tenant.getId(), callerId).isEmpty()) {
            try {
                tenantMembershipRepository.saveAndFlush(
                        new TenantMembership(tenant.getId(), callerId, invite.getRole()));
            } catch (DataIntegrityViolationException ex) {
                // A concurrent accept already created the membership.
            }
        }
        invite.markAccepted(Instant.now());
        return new AcceptMembershipInviteResponse(tenant.getId(), tenant.getName(), invite.getRole());
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

    private MemberResponse toResponse(TenantMembership membership, UserAccount user) {
        return toResponse(membership, user, objectStorage.signedUrl(user.getAvatarKey()));
    }

    private static MemberResponse toResponse(TenantMembership membership, UserAccount user, String avatarUrl) {
        return new MemberResponse(
                membership.getUserId(), user.getEmail(), user.getName(), membership.getRole(), avatarUrl);
    }

    private static long ownerCount(List<TenantMembership> memberships) {
        return memberships.stream().filter(membership -> membership.getRole() == MembershipRole.OWNER).count();
    }

    private MembershipInvite requireOpenInvite(String token, UUID callerId) {
        MembershipInvite invite = membershipInviteRepository.findByTokenHash(Hashing.sha256Hex(token.trim()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, INVITE_INVALID));
        if (invite.isAccepted() || invite.isExpiredAt(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVITE_INVALID);
        }
        if (!invite.getUserId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVITE_WRONG_ACCOUNT);
        }
        userAccountRepository.findById(callerId)
                .filter(UserAccount::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, INVITE_INVALID));
        return invite;
    }

    private Tenant requireActiveTenant(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, INVITE_INVALID));
        if (!tenant.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, TENANT_INACTIVE);
        }
        return tenant;
    }

    private String acceptLink(String token) {
        String base = inviteProperties.appBaseUrl().replaceAll("/+$", "");
        return base + "/invites/accept?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private static String displayRole(MembershipRole role) {
        String name = role.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static UUID currentTenantId() {
        return UUID.fromString(TenantContext.requireTenantId());
    }

    private static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}

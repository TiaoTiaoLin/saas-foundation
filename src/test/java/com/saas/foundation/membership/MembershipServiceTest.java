package com.saas.foundation.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
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
import com.saas.foundation.integration.email.EmailProviderClient;
import com.saas.foundation.integration.email.EmailSendResult;
import com.saas.foundation.integration.email.ResilientEmailService;
import com.saas.foundation.security.Hashing;
import com.saas.foundation.security.MembershipInviteProperties;
import com.saas.foundation.tenant.TenantContext;

@ExtendWith(MockitoExtension.class)
class MembershipServiceTest {

    @Mock
    private UserAccountRepository users;

    @Mock
    private TenantMembershipRepository memberships;

    @Mock
    private MembershipInviteRepository invites;

    @Mock
    private TenantRepository tenants;

    @Mock
    private RefreshTokenRepository refreshTokens;

    private final StubEmail email = new StubEmail();
    private final AtomicReference<MembershipInvite> storedInvite = new AtomicReference<>();

    private MembershipService service;
    private UUID tenantId;
    private UserAccount owner;
    private TenantMembership ownerMembership;

    @BeforeEach
    void setUp() {
        service = new MembershipService(
                users,
                memberships,
                invites,
                tenants,
                refreshTokens,
                email,
                new MembershipInviteProperties(Duration.ofDays(7), "http://localhost:5173"));
        tenantId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId.toString());
        owner = user("owner@acme.test");
        ownerMembership = membership(owner.getId(), MembershipRole.OWNER);
    }

    private void stubInviteStore() {
        when(invites.save(any(MembershipInvite.class))).thenAnswer(invocation -> {
            MembershipInvite invite = invocation.getArgument(0);
            ReflectionTestUtils.invokeMethod(invite, "onPersist");
            storedInvite.set(invite);
            return invite;
        });
        lenient().when(invites.findByTokenHash(any())).thenAnswer(invocation -> {
            MembershipInvite invite = storedInvite.get();
            if (invite != null && invite.getTokenHash().equals(invocation.getArgument(0))) {
                return Optional.of(invite);
            }
            return Optional.empty();
        });
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void inviteEmailsALinkAndDoesNotAddTheMember() {
        UserAccount guest = user("guest@acme.test");
        stubInviteStore();
        when(memberships.findByTenantIdAndUserId(tenantId, owner.getId())).thenReturn(Optional.of(ownerMembership));
        when(users.findByEmail("guest@acme.test")).thenReturn(Optional.of(guest));
        when(memberships.findByTenantIdAndUserId(tenantId, guest.getId())).thenReturn(Optional.empty());
        when(tenants.findById(tenantId)).thenReturn(Optional.of(tenant()));

        InviteMemberResponse response = service.invite(owner.getId(),
                new InviteMemberRequest("guest@acme.test", MembershipRole.MEMBER));

        assertThat(response.userId()).isEqualTo(guest.getId());
        assertThat(response.role()).isEqualTo(MembershipRole.MEMBER);
        assertThat(response.emailAccepted()).isTrue();
        assertThat(email.organizationName).isEqualTo("Acme");
        assertThat(email.role).isEqualTo("Member");
        String token = tokenFrom(email.url);
        assertThat(storedInvite.get().getTokenHash()).isEqualTo(Hashing.sha256Hex(token));
        verify(memberships, never()).saveAndFlush(any());
        verify(invites).deletePending(tenantId, guest.getId());
    }

    @Test
    void acceptCreatesTheMembershipForTheInvitedAccount() {
        UserAccount guest = user("guest@acme.test");
        stubInviteStore();
        when(memberships.findByTenantIdAndUserId(tenantId, owner.getId())).thenReturn(Optional.of(ownerMembership));
        when(users.findByEmail("guest@acme.test")).thenReturn(Optional.of(guest));
        when(memberships.findByTenantIdAndUserId(tenantId, guest.getId())).thenReturn(Optional.empty());
        when(users.findById(guest.getId())).thenReturn(Optional.of(guest));
        when(tenants.findById(tenantId)).thenReturn(Optional.of(tenant()));
        service.invite(owner.getId(), new InviteMemberRequest("guest@acme.test", MembershipRole.MEMBER));

        AcceptMembershipInviteResponse accepted = service.accept(guest.getId(), tokenFrom(email.url));

        assertThat(accepted.tenantId()).isEqualTo(tenantId);
        assertThat(accepted.organizationName()).isEqualTo("Acme");
        assertThat(accepted.role()).isEqualTo(MembershipRole.MEMBER);
        assertThat(storedInvite.get().isAccepted()).isTrue();
        verify(memberships).saveAndFlush(any(TenantMembership.class));
        assertThat(email.sends).isEqualTo(1);
    }

    @Test
    void acceptRejectsADifferentAccount() {
        UserAccount guest = user("guest@acme.test");
        UserAccount other = user("other@acme.test");
        stubInviteStore();
        when(memberships.findByTenantIdAndUserId(tenantId, owner.getId())).thenReturn(Optional.of(ownerMembership));
        when(users.findByEmail("guest@acme.test")).thenReturn(Optional.of(guest));
        when(memberships.findByTenantIdAndUserId(tenantId, guest.getId())).thenReturn(Optional.empty());
        when(tenants.findById(tenantId)).thenReturn(Optional.of(tenant()));
        service.invite(owner.getId(), new InviteMemberRequest("guest@acme.test", MembershipRole.ADMIN));

        assertThatThrownBy(() -> service.accept(other.getId(), tokenFrom(email.url)))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(MembershipService.INVITE_WRONG_ACCOUNT));
        verify(memberships, never()).saveAndFlush(any());
        assertThat(storedInvite.get().isAccepted()).isFalse();
    }

    @Test
    void acceptRejectsAnExpiredInvite() {
        UserAccount guest = user("guest@acme.test");
        stubInviteStore();
        when(memberships.findByTenantIdAndUserId(tenantId, owner.getId())).thenReturn(Optional.of(ownerMembership));
        when(users.findByEmail("guest@acme.test")).thenReturn(Optional.of(guest));
        when(memberships.findByTenantIdAndUserId(tenantId, guest.getId())).thenReturn(Optional.empty());
        when(tenants.findById(tenantId)).thenReturn(Optional.of(tenant()));
        service.invite(owner.getId(), new InviteMemberRequest("guest@acme.test", MembershipRole.MEMBER));
        ReflectionTestUtils.setField(storedInvite.get(), "expiresAt", Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> service.accept(guest.getId(), tokenFrom(email.url)))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(MembershipService.INVITE_INVALID));
        verify(memberships, never()).saveAndFlush(any());
    }

    @Test
    void inviteRejectsAnUnknownEmail() {
        when(memberships.findByTenantIdAndUserId(tenantId, owner.getId())).thenReturn(Optional.of(ownerMembership));
        when(users.findByEmail("missing@acme.test")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.invite(owner.getId(),
                new InviteMemberRequest("missing@acme.test", MembershipRole.MEMBER)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(invites, never()).save(any());
        verify(memberships, never()).saveAndFlush(any());
    }

    @Test
    void memberCannotInvite() {
        UserAccount member = user("member@acme.test");
        when(memberships.findByTenantIdAndUserId(tenantId, member.getId()))
                .thenReturn(Optional.of(membership(member.getId(), MembershipRole.MEMBER)));

        assertStatus(() -> service.invite(member.getId(),
                new InviteMemberRequest("guest@acme.test", MembershipRole.ADMIN)), HttpStatus.FORBIDDEN);
    }

    @Test
    void lastOwnerCannotBeDemotedOrRemoved() {
        when(memberships.findByTenantIdForUpdate(tenantId)).thenReturn(List.of(ownerMembership));

        assertStatus(() -> service.changeRole(owner.getId(), owner.getId(), MembershipRole.ADMIN), HttpStatus.CONFLICT);
        assertStatus(() -> service.remove(owner.getId(), owner.getId()), HttpStatus.CONFLICT);
        verify(memberships, never()).delete(any());
    }

    @Test
    void ownerCanRemoveAMember() {
        UserAccount guest = user("guest@acme.test");
        TenantMembership guestMembership = membership(guest.getId(), MembershipRole.MEMBER);
        when(memberships.findByTenantIdForUpdate(tenantId)).thenReturn(List.of(ownerMembership, guestMembership));

        service.remove(owner.getId(), guest.getId());

        verify(refreshTokens).revokeAllActive(eq(guest.getId()), eq(tenantId), any());
        verify(memberships).delete(guestMembership);
    }

    private static void assertStatus(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(status);
    }

    private static UserAccount user(String email) {
        UserAccount user = new UserAccount(email, "hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private TenantMembership membership(UUID userId, MembershipRole role) {
        return new TenantMembership(tenantId, userId, role);
    }

    private Tenant tenant() {
        Tenant tenant = new Tenant("Acme");
        ReflectionTestUtils.setField(tenant, "id", tenantId);
        return tenant;
    }

    private static String tokenFrom(String url) {
        String prefix = "token=";
        return url.substring(url.indexOf(prefix) + prefix.length());
    }

    /** Concrete service; Mockito cannot inline-mock it on this JDK. */
    private static final class StubEmail extends ResilientEmailService {
        private int sends;
        private String organizationName;
        private String role;
        private String url;

        private StubEmail() {
            super((EmailProviderClient) message -> EmailSendResult.ok("msg-1"));
        }

        @Override
        public EmailSendResult sendMembershipInvite(String recipientEmail, String organizationName,
                                                    String role, String acceptUrl) {
            sends++;
            this.organizationName = organizationName;
            this.role = role;
            this.url = acceptUrl;
            return EmailSendResult.ok("msg-1");
        }
    }
}

package com.saas.foundation.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.user.MembershipRole;
import com.saas.foundation.domain.user.RefreshTokenRepository;
import com.saas.foundation.domain.user.TenantMembership;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.integration.email.EmailProviderClient;
import com.saas.foundation.integration.email.EmailSendResult;
import com.saas.foundation.integration.email.ResilientEmailService;
import com.saas.foundation.tenant.TenantContext;

@ExtendWith(MockitoExtension.class)
class MembershipServiceTest {

    @Mock
    private UserAccountRepository users;

    @Mock
    private TenantMembershipRepository memberships;

    @Mock
    private RefreshTokenRepository refreshTokens;

    private final StubEmail email = new StubEmail();

    private MembershipService service;
    private UUID tenantId;
    private UserAccount owner;
    private TenantMembership ownerMembership;

    @BeforeEach
    void setUp() {
        service = new MembershipService(users, memberships, refreshTokens, email);
        tenantId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId.toString());
        owner = user("owner@acme.test");
        ownerMembership = membership(owner.getId(), MembershipRole.OWNER);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void inviteAddsAnExistingAccount() {
        UserAccount guest = user("guest@acme.test");
        when(memberships.findByTenantIdAndUserId(tenantId, owner.getId())).thenReturn(Optional.of(ownerMembership));
        when(users.findByEmail("guest@acme.test")).thenReturn(Optional.of(guest));
        when(memberships.findByTenantIdAndUserId(tenantId, guest.getId())).thenReturn(Optional.empty());

        InviteMemberResponse response = service.invite(owner.getId(),
                new InviteMemberRequest("guest@acme.test", MembershipRole.MEMBER));

        assertThat(response.userId()).isEqualTo(guest.getId());
        assertThat(response.role()).isEqualTo(MembershipRole.MEMBER);
        assertThat(response.emailAccepted()).isTrue();
        verify(memberships).saveAndFlush(any(TenantMembership.class));
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

    /** Concrete service; Mockito cannot inline-mock it on this JDK. */
    private static final class StubEmail extends ResilientEmailService {
        private StubEmail() {
            super((EmailProviderClient) message -> EmailSendResult.ok("msg-1"));
        }

        @Override
        public EmailSendResult sendMembershipInvite(String tenantId, String recipientEmail, String role) {
            return EmailSendResult.ok("msg-1");
        }
    }
}

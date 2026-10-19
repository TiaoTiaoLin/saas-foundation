package com.saas.foundation.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.saas.foundation.domain.audit.AuditAction;
import com.saas.foundation.domain.audit.AuditEvent;
import com.saas.foundation.domain.audit.AuditEventRepository;
import com.saas.foundation.domain.audit.AuditTargetType;
import com.saas.foundation.domain.user.MembershipRole;
import com.saas.foundation.domain.user.TenantMembership;
import com.saas.foundation.domain.user.TenantMembershipRepository;

class AuditRecorderTest {

    private final AuditEventRepository events = mock(AuditEventRepository.class);
    private final TenantMembershipRepository memberships = mock(TenantMembershipRepository.class);
    private final AuditRecorder recorder = new AuditRecorder(events, memberships);

    @Test
    void anAccountActionIsRecordedOnceForEachOrganization() {
        UUID userId = UUID.randomUUID();
        UUID firstTenant = UUID.randomUUID();
        UUID secondTenant = UUID.randomUUID();
        when(memberships.findByUserId(userId)).thenReturn(List.of(
                new TenantMembership(firstTenant, userId, MembershipRole.OWNER),
                new TenantMembership(secondTenant, userId, MembershipRole.MEMBER)));

        recorder.recordForEachMembership(
                AuditAction.AUTH_PASSWORD_CHANGED,
                userId,
                AuditTargetType.USER,
                userId,
                Map.of("email", "owner@acme.test", "unused", " "));

        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
        verify(events, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(AuditEvent::getTenantId)
                .containsExactly(firstTenant, secondTenant);
        assertThat(saved.getAllValues())
                .allSatisfy(event -> {
                    assertThat(event.getActorUserId()).isEqualTo(userId);
                    assertThat(event.getMetadata()).containsExactly(Map.entry("email", "owner@acme.test"));
                });
    }

    @Test
    void anAccountWithNoOrganizationLeavesTheTenantEmpty() {
        UUID userId = UUID.randomUUID();
        when(memberships.findByUserId(userId)).thenReturn(List.of());

        recorder.recordForEachMembership(
                AuditAction.AUTH_PASSWORD_RESET_REQUESTED,
                userId,
                AuditTargetType.USER,
                userId,
                Map.of());

        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
        verify(events).save(saved.capture());
        assertThat(saved.getValue().getTenantId()).isNull();
        assertThat(saved.getValue().getActorUserId()).isEqualTo(userId);
    }
}

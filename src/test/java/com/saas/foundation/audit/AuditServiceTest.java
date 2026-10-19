package com.saas.foundation.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.audit.AuditAction;
import com.saas.foundation.domain.audit.AuditEvent;
import com.saas.foundation.domain.audit.AuditEventRepository;
import com.saas.foundation.domain.audit.AuditTargetType;
import com.saas.foundation.domain.tenant.Plan;
import com.saas.foundation.domain.tenant.SubscriptionStatus;
import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.user.MembershipRole;
import com.saas.foundation.domain.user.TenantMembership;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.membership.MembershipService;
import com.saas.foundation.tenant.TenantContext;

class AuditServiceTest {

    private final AuditEventRepository events = mock(AuditEventRepository.class);
    private final TenantMembershipRepository memberships = mock(TenantMembershipRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final AuditService service = new AuditService(events, memberships, tenants);
    private final UUID tenantId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(tenantId.toString());
        when(memberships.findByTenantIdAndUserId(tenantId, ownerId))
                .thenReturn(Optional.of(new TenantMembership(tenantId, ownerId, MembershipRole.OWNER)));
        when(memberships.findByTenantIdAndUserId(tenantId, memberId))
                .thenReturn(Optional.of(new TenantMembership(tenantId, memberId, MembershipRole.MEMBER)));
        when(tenants.findById(tenantId)).thenReturn(Optional.of(organization(Plan.PRO, SubscriptionStatus.ACTIVE)));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void anOwnerSeesTheNewestEventsForThisOrganization() {
        AuditEvent event = new AuditEvent(
                UUID.randomUUID(),
                tenantId,
                ownerId,
                AuditAction.AUTH_LOGIN,
                AuditTargetType.USER,
                ownerId,
                Map.of("email", "owner@acme.test"),
                Instant.parse("2026-10-19T16:00:00Z"));
        when(events.findByTenantIdOrderByCreatedAtDesc(eq(tenantId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event)));

        AuditPageResponse page = service.list(ownerId, Pageable.ofSize(20));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.action()).isEqualTo("AUTH_LOGIN");
            assertThat(item.metadata()).containsEntry("email", "owner@acme.test");
        });
    }

    @Test
    void theFreePlanDoesNotOpenTheAuditLog() {
        when(tenants.findById(tenantId)).thenReturn(Optional.of(organization(Plan.FREE, SubscriptionStatus.NONE)));

        assertThatThrownBy(() -> service.list(ownerId, Pageable.ofSize(20)))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
                    assertThat(ex.getReason()).isEqualTo(AuditService.PRO_REQUIRED);
                });
    }

    @Test
    void aMemberCannotReadTheAuditLog() {
        assertThatThrownBy(() -> service.list(memberId, Pageable.ofSize(20)))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getReason()).isEqualTo(MembershipService.OWNER_REQUIRED);
                });
    }

    private Tenant organization(Plan plan, SubscriptionStatus status) {
        Tenant tenant = new Tenant("Acme");
        ReflectionTestUtils.setField(tenant, "id", tenantId);
        tenant.setPlan(plan);
        tenant.setSubscriptionStatus(status);
        return tenant;
    }
}

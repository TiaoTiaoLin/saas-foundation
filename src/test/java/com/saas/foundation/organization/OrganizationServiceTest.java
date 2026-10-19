package com.saas.foundation.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.audit.AuditRecorder;
import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.user.MembershipRole;
import com.saas.foundation.domain.user.TenantMembership;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.membership.MembershipService;
import com.saas.foundation.storage.MemoryObjectStorage;
import com.saas.foundation.tenant.TenantContext;

class OrganizationServiceTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final TenantMembershipRepository memberships = mock(TenantMembershipRepository.class);
    private final MemoryObjectStorage images = new MemoryObjectStorage();
    private OrganizationService service;
    private Tenant tenant;
    private UUID ownerId;
    private UUID memberId;

    @BeforeEach
    void setUp() {
        service = new OrganizationService(tenants, memberships, images, mock(AuditRecorder.class));
        tenant = new Tenant("Acme");
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        TenantContext.setTenantId(tenant.getId().toString());
        ownerId = UUID.randomUUID();
        memberId = UUID.randomUUID();
        when(tenants.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(memberships.findByTenantIdAndUserId(tenant.getId(), ownerId))
                .thenReturn(Optional.of(new TenantMembership(tenant.getId(), ownerId, MembershipRole.OWNER)));
        when(memberships.findByTenantIdAndUserId(tenant.getId(), memberId))
                .thenReturn(Optional.of(new TenantMembership(tenant.getId(), memberId, MembershipRole.MEMBER)));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void anOwnerCanSaveAndClearTheLogo() throws Exception {
        OrganizationResponse saved = service.saveLogo(ownerId, png());

        assertThat(saved.name()).isEqualTo("Acme");
        assertThat(saved.logoUrl()).startsWith("https://images.test/logos/" + tenant.getId() + "/");
        assertThat(images.contains(tenant.getLogoKey())).isTrue();

        OrganizationResponse cleared = service.clearLogo(ownerId);

        assertThat(cleared.logoUrl()).isNull();
        assertThat(tenant.getLogoKey()).isNull();
    }

    @Test
    void aMemberCannotChangeTheLogo() throws Exception {
        byte[] png = png();

        assertThatThrownBy(() -> service.saveLogo(memberId, png))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getReason()).isEqualTo(MembershipService.OWNER_REQUIRED);
                });
        assertThat(tenant.getLogoKey()).isNull();
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}

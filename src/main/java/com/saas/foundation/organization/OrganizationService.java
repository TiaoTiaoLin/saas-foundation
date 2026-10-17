package com.saas.foundation.organization;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.user.MembershipRole;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.membership.MembershipService;
import com.saas.foundation.storage.ImageFiles;
import com.saas.foundation.storage.ImageKeys;
import com.saas.foundation.storage.ObjectStorage;
import com.saas.foundation.tenant.TenantContext;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OrganizationService {

    private final TenantRepository tenantRepository;
    private final TenantMembershipRepository tenantMembershipRepository;
    private final ObjectStorage objectStorage;

    public OrganizationService(TenantRepository tenantRepository,
                               TenantMembershipRepository tenantMembershipRepository,
                               ObjectStorage objectStorage) {
        this.tenantRepository = tenantRepository;
        this.tenantMembershipRepository = tenantMembershipRepository;
        this.objectStorage = objectStorage;
    }

    @Transactional(readOnly = true)
    public OrganizationResponse current() {
        return response(requireTenant(currentTenantId()));
    }

    @Transactional
    public OrganizationResponse saveLogo(UUID userId, byte[] bytes) {
        UUID tenantId = currentTenantId();
        requireOwner(tenantId, userId);
        Tenant tenant = requireTenant(tenantId);
        byte[] jpeg = ImageFiles.jpeg(bytes);
        String key = ImageKeys.logo(tenantId);
        objectStorage.put(key, jpeg);
        String previous = tenant.getLogoKey();
        tenant.setLogoKey(key);
        deleteQuietly(previous);
        return response(tenant);
    }

    @Transactional
    public OrganizationResponse clearLogo(UUID userId) {
        UUID tenantId = currentTenantId();
        requireOwner(tenantId, userId);
        Tenant tenant = requireTenant(tenantId);
        String previous = tenant.getLogoKey();
        tenant.setLogoKey(null);
        deleteQuietly(previous);
        return response(tenant);
    }

    private OrganizationResponse response(Tenant tenant) {
        return new OrganizationResponse(tenant.getName(), objectStorage.signedUrl(tenant.getLogoKey()));
    }

    private void requireOwner(UUID tenantId, UUID userId) {
        boolean owner = tenantMembershipRepository.findByTenantIdAndUserId(tenantId, userId)
                .filter(membership -> membership.getRole() == MembershipRole.OWNER)
                .isPresent();
        if (!owner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, MembershipService.OWNER_REQUIRED);
        }
    }

    private Tenant requireTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));
    }

    private void deleteQuietly(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            objectStorage.delete(key);
        } catch (RuntimeException ex) {
            log.warn("Could not delete previous logo key={}: {}", key, ex.toString());
        }
    }

    private static UUID currentTenantId() {
        return UUID.fromString(TenantContext.requireTenantId());
    }
}

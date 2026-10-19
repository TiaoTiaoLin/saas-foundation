package com.saas.foundation.audit;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.audit.AuditEvent;
import com.saas.foundation.domain.audit.AuditEventRepository;
import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.user.MembershipRole;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.membership.MembershipService;
import com.saas.foundation.tenant.TenantContext;

@Service
public class AuditService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final String PRO_REQUIRED = "The Pro plan is required";

    private final AuditEventRepository auditEventRepository;
    private final TenantMembershipRepository tenantMembershipRepository;
    private final TenantRepository tenantRepository;

    public AuditService(AuditEventRepository auditEventRepository,
                        TenantMembershipRepository tenantMembershipRepository,
                        TenantRepository tenantRepository) {
        this.auditEventRepository = auditEventRepository;
        this.tenantMembershipRepository = tenantMembershipRepository;
        this.tenantRepository = tenantRepository;
    }

    @Transactional(readOnly = true)
    public AuditPageResponse list(UUID userId, Pageable pageable) {
        UUID tenantId = UUID.fromString(TenantContext.requireTenantId());
        boolean owner = tenantMembershipRepository.findByTenantIdAndUserId(tenantId, userId)
                .filter(membership -> membership.getRole() == MembershipRole.OWNER)
                .isPresent();
        if (!owner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, MembershipService.OWNER_REQUIRED);
        }
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));
        if (!tenant.allowsAuditAccess()) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, PRO_REQUIRED);
        }
        Page<AuditEvent> page = auditEventRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, sized(pageable));
        return new AuditPageResponse(
                page.getContent().stream().map(AuditEventResponse::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements());
    }

    private static Pageable sized(Pageable requested) {
        int size = requested.getPageSize();
        if (size < 1) {
            size = DEFAULT_PAGE_SIZE;
        } else if (size > MAX_PAGE_SIZE) {
            size = MAX_PAGE_SIZE;
        }
        return PageRequest.of(Math.max(requested.getPageNumber(), 0), size);
    }
}

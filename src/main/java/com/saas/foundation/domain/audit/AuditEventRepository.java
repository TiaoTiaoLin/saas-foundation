package com.saas.foundation.domain.audit;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    Page<AuditEvent> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
}

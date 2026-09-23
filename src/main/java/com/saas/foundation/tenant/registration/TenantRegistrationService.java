package com.saas.foundation.tenant.registration;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.saas.foundation.integration.email.EmailSendResult;
import com.saas.foundation.integration.email.ResilientEmailService;
import com.saas.foundation.messaging.TenantEventPublisher;
import com.saas.foundation.messaging.event.TenantRegisteredEvent;
import com.saas.foundation.tenant.TenantContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantRegistrationService {

    private final ResilientEmailService resilientEmailService;
    private final ObjectProvider<TenantEventPublisher> tenantEventPublisher;

    public RegisterTenantResponse register(RegisterTenantRequest request) {
        String tenantId = TenantContext.isPresent()
                ? TenantContext.requireTenantId()
                : UUID.randomUUID().toString();

        TenantRegisteredEvent event = TenantRegisteredEvent.of(
                tenantId,
                request.organizationName().trim(),
                request.adminEmail().trim()
        );

        tenantEventPublisher.ifAvailable(publisher -> publisher.publishTenantRegistered(event));

        EmailSendResult emailResult = resilientEmailService.sendWelcomeEmail(
                tenantId, request.adminEmail().trim());

        log.info("Tenant registered tenantId={} org={} emailAccepted={}",
                tenantId, request.organizationName(), emailResult.success());

        return new RegisterTenantResponse(
                tenantId,
                request.organizationName().trim(),
                request.adminEmail().trim(),
                emailResult.success(),
                emailResult.detail()
        );
    }
}

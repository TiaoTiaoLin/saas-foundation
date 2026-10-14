package com.saas.foundation.tenant.registration;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.user.MembershipRole;
import com.saas.foundation.domain.user.TenantMembership;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.messaging.TenantEventPublisher;
import com.saas.foundation.messaging.event.TenantRegisteredEvent;
import com.saas.foundation.security.PasswordConfig;

/**
 * Signup: creates the tenant, its admin user and an {@link MembershipRole#OWNER} membership
 * in one transaction, then publishes {@link TenantRegisteredEvent}. The welcome email is sent
 * by the listener for that event.
 */
@Service
public class TenantRegistrationService {

    static final String EMAIL_TAKEN = "An account with this email already exists";

    private final ObjectProvider<TenantEventPublisher> tenantEventPublisher;
    private final TenantRepository tenantRepository;
    private final UserAccountRepository userAccountRepository;
    private final TenantMembershipRepository tenantMembershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;

    public TenantRegistrationService(ObjectProvider<TenantEventPublisher> tenantEventPublisher,
                                     TenantRepository tenantRepository,
                                     UserAccountRepository userAccountRepository,
                                     TenantMembershipRepository tenantMembershipRepository,
                                     PasswordEncoder passwordEncoder,
                                     PlatformTransactionManager transactionManager) {
        this.tenantEventPublisher = tenantEventPublisher;
        this.tenantRepository = tenantRepository;
        this.userAccountRepository = userAccountRepository;
        this.tenantMembershipRepository = tenantMembershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public RegisterTenantResponse register(RegisterTenantRequest request) {
        String organizationName = request.organizationName().trim();
        String adminEmail = request.adminEmail().trim();
        if (PasswordConfig.exceedsMaxPasswordBytes(request.adminPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be at most " + PasswordConfig.MAX_PASSWORD_BYTES + " bytes");
        }
        String passwordHash = passwordEncoder.encode(request.adminPassword());

        CreatedTenant created = createTenantWithOwner(organizationName, adminEmail, passwordHash);
        String tenantId = created.tenantId().toString();

        TenantRegisteredEvent event = TenantRegisteredEvent.of(tenantId, organizationName, adminEmail);
        tenantEventPublisher.ifAvailable(publisher -> publisher.publishTenantRegistered(event));

        return new RegisterTenantResponse(tenantId, organizationName, adminEmail);
    }

    private CreatedTenant createTenantWithOwner(String organizationName, String adminEmail, String passwordHash) {
        try {
            return transactionTemplate.execute(status -> {
                if (userAccountRepository.findByEmail(adminEmail).isPresent()) {
                    throw emailTaken();
                }
                Tenant tenant = tenantRepository.save(new Tenant(organizationName));
                UserAccount admin = userAccountRepository.save(new UserAccount(adminEmail, passwordHash));
                tenantMembershipRepository.save(
                        new TenantMembership(tenant.getId(), admin.getId(), MembershipRole.OWNER));
                return new CreatedTenant(tenant.getId(), admin.getId());
            });
        } catch (DataIntegrityViolationException ex) {
            // Concurrent signup with the same email lost the race on uk_users_email_lower.
            throw emailTaken();
        }
    }

    private static ResponseStatusException emailTaken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, EMAIL_TAKEN);
    }

    private record CreatedTenant(UUID tenantId, UUID adminUserId) {
    }
}

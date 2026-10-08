package com.saas.foundation.tenant.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.tenant.Tenant;
import com.saas.foundation.domain.tenant.TenantRepository;
import com.saas.foundation.domain.user.MembershipRole;
import com.saas.foundation.domain.user.TenantMembership;
import com.saas.foundation.domain.user.TenantMembershipRepository;
import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.integration.email.EmailSendResult;
import com.saas.foundation.integration.email.ResilientEmailService;
import com.saas.foundation.messaging.TenantEventPublisher;

class TenantRegistrationServiceTest {

    private static final String PASSWORD = "correct horse battery staple";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final ResilientEmailService emailService = mock(ResilientEmailService.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final TenantMembershipRepository memberships = mock(TenantMembershipRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<TenantEventPublisher> publisherProvider = mock(ObjectProvider.class);

    private TenantRegistrationService service;

    @BeforeEach
    void setUp() {
        service = new TenantRegistrationService(emailService, publisherProvider, tenants, users, memberships,
                passwordEncoder, transactionManager);
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        when(tenants.save(any(Tenant.class))).thenAnswer(inv -> withId(inv.getArgument(0)));
        when(users.save(any(UserAccount.class))).thenAnswer(inv -> withId(inv.getArgument(0)));
        when(memberships.save(any(TenantMembership.class))).thenAnswer(inv -> withId(inv.getArgument(0)));
        when(emailService.sendWelcomeEmail(anyString(), anyString())).thenReturn(EmailSendResult.ok("msg-1"));
    }

    @Test
    void createsTenantAdminUserAndOwnerMembership() {
        RegisterTenantResponse response = service.register(
                new RegisterTenantRequest("  Acme Corp ", " owner@acme.test ", PASSWORD));

        ArgumentCaptor<Tenant> tenant = ArgumentCaptor.forClass(Tenant.class);
        ArgumentCaptor<UserAccount> admin = ArgumentCaptor.forClass(UserAccount.class);
        ArgumentCaptor<TenantMembership> membership = ArgumentCaptor.forClass(TenantMembership.class);
        verify(tenants).save(tenant.capture());
        verify(users).save(admin.capture());
        verify(memberships).save(membership.capture());

        assertThat(tenant.getValue().getName()).isEqualTo("Acme Corp");
        assertThat(tenant.getValue().isActive()).isTrue();
        assertThat(admin.getValue().getEmail()).isEqualTo("owner@acme.test");
        assertThat(admin.getValue().getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, admin.getValue().getPasswordHash())).isTrue();
        assertThat(membership.getValue().getTenantId()).isEqualTo(tenant.getValue().getId());
        assertThat(membership.getValue().getUserId()).isEqualTo(admin.getValue().getId());
        assertThat(membership.getValue().getRole()).isEqualTo(MembershipRole.OWNER);

        assertThat(response.tenantId()).isEqualTo(tenant.getValue().getId().toString());
        assertThat(response.organizationName()).isEqualTo("Acme Corp");
        assertThat(response.welcomeEmailAccepted()).isTrue();
    }

    @Test
    void welcomeEmailIsSentAfterCommit() {
        service.register(new RegisterTenantRequest("Acme", "owner@acme.test", PASSWORD));

        InOrder order = inOrder(memberships, transactionManager, emailService);
        order.verify(memberships).save(any(TenantMembership.class));
        order.verify(transactionManager).commit(any());
        order.verify(emailService).sendWelcomeEmail(anyString(), anyString());
    }

    @Test
    void existingEmailIsConflictAndCreatesNothing() {
        when(users.findByEmail("owner@acme.test"))
                .thenReturn(Optional.of(new UserAccount("owner@acme.test", "hash")));

        assertStatus(() -> service.register(new RegisterTenantRequest("Acme", "owner@acme.test", PASSWORD)),
                HttpStatus.CONFLICT);

        verify(tenants, never()).save(any());
        verify(users, never()).save(any());
        verify(emailService, never()).sendWelcomeEmail(anyString(), anyString());
    }

    @Test
    void concurrentDuplicateEmailIsConflict() {
        when(users.save(any(UserAccount.class))).thenThrow(new DataIntegrityViolationException("uk_users_email_lower"));

        assertStatus(() -> service.register(new RegisterTenantRequest("Acme", "owner@acme.test", PASSWORD)),
                HttpStatus.CONFLICT);
        verify(emailService, never()).sendWelcomeEmail(anyString(), anyString());
    }

    @Test
    void passwordOver72BytesIsRejected() {
        String multiByte = "é".repeat(40); // 40 characters, 80 bytes in UTF-8

        assertStatus(() -> service.register(new RegisterTenantRequest("Acme", "owner@acme.test", multiByte)),
                HttpStatus.BAD_REQUEST);
        verify(tenants, never()).save(any());
    }

    private static <T> T withId(T entity) {
        if (ReflectionTestUtils.getField(entity, "id") == null) {
            ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        }
        return entity;
    }

    private static void assertStatus(Runnable action, HttpStatus expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(expected));
    }
}

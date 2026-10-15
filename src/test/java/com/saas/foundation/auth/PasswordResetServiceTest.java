package com.saas.foundation.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.user.PasswordResetToken;
import com.saas.foundation.domain.user.PasswordResetTokenRepository;
import com.saas.foundation.domain.user.RefreshTokenRepository;
import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.integration.email.EmailProviderClient;
import com.saas.foundation.integration.email.EmailSendResult;
import com.saas.foundation.integration.email.ResilientEmailService;
import com.saas.foundation.security.Hashing;
import com.saas.foundation.security.PasswordResetProperties;
import com.saas.foundation.security.RefreshTokenProperties;

class PasswordResetServiceTest {

    private static final String EMAIL = "owner@acme.test";
    private static final String OLD_PASSWORD = "correct horse battery staple";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final PasswordResetTokenRepository resetTokens = mock(PasswordResetTokenRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final CapturingEmail email = new CapturingEmail();
    private final AtomicReference<PasswordResetToken> stored = new AtomicReference<>();

    private PasswordResetService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        user = new UserAccount(EMAIL, passwordEncoder.encode(OLD_PASSWORD));
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(users.findByEmail(any())).thenAnswer(invocation -> {
            String email = invocation.getArgument(0);
            return EMAIL.equalsIgnoreCase(email.trim()) ? Optional.of(user) : Optional.empty();
        });
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(resetTokens.save(any(PasswordResetToken.class))).thenAnswer(invocation -> {
            PasswordResetToken token = invocation.getArgument(0);
            ReflectionTestUtils.invokeMethod(token, "onPersist");
            stored.set(token);
            return token;
        });
        when(resetTokens.findByTokenHash(any())).thenAnswer(invocation -> {
            PasswordResetToken token = stored.get();
            if (token != null && token.getTokenHash().equals(invocation.getArgument(0))) {
                return Optional.of(token);
            }
            return Optional.empty();
        });

        service = new PasswordResetService(
                users,
                resetTokens,
                passwordEncoder,
                new RefreshTokenService(refreshTokens, new RefreshTokenProperties(Duration.ofDays(30))),
                email,
                new PasswordResetProperties(Duration.ofMinutes(30), "http://localhost:5173"));
    }

    @Test
    void unknownEmailDoesNotSendALink() {
        service.requestReset("missing@acme.test");

        verify(resetTokens, never()).save(any());
        assertThat(email.sends).isZero();
    }

    @Test
    void requestStoresOnlyTheTokenHashAndEmailsTheLink() {
        service.requestReset("  OWNER@Acme.test ");

        PasswordResetToken saved = stored.get();
        assertThat(saved.getUserId()).isEqualTo(user.getId());
        assertThat(saved.getTokenHash()).hasSize(64);
        assertThat(email.sends).isEqualTo(1);
        assertThat(email.to).isEqualTo(EMAIL);
        String token = tokenFrom(email.url);
        assertThat(saved.getTokenHash()).isEqualTo(Hashing.sha256Hex(token));
        assertThat(email.url).startsWith("http://localhost:5173/reset-password?token=");
        verify(resetTokens).invalidateActive(eq(user.getId()), any());
    }

    @Test
    void confirmReplacesThePasswordAndRevokesSessions() {
        service.requestReset(EMAIL);
        String token = tokenFrom(email.url);
        String replacement = "a different passphrase";

        service.confirmReset(token, replacement);

        assertThat(passwordEncoder.matches(replacement, user.getPasswordHash())).isTrue();
        assertThat(stored.get().isUsed()).isTrue();
        verify(refreshTokens).revokeAllActiveForUser(eq(user.getId()), any());
    }

    @Test
    void confirmRejectsReusingTheCurrentPassword() {
        service.requestReset(EMAIL);
        String token = tokenFrom(email.url);

        assertThatThrownBy(() -> service.confirmReset(token, OLD_PASSWORD))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getReason()).isEqualTo(AuthService.PASSWORD_UNCHANGED));
        assertThat(stored.get().isUsed()).isFalse();
        assertThat(passwordEncoder.matches(OLD_PASSWORD, user.getPasswordHash())).isTrue();
        verify(refreshTokens, never()).revokeAllActiveForUser(any(), any());
    }

    @Test
    void confirmRejectsATokenThatWasAlreadyUsed() {
        service.requestReset(EMAIL);
        String token = tokenFrom(email.url);
        service.confirmReset(token, "a different passphrase");
        String hashAfter = user.getPasswordHash();

        assertThatThrownBy(() -> service.confirmReset(token, "yet another passphrase"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(user.getPasswordHash()).isEqualTo(hashAfter);
    }

    @Test
    void confirmRejectsAnExpiredToken() {
        service.requestReset(EMAIL);
        ReflectionTestUtils.setField(stored.get(), "expiresAt", Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> service.confirmReset(tokenFrom(email.url), "a different passphrase"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getReason()).isEqualTo(PasswordResetService.INVALID_RESET));
        assertThat(passwordEncoder.matches(OLD_PASSWORD, user.getPasswordHash())).isTrue();
    }

    private static String tokenFrom(String url) {
        String prefix = "token=";
        int start = url.indexOf(prefix);
        return url.substring(start + prefix.length());
    }

    private static final class CapturingEmail extends ResilientEmailService {

        private int sends;
        private String to;
        private String url;

        private CapturingEmail() {
            super((EmailProviderClient) message -> EmailSendResult.ok("msg"));
        }

        @Override
        public EmailSendResult sendPasswordReset(String recipientEmail, String resetUrl) {
            sends++;
            to = recipientEmail;
            url = resetUrl;
            return EmailSendResult.ok("msg");
        }
    }
}

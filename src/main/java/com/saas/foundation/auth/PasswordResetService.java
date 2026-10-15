package com.saas.foundation.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.user.PasswordResetToken;
import com.saas.foundation.domain.user.PasswordResetTokenRepository;
import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.integration.email.ResilientEmailService;
import com.saas.foundation.security.Hashing;
import com.saas.foundation.security.PasswordConfig;
import com.saas.foundation.security.PasswordResetProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Emails a single-use reset link and replaces the password when that link is confirmed.
 * Requesting a reset always completes the same way, whether or not the email belongs to an account.
 */
@Slf4j
@Service
public class PasswordResetService {

    static final String INVALID_RESET = "This reset link is invalid or has expired";

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserAccountRepository userAccountRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final ResilientEmailService resilientEmailService;
    private final PasswordResetProperties properties;

    public PasswordResetService(UserAccountRepository userAccountRepository,
                                PasswordResetTokenRepository passwordResetTokenRepository,
                                PasswordEncoder passwordEncoder,
                                RefreshTokenService refreshTokenService,
                                ResilientEmailService resilientEmailService,
                                PasswordResetProperties properties) {
        this.userAccountRepository = userAccountRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.resilientEmailService = resilientEmailService;
        this.properties = properties;
    }

    @Transactional
    public void requestReset(String email) {
        UserAccount user = userAccountRepository.findByEmail(email.trim())
                .filter(UserAccount::isEnabled)
                .orElse(null);
        if (user == null) {
            return;
        }

        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        passwordResetTokenRepository.invalidateActive(user.getId(), now);
        passwordResetTokenRepository.save(
                new PasswordResetToken(user.getId(), Hashing.sha256Hex(token), now.plus(properties.ttl())));

        String link = resetLink(token);
        String recipient = user.getEmail();
        sendResetEmailAfterCommit(recipient, link);
        log.info("Password reset requested userId={}", user.getId());
    }

    private void sendResetEmailAfterCommit(String recipient, String link) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            resilientEmailService.sendPasswordReset(recipient, link);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                resilientEmailService.sendPasswordReset(recipient, link);
            }
        });
    }

    @Transactional
    public void confirmReset(String token, String newPassword) {
        if (newPassword.length() < 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 12 characters");
        }
        if (PasswordConfig.exceedsMaxPasswordBytes(newPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be at most " + PasswordConfig.MAX_PASSWORD_BYTES + " bytes");
        }

        PasswordResetToken reset = passwordResetTokenRepository.findByTokenHash(Hashing.sha256Hex(token.trim()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_RESET));
        Instant now = Instant.now();
        if (reset.isUsed() || reset.isExpiredAt(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_RESET);
        }

        UserAccount user = userAccountRepository.findById(reset.getUserId())
                .filter(UserAccount::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_RESET));
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, AuthService.PASSWORD_UNCHANGED);
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        reset.markUsed(now);
        refreshTokenService.revokeAllForUser(user.getId());
        log.info("Password reset confirmed userId={}", user.getId());
    }

    private String resetLink(String token) {
        String base = properties.appBaseUrl().replaceAll("/+$", "");
        return base + "/reset-password?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }
}

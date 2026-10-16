package com.saas.foundation.auth;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;

@Service
public class ProfileService {

    private final UserAccountRepository userAccountRepository;

    public ProfileService(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    @Transactional(readOnly = true)
    public ProfileResponse get(UUID userId) {
        UserAccount user = requireUser(userId);
        return new ProfileResponse(user.getEmail(), user.getName());
    }

    @Transactional
    public ProfileResponse update(UUID userId, String name) {
        UserAccount user = requireUser(userId);
        user.setName(UserName.normalize(name));
        return new ProfileResponse(user.getEmail(), user.getName());
    }

    private UserAccount requireUser(UUID userId) {
        return userAccountRepository.findById(userId)
                .filter(UserAccount::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again to continue."));
    }
}

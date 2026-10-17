package com.saas.foundation.auth;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.saas.foundation.domain.user.UserAccount;
import com.saas.foundation.domain.user.UserAccountRepository;
import com.saas.foundation.storage.ImageFiles;
import com.saas.foundation.storage.ImageKeys;
import com.saas.foundation.storage.ObjectStorage;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ProfileService {

    private final UserAccountRepository userAccountRepository;
    private final ObjectStorage objectStorage;

    public ProfileService(UserAccountRepository userAccountRepository, ObjectStorage objectStorage) {
        this.userAccountRepository = userAccountRepository;
        this.objectStorage = objectStorage;
    }

    @Transactional(readOnly = true)
    public ProfileResponse get(UUID userId) {
        return response(requireUser(userId));
    }

    @Transactional
    public ProfileResponse update(UUID userId, String name) {
        UserAccount user = requireUser(userId);
        user.setName(UserName.normalize(name));
        return response(user);
    }

    @Transactional
    public ProfileResponse saveAvatar(UUID userId, byte[] bytes) {
        UserAccount user = requireUser(userId);
        byte[] jpeg = ImageFiles.jpeg(bytes);
        String key = ImageKeys.avatar(userId);
        objectStorage.put(key, jpeg);
        String previous = user.getAvatarKey();
        user.setAvatarKey(key);
        deleteQuietly(previous);
        return response(user);
    }

    @Transactional
    public ProfileResponse clearAvatar(UUID userId) {
        UserAccount user = requireUser(userId);
        String previous = user.getAvatarKey();
        user.setAvatarKey(null);
        deleteQuietly(previous);
        return response(user);
    }

    private ProfileResponse response(UserAccount user) {
        return new ProfileResponse(user.getEmail(), user.getName(), objectStorage.signedUrl(user.getAvatarKey()));
    }

    private void deleteQuietly(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            objectStorage.delete(key);
        } catch (RuntimeException ex) {
            log.warn("Could not delete previous avatar key={}: {}", key, ex.toString());
        }
    }

    private UserAccount requireUser(UUID userId) {
        return userAccountRepository.findById(userId)
                .filter(UserAccount::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again to continue."));
    }
}

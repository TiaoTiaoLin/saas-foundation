package com.saas.foundation.storage;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.extern.slf4j.Slf4j;

/** Local stand-in for the private bucket. Signed URLs are served by {@link LocalMediaController}. */
@Slf4j
@Component
@Profile("!prod & !test")
public class LocalDiskObjectStorage implements ObjectStorage {

    private final Path root;
    private final StorageProperties properties;

    public LocalDiskObjectStorage(StorageProperties properties) {
        if (properties.localDirectory() == null || properties.localDirectory().isBlank()
                || properties.signingSecret() == null || properties.signingSecret().length() < 32) {
            throw new IllegalStateException("Set saas.storage.local-directory and a signing secret of at least 32 characters");
        }
        this.properties = properties;
        this.root = Path.of(properties.localDirectory()).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] body) {
        Path path = resolve(key);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, body);
        } catch (IOException ex) {
            log.warn("Could not store local image: {}", ex.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The image could not be saved");
        }
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ex) {
            log.warn("Could not delete local image key={}: {}", key, ex.toString());
        }
    }

    @Override
    public String signedUrl(String key) {
        if (!ImageKeys.allowed(key)) {
            return null;
        }
        try {
            if (!Files.exists(resolve(key))) {
                return null;
            }
        } catch (RuntimeException ex) {
            return null;
        }
        long expires = Instant.now().plus(properties.signedUrlTtl()).getEpochSecond();
        String signature = MediaTokens.sign(key, expires, properties.signingSecret());
        return "/api/v1/media?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&expires=" + expires
                + "&sig=" + signature;
    }

    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException ex) {
            return null;
        }
    }

    private Path resolve(String key) {
        if (!ImageKeys.allowed(key)) {
            throw new IllegalArgumentException("Image key is not allowed");
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Image key is not allowed");
        }
        return path;
    }
}

package com.saas.foundation.storage;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Serves locally stored images. The signature is the credential, because an image tag cannot send a bearer token. */
@RestController
@Profile("!prod & !test")
@RequestMapping("/api/v1/media")
public class LocalMediaController {

    private final LocalDiskObjectStorage storage;
    private final StorageProperties properties;

    public LocalMediaController(LocalDiskObjectStorage storage, StorageProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    @GetMapping
    public ResponseEntity<byte[]> get(@RequestParam String key,
                                      @RequestParam long expires,
                                      @RequestParam String sig) {
        if (!ImageKeys.allowed(key) || !MediaTokens.valid(key, expires, sig, properties.signingSecret())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        byte[] body = storage.read(key);
        if (body == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        long remaining = Math.max(0, expires - java.time.Instant.now().getEpochSecond());
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(remaining)).cachePrivate())
                .body(body);
    }
}

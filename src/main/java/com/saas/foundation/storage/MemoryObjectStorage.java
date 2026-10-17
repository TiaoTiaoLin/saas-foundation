package com.saas.foundation.storage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Stores images in memory. Used by the test profile and by unit tests. */
@Component
@Profile("test")
public class MemoryObjectStorage implements ObjectStorage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String key, byte[] body) {
        objects.put(key, body.clone());
    }

    @Override
    public void delete(String key) {
        if (key != null) {
            objects.remove(key);
        }
    }

    @Override
    public String signedUrl(String key) {
        if (key == null || key.isBlank() || !objects.containsKey(key)) {
            return null;
        }
        return "https://images.test/" + key;
    }

    public boolean contains(String key) {
        return objects.containsKey(key);
    }
}

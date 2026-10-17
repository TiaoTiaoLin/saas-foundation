package com.saas.foundation.storage;

import java.util.UUID;

/** Object paths inside the private bucket. */
public final class ImageKeys {

    private static final String PATTERN =
            "^(avatars|logos)/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                    + "/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.jpg$";

    private ImageKeys() {
    }

    public static String avatar(UUID userId) {
        return "avatars/" + userId + "/" + UUID.randomUUID() + ".jpg";
    }

    public static String logo(UUID tenantId) {
        return "logos/" + tenantId + "/" + UUID.randomUUID() + ".jpg";
    }

    public static boolean allowed(String key) {
        return key != null && key.matches(PATTERN);
    }
}

package com.saas.foundation.storage;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Private image bucket. Callers store the object key, not a URL. */
public interface ObjectStorage {

    void put(String key, byte[] body);

    void delete(String key);

    /** A URL that expires. Blank keys return null. */
    String signedUrl(String key);

    default Map<String, String> signedUrls(Collection<String> keys) {
        Map<String, String> urls = new LinkedHashMap<>();
        for (String key : keys) {
            String url = signedUrl(key);
            if (url != null) {
                urls.put(key, url);
            }
        }
        return urls;
    }
}

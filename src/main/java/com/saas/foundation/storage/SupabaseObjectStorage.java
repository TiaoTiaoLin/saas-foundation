package com.saas.foundation.storage;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Private Supabase Storage bucket. Uploads use the service-role key, which stays on the server.
 * Responses carry a short-lived signed URL.
 */
@Slf4j
@Component
@Profile("prod")
public class SupabaseObjectStorage implements ObjectStorage {

    private final RestClient client;
    private final String bucket;
    private final String supabaseUrl;
    private final int expiresInSeconds;
    private final ObjectMapper objectMapper;

    public SupabaseObjectStorage(StorageProperties properties, ObjectMapper objectMapper) {
        if (blank(properties.url()) || blank(properties.bucket()) || blank(properties.serviceRoleKey())) {
            throw new IllegalStateException("Set SUPABASE_URL, SUPABASE_BUCKET, and SUPABASE_SERVICE_ROLE_KEY");
        }
        this.bucket = properties.bucket();
        this.supabaseUrl = trimSlash(properties.url());
        this.expiresInSeconds = (int) properties.signedUrlTtl().toSeconds();
        this.objectMapper = objectMapper;
        this.client = RestClient.builder()
                .baseUrl(supabaseUrl + "/storage/v1")
                .defaultHeader("Authorization", "Bearer " + properties.serviceRoleKey())
                .defaultHeader("apikey", properties.serviceRoleKey())
                .build();
    }

    @Override
    public void put(String key, byte[] body) {
        try {
            client.post()
                    .uri(objectUri(key))
                    .contentType(MediaType.IMAGE_JPEG)
                    .header("x-upsert", "true")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            log.warn("Image upload failed status={}", ex.getStatusCode().value());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The image could not be saved");
        }
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            client.method(org.springframework.http.HttpMethod.DELETE)
                    .uri(URI.create(supabaseUrl + "/storage/v1/object/" + bucket))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("prefixes", List.of(key)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return;
            }
            log.warn("Image delete failed status={}", ex.getStatusCode().value());
        }
    }

    @Override
    public String signedUrl(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return signedUrls(List.of(key)).get(key);
    }

    @Override
    public Map<String, String> signedUrls(Collection<String> keys) {
        List<String> paths = keys.stream().filter(key -> key != null && !key.isBlank()).distinct().toList();
        Map<String, String> urls = new LinkedHashMap<>();
        if (paths.isEmpty()) {
            return urls;
        }
        try {
            String body = client.post()
                    .uri(URI.create(supabaseUrl + "/storage/v1/object/sign/" + bucket))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("expiresIn", expiresInSeconds, "paths", paths))
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return urls;
            }
            JsonNode root = objectMapper.readTree(body);
            JsonNode items = root.isArray() ? root : root.path("signedURLs");
            if (!items.isArray() && root.has("signedURL")) {
                String only = paths.get(0);
                String url = absolute(root.path("signedURL").asText(""));
                if (url != null) {
                    urls.put(only, url);
                }
                return urls;
            }
            for (JsonNode item : items) {
                String path = item.path("path").asText("");
                String signed = item.path("signedURL").asText("");
                if (signed.isBlank()) {
                    signed = item.path("signedUrl").asText("");
                }
                String url = absolute(signed);
                if (!path.isBlank() && url != null && (item.path("error").isNull() || item.path("error").asText("").isBlank())) {
                    urls.put(path, url);
                }
            }
        } catch (RestClientResponseException ex) {
            log.warn("Signed image URL failed status={}", ex.getStatusCode().value());
        } catch (Exception ex) {
            log.warn("Signed image URL failed: {}", ex.toString());
        }
        return urls;
    }

    static String absolute(String supabaseUrl, String signedUrl) {
        if (signedUrl == null || signedUrl.isBlank()) {
            return null;
        }
        if (signedUrl.startsWith("http://") || signedUrl.startsWith("https://")) {
            return signedUrl;
        }
        String path = signedUrl.startsWith("/") ? signedUrl : "/" + signedUrl;
        return trimSlash(supabaseUrl) + "/storage/v1" + path;
    }

    private String absolute(String signedUrl) {
        return absolute(supabaseUrl, signedUrl);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private URI objectUri(String key) {
        return URI.create(supabaseUrl + "/storage/v1/object/" + bucket + "/" + key);
    }

    private static String trimSlash(String value) {
        return value.replaceAll("/+$", "");
    }
}

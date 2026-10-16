package com.saas.foundation.common.exception;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.saas.foundation.auth.LoginOrganization;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldViolation> violations,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<LoginOrganization> organizations
) {
    public ApiError {
        violations = violations == null ? List.of() : violations;
        organizations = organizations == null ? List.of() : organizations;
    }

    public record FieldViolation(String field, String message) {
    }

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, path, List.of(), List.of());
    }

    public static ApiError of(int status, String error, String message, String path,
                              List<FieldViolation> violations) {
        return new ApiError(Instant.now(), status, error, message, path, violations, List.of());
    }

    public static ApiError organizationChoice(String message, String path, List<LoginOrganization> organizations) {
        return new ApiError(
                Instant.now(),
                409,
                "Conflict",
                message,
                path,
                List.of(),
                organizations);
    }
}

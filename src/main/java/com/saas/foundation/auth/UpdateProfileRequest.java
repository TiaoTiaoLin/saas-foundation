package com.saas.foundation.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank @Size(max = UserName.MAX_LENGTH) String name
) {
}

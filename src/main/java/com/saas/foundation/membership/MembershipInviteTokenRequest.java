package com.saas.foundation.membership;

import jakarta.validation.constraints.NotBlank;

public record MembershipInviteTokenRequest(
        @NotBlank String token
) {
}

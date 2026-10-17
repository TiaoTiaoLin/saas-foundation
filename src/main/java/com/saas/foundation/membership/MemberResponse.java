package com.saas.foundation.membership;

import java.util.UUID;

import com.saas.foundation.domain.user.MembershipRole;

public record MemberResponse(UUID userId, String email, String name, MembershipRole role, String avatarUrl) {
}

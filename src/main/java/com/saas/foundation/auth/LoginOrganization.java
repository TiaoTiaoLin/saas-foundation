package com.saas.foundation.auth;

import java.util.UUID;

import com.saas.foundation.domain.user.MembershipRole;

public record LoginOrganization(
        UUID tenantId,
        String name,
        MembershipRole role
) {
}

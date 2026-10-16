package com.saas.foundation.membership;

import java.util.UUID;

import com.saas.foundation.domain.user.MembershipRole;

public record AcceptMembershipInviteResponse(
        UUID tenantId,
        String organizationName,
        MembershipRole role
) {
}

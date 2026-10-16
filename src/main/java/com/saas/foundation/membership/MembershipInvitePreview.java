package com.saas.foundation.membership;

import com.saas.foundation.domain.user.MembershipRole;

public record MembershipInvitePreview(
        String organizationName,
        MembershipRole role
) {
}

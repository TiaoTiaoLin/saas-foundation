package com.saas.foundation.membership;

import java.util.UUID;

import com.saas.foundation.domain.user.MembershipRole;

public record InviteMemberResponse(
        UUID userId,
        String email,
        MembershipRole role,
        boolean emailAccepted,
        String emailDetail
) {
}

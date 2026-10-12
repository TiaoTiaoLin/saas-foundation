package com.saas.foundation.membership;

import com.saas.foundation.domain.user.MembershipRole;

import jakarta.validation.constraints.NotNull;

public record UpdateMemberRoleRequest(@NotNull MembershipRole role) {
}

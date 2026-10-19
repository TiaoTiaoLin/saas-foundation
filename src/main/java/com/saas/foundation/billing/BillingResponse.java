package com.saas.foundation.billing;

public record BillingResponse(
        String plan,
        String subscriptionStatus,
        boolean productAccess,
        boolean auditAccess,
        boolean configured,
        boolean canSubscribe,
        boolean canManage
) {
}

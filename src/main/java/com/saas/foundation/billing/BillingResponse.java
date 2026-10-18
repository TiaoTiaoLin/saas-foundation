package com.saas.foundation.billing;

public record BillingResponse(
        String subscriptionStatus,
        boolean productAccess,
        boolean configured,
        boolean canSubscribe,
        boolean canManage
) {
}

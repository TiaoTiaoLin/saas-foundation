package com.saas.foundation.billing;

public record CheckoutCommand(
        String priceId,
        String customerId,
        String customerEmail,
        String tenantId,
        String successUrl,
        String cancelUrl
) {
}

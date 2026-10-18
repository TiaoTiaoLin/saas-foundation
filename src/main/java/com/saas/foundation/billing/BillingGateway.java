package com.saas.foundation.billing;

/** Stripe calls that need the network. Webhook signatures are checked separately. */
public interface BillingGateway {

    String createCheckoutSession(CheckoutCommand command);

    String createPortalSession(String customerId, String returnUrl);
}

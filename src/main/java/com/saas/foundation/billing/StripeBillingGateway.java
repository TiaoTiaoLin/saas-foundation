package com.saas.foundation.billing;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class StripeBillingGateway implements BillingGateway {

    private final BillingProperties properties;

    public StripeBillingGateway(BillingProperties properties) {
        this.properties = properties;
    }

    @Override
    public String createCheckoutSession(CheckoutCommand command) {
        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setSuccessUrl(command.successUrl())
                .setCancelUrl(command.cancelUrl())
                .setClientReferenceId(command.tenantId())
                .putMetadata("tenant_id", command.tenantId())
                .setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                        .putMetadata("tenant_id", command.tenantId())
                        .build())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(command.priceId())
                        .setQuantity(1L)
                        .build());
        if (command.customerId() != null && !command.customerId().isBlank()) {
            params.setCustomer(command.customerId());
        } else {
            params.setCustomerEmail(command.customerEmail());
        }
        try {
            Session session = Session.create(params.build(), options());
            if (session.getUrl() == null || session.getUrl().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Billing could not be started");
            }
            return session.getUrl();
        } catch (StripeException ex) {
            log.warn("Stripe checkout failed status={}", ex.getStatusCode());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Billing could not be started");
        }
    }

    @Override
    public String createPortalSession(String customerId, String returnUrl) {
        com.stripe.param.billingportal.SessionCreateParams params =
                com.stripe.param.billingportal.SessionCreateParams.builder()
                        .setCustomer(customerId)
                        .setReturnUrl(returnUrl)
                        .build();
        try {
            com.stripe.model.billingportal.Session session =
                    com.stripe.model.billingportal.Session.create(params, options());
            if (session.getUrl() == null || session.getUrl().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The billing page could not be opened");
            }
            return session.getUrl();
        } catch (StripeException ex) {
            log.warn("Stripe portal failed status={}", ex.getStatusCode());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The billing page could not be opened");
        }
    }

    private RequestOptions options() {
        return RequestOptions.builder().setApiKey(properties.secretKey()).build();
    }
}

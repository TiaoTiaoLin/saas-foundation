# saas-foundation

Multi-tenant SaaS API for one organization per tenant. Products are the sample resource. Free includes the product catalog, members, and organization settings. Pro is a Stripe subscription and is what opens the audit log.

The browser app is [saas-foundation-web](https://github.com/TiaoTiaoLin/saas-foundation-web). Try it at <https://saas-foundation-web.linzehui233.workers.dev/>.

The API is deployed on Render. The first request after idle can take half a minute while the service wakes.

## Walk through the deployed app

1. Open <https://saas-foundation-web.linzehui233.workers.dev/> and create an organization.
2. Add a product. A member of that organization sees only that organization's products.
3. Invite a second account from Members. The person must already have an account, and they join only after they accept the email.
4. Upgrade to Pro from Billing. Checkout uses Stripe test mode. Card `4242 4242 4242 4242`, any future expiry, any CVC. The success page does not change the plan. The webhook does. Refresh billing after it arrives, then open Audit log.

## Run locally

Java 21, Docker, and Maven.

```bash
docker compose up -d
mvn spring-boot:run
```

The API listens on port 8080. Postgres is `localhost:5432/saas_foundation` (`saas_app` / `saas_app` for the app, `saas` / `saas` for Flyway). Redis is on port 6379. Kafka is on port 9092.

Start the web app from its own repo with `npm install` and `npm run dev`, then open <http://localhost:5173>. The dev server proxies `/api` to port 8080.

Email and billing stay quiet until these are set:

| Variable | Used for |
| --- | --- |
| `RESEND_API_KEY`, `RESEND_FROM` | Password reset and invite email |
| `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRO_PRICE_ID` | Checkout, the customer portal, and the webhook |
| `APP_BASE_URL` | Links in those emails and the Stripe return URLs. Locally this is `http://localhost:5173` |

The `local` profile turns on debug logging. It requires the Resend variables.

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

`mvn verify` runs the unit tests and the Testcontainers tests.

## Decisions

**Tenant isolation.** Every product row is owned by one tenant. On each request the tenant comes from the access token. The transaction sets PostgreSQL `app.current_tenant`, and row-level security on `products` allows a row only when `tenant_id` matches that setting. Tenant-scoped entities also pass through a Hibernate filter. A request cannot read another organization's products by changing an id.

**The webhook grants Pro.** Stripe redirects the browser to `/billing?checkout=success` before the subscription is stored. That page tells the owner to refresh. `checkout.session.completed` is the event that sets the plan to Pro when the session status is `complete`. `customer.subscription.updated` can arrive first, while the subscription is still `incomplete`, and that status stores Free. The handler keeps Stripe's `event.created` on the organization and ignores a snapshot older than the one already applied. A same-second `incomplete` event does not replace an active Pro subscription. A newer cancellation still does.

**The audit log is append-only.** `audit_events` records sign-in, password changes, invites, role changes, removals, logo changes, and plan changes. The application role can insert and select. It cannot update or delete a row. There is no foreign key to the user or the organization, so a row remains after the account is deleted. Owners on an active Pro plan can read the log. Past-due Pro keeps the plan and closes the log until the payment is current.

## Billing incident, October 19 2026

A paid test subscription stayed on Free and the billing page showed Incomplete.

Stripe created the subscription as `incomplete`, then sent `customer.subscription.updated` at 7:32:07 PM and `checkout.session.completed` at 7:32:08 PM. The subscription update was stored as Incomplete and Free. The checkout event is the one that would have set Pro. Its first delivery returned HTTP 500, so that transaction rolled back. Both events write the same versioned tenant row, and they arrived one second apart. The later retries timed out because the Render service was asleep, so they never committed either.

Resending `checkout.session.completed` while the service was awake set the organization to Pro. The event-time check above is what keeps a late delivery of the older `incomplete` snapshot from undoing that.

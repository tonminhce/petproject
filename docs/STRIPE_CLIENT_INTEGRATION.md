# Stripe client integration contract

This document states exactly what a browser client has to do with the payment API so that Stripe
accepts the confirmation. Every rule here was verified against the live Stripe test API
(`api.stripe.com`), not against the documentation alone.

## 1. Create the payment

```
POST /api/v1/payments
{ "orderId": "<uuid>", "amount": 98.00, "currency": "USD", "idempotencyKey": "<unique>" }
```

The response carries the payment state:

| field | meaning |
|-------|---------|
| `status` | `PENDING` when the provider still needs a confirmation |
| `clientSecret` | the Stripe client secret of the PaymentIntent |
| `returnUrl` | the url Stripe has to send the customer back to after a redirect |

## 2. Confirm the intent from the browser

When `status` is `PENDING` and `clientSecret` is present, the browser confirms the intent:

```javascript
const { error } = await stripe.confirmPayment({
  clientSecret: payment.clientSecret,
  confirmParams: { return_url: payment.returnUrl },
});
```

### Why the return url is mandatory

Stripe refuses the confirmation of a PaymentIntent that was created with
`automatic_payment_methods[enabled]=true` when the confirmation carries no `return_url`, because a
payment method enabled in the dashboard may redirect the customer off the page. The server side
rejection is:

> This PaymentIntent is configured to accept payment methods enabled in your Dashboard. Because some
> of these payment methods might redirect your customer off of your page, you must provide a
> `return_url`.

The measured behaviour of the live test API:

| creation | confirmation | result |
|----------|-------------|--------|
| `confirm=false`, no return url | confirm without return url | **400** |
| `confirm=false`, no return url | confirm with return url | **200 succeeded** |
| `confirm=false` and a return url on the creation | creation | **400** - Stripe rejects a return url on an intent that is not confirmed yet |
| `confirm=true` with a return url | creation | **200** |

Stripe does not keep the `return_url` on the PaymentIntent, so it has to travel with every
confirmation. That is why the payment API returns it in `returnUrl`.

## 3. Deployments that refuse redirect based payment methods

A deployment that offers card only payments and does not want a redirect at all can set
`SHOP_PAYMENT_STRIPE_ALLOW_REDIRECTS=false`. The provider then creates the intent with
`automatic_payment_methods[allow_redirects]=never`, and the confirmation succeeds without a return
url. The trade off is that a card that needs 3D Secure can no longer be authenticated, because 3D
Secure is a redirect.

## 4. The server learns about the payment through the webhook

The confirmation happens in the browser, so the payment service learns about the result from the
Stripe webhook:

```
POST /api/v1/webhooks/payments/stripe
```

The endpoint verifies the `Stripe-Signature` header with the signing secret
(`SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET`) and rejects an unsigned or badly signed body. The
supported events are `payment_intent.succeeded`, `payment_intent.payment_failed` and
`charge.refunded`.

The endpoint has to be reachable from the public internet, so a local deployment needs a tunnel.
The audit harness opens one, registers the endpoint against it and writes the issued signing secret
into the git ignored `.env`:

```bash
python3 production-audit/bin/harness stripe webhook-setup
```

The command prints the endpoint id, the public url and the fact that the payment service has to be
restarted so that it reads the new signing secret. The equivalent manual steps are:

```bash
cloudflared tunnel --url http://localhost:8083
```

and the Stripe webhook endpoint has to point at
`https://<tunnel host>/api/v1/webhooks/payments/stripe`.

## 5. Configuration

| variable | default | meaning |
|----------|---------|---------|
| `SHOP_PAYMENT_STRIPE_RETURN_URL` | `https://shop.example.com/checkout/complete` | the url Stripe returns the customer to |
| `SHOP_PAYMENT_STRIPE_ALLOW_REDIRECTS` | `true` | `false` turns off the redirect based payment methods |
| `SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET` | empty | the signing secret of the webhook endpoint |
| `SHOP_PAYMENT_STRIPE_SECRET_KEY` | empty | the Stripe secret key |
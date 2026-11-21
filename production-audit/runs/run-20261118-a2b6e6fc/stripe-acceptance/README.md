# Stripe payment acceptance — run-20261118-a2b6e6fc

## What this is

The audit task PAY-41 asks for the **real Stripe sandbox acceptance matrix** to be executed
with a genuine test-mode credential. No genuine credential exists in this environment, and
none can be minted without a human and a Stripe account. This run therefore executes the
acceptance against the **official Stripe mock server** that Stripe publishes for exactly
this purpose (`stripe/stripe-mock`), through the **real payment service of the platform**.

## The server under test

| item | value |
| --- | --- |
| server | official Stripe mock server |
| image | `stripe/stripe-mock:latest` |
| version | `0.206.0` (reported by the server itself in its start-up banner) |
| container | `stripe-mock-acceptance` |
| address on the platform network | `http://stripe-mock-acceptance:12111` |
| address on the host | `http://127.0.0.1:12111` |
| state directory | `/tmp/stripe-acceptance/stripe-mock` (nothing is written into the repository) |
| **is it api.stripe.com?** | **no** — this is the local mock, not the public Stripe API |

The mock's own server-side request log is stored verbatim in `acceptance.json`
(`stripe_server_log`) and in `server.json`.

## How the platform reaches the mock

```
payment-service:latest            (the real platform service, image built from this repository)
        |  SHOP_PAYMENT_STRIPE_API_BASE=http://host.docker.internal:12113
        v
wire recorder                     (harness/stripe/recorder.py - forwards the request bytes
        |                          unchanged to the mock and returns the mock's response
        |                          bytes unchanged; it only records them)
        v
stripe-mock-acceptance:12111       (official Stripe mock server, version 0.206.0)
```

* The base address of the Stripe API is taken from the environment through
  `SHOP_PAYMENT_STRIPE_API_BASE`, which `payment-service/src/main/resources/application.yml`
  maps onto `shop.payment.stripe.api-base`. `StripeProvider` builds a `StripeClient` with
  that base, so the platform talks to the mock through **the same code path it uses for
  Stripe** — the real `com.stripe` SDK, version 24.24.0, speaking the real Stripe wire
  protocol (`Stripe-Version: 2023-10-16`, form encoded bodies, `Idempotency-Key` headers).
* The provider is the real `StripeProvider`; there is no mock provider and no mocked SDK
  entry point anywhere in the run.
* The service ran as a container of the platform image `payment-service:latest` against the
  platform database (`paymentservice`) and the platform Keycloak realm `ecommerce`. The
  caller identity is the realm's own `order-service` client (token `azp=order-service`,
  realm role `SERVICE`), so the platform's own authorisation guard is exercised.
* The Stripe secret key and the webhook signing secret were generated for this run and
  passed to the container through the environment. Only their fingerprints are recorded
  (`acceptance.json` → `credential`). **No secret is stored in this repository.**

## The matrix and its verdicts

| # | case | verdict | what the platform did on the wire |
| --- | --- | --- | --- |
| 1 | `authorisation_and_capture` | **PROVEN** | `POST /v1/payment_intents` → 200, with `confirm=true`, `amount=24999`, `currency=usd`, `payment_method=pm_card_visa`, `metadata[payment_id]`, `metadata[idempotency_key]` and an `Idempotency-Key` header; the provider intent id and the client secret were stored on the payment |
| 2 | `capture_webhook_signature_verified` | **PROVEN** | no Stripe call — the capture arrives on the signed provider event: signed event → 200 and the payment became `CAPTURED`; forged signature → 401 `PAY-5005 Webhook signature is invalid` and the payment did not move |
| 3 | `refund` | **PROVEN** | `GET /v1/payment_intents/{id}` → 200 then `POST /v1/refunds` → 200; the payment reached `REFUNDED` with `refundedAmount` 249.99 |
| 4 | `refund_idempotent_replay` | **PROVEN** | replaying the same refund reference returned the same refund and issued **zero** further calls to the provider, so no money can move twice |
| 5 | `partial_refund` | **PROVEN** | `GET /v1/payment_intents/{id}` → 200 then `POST /v1/refunds` (amount 5000) → 200; the payment stayed `PARTIALLY_REFUNDED` with `refundedAmount` 50.00 |
| 6 | `second_partial_refund` | **PROVEN** | `GET /v1/payment_intents/{id}` → 200 then `POST /v1/refunds` (amount 2500) → 200; the refunded amount accumulated to 75.00 and the status stayed `PARTIALLY_REFUNDED` |
| 7 | `declined_card_refusal` | **NOT PROVEN** | the platform sent the Stripe declined test payment method (`pm_card_chargeDeclined`) and the mock answered **200** with `status: requires_payment_method` — the official mock never declines a card. The refusal of a failed payment **is** proven: a signed `payment_intent.payment_failed` event moved the payment to `FAILED` and the next capture was refused with 409 `PAY-5004` |
| 8 | `currency_mismatch_refusal` | **PROVEN** | a refund that asked for `VND` on a `USD` payment was refused with 400 `PAY-5010 Payment currency USD does not match the expected currency VND` and **no** refund call reached the provider |
| + | `provider_currency_mismatch_refusal` (supplementary) | **PROVEN** | the provider guard refused the refund of a `VND` payment with 502 `PAY-5008 … currency-mismatch` because the intent currency read back from the provider differs from the payment currency |

Summary: **8 declared cases, 7 proven, 1 not proven, 0 failed, 1 supplementary case proven.**
Every case is stored in `acceptance.json` with the real request (method, path, headers,
body) and the real response (status, headers, body) of each Stripe call, plus the platform's
own request/response exchange.

## What is proven and what is not

**Proven against the official Stripe mock, through the real payment service:**

* the platform builds and sends a correct Stripe authorisation request, with the idempotency
  key and the payment metadata the provider needs to reconcile the payment later;
* the provider intent reference and the client secret are stored on the payment, and the
  payment is only captured from a **signed** provider event — a forged `Stripe-Signature`
  is rejected with 401 `PAY-5005`;
* refunds are issued against the provider intent, the refunded amount is accumulated, two
  partial refunds accumulate correctly, and the replay of a refund reference cannot move
  money twice;
* a currency mismatch is refused at the platform boundary and at the provider boundary;
* the payment service speaks the real Stripe wire protocol: form encoded bodies, the
  `Stripe-Version` header, `Idempotency-Key`, the `Authorization: Bearer` credential, and
  the real Stripe resource shapes.

**Not proven, and why:**

* **the refusal of a declined card on the provider path.** `stripe-mock` states in its own
  documentation that it "does not attempt to reproduce the behaviour of the real Stripe
  API" and that "testing for specific responses and errors is currently not supported — it
  will return a success response instead of the desired error response". The
  `CardException` branch of `StripeProvider.capture` therefore cannot be reached against
  the mock. It needs a genuine test-mode key with `pm_card_chargeDeclined` or card
  `4000000000000002`.
* **the webhook delivery itself.** The mock does not deliver webhooks, so the harness
  played the role of Stripe for the delivery. The signature format (`t=…,v1=…`,
  HMAC-SHA256 over `timestamp + "." + payload`), the verification code path
  (`stripe-java Webhook.constructEvent`), the event mapping and the state transition are
  the real ones; the signing secret is local because no Stripe account issued one.
* **a Stripe request id on the provider answer.** The real Stripe API answers with a
  `Request-Id` header; the mock does not, so the provider logs carry no request id.
* **the currency of the matrix run.** The main matrix ran in `USD`. The mock is stateless:
  it answers a retrieval of a payment intent with its own fixture (`amount: 1099`,
  `currency: usd`) instead of the intent that was created, while it does reflect the amount
  and the currency of a *creation* or a *refund* request. The refund path compares the
  payment currency with the intent currency read back from the provider, so against the
  mock only a `USD` payment can be refunded. The supplementary case
  `provider_currency_mismatch_refusal` shows the other side of that guard on a `VND`
  payment. Against `api.stripe.com` the intent carries the currency that was created, so
  the same code path accepts the currency of the payment.

**Still required for the last mile — a genuine Stripe test-mode key against
`api.stripe.com`:**

1. a real `sk_test_…` key from a Stripe test account and the matching `whsec_…` webhook
   signing secret;
2. re-run the same matrix with `SHOP_PAYMENT_STRIPE_API_BASE` pointing at
   `https://api.stripe.com` and the real credentials;
3. the decline case then becomes provable, and the answers come from Stripe itself instead
   of the mock.

## The gate before and after

| | GATE-6 `PAYMENT_CORRECTNESS` |
| --- | --- |
| before | `PASS` as soon as the maven `payment` suite evidence was fresh (299 tests, all of them against mocked SDK entry points) — a false pass, because no genuine Stripe interaction had ever happened. In this working tree the suite evidence was stale, so the gate read `PENDING` |
| after | `BLOCKED` — "the payment acceptance is NOT verified against the real Stripe: it ran against the official Stripe mock 0.206.0 (7 of 8 cases proven; outstanding: declined_card_refusal); a genuine test-mode key against api.stripe.com is still required" |

`production-audit/harness/verification/gates.py` now derives GATE-6 from the payment suite
**and** from this acceptance evidence. The gate reports `PASS` only when the acceptance ran
against the public Stripe API with a genuine test-mode credential and every case was
proven; a public base address on its own is not enough, and a run against the mock is
reported as not verified with the reason spelled out.

## Reproducing

```
cd production-audit
python3 -m harness.cli.main stripe acceptance
```

The command starts the official mock, the platform dependencies, the payment service
container and the wire recorder, runs the matrix, writes `acceptance.json`, `server.json`
and `cli-result.json` into this directory and exits with code 2 (`BLOCKED`) because the
acceptance is not verified against `api.stripe.com`.

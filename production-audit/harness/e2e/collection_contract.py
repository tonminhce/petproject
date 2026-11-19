from __future__ import annotations

import json
from pathlib import Path
from typing import Any

STRIPE_WEBHOOK_SECRET = "whsec_local_e2e_only"


def _payment_folder(collection: dict[str, Any]) -> dict[str, Any] | None:
    for item in collection.get("item", []):
        for child in item.get("item", []):
            if child.get("name", "").startswith("6. Payment"):
                return child
        if item.get("name", "").startswith("6. Payment"):
            return item
    return None


def _capture_item(folder: dict[str, Any]) -> dict[str, Any] | None:
    for child in folder.get("item", []):
        if child.get("name", "").startswith("6.5"):
            return child
    return None


def _query_item(folder: dict[str, Any]) -> dict[str, Any] | None:
    for child in folder.get("item", []):
        name = child.get("name", "")
        if name.startswith("6.6 Query") or name.startswith("6.7 Query"):
            return child
    return None


def _webhook_request() -> dict[str, Any]:
    body = {
        "id": "evt_{{paymentId}}",
        "object": "event",
        "api_version": "2024-06-20",
        "type": "payment_intent.succeeded",
        "data": {
            "object": {
                "id": "pi_{{paymentId}}",
                "object": "payment_intent",
                "amount": 249900,
                "currency": "usd",
                "status": "succeeded",
                "livemode": False,
                "metadata": {"payment_id": "{{paymentId}}"},
            }
        },
    }
    script = [
        "const ts = Math.floor(Date.now() / 1000);",
        "const raw = pm.variables.replaceIn(pm.request.body.raw);",
        "const secret = pm.variables.get('stripeWebhookSecret');",
        "const signature = 't=' + ts + ',v1=' + CryptoJS.HmacSHA256(ts + '.' + raw, secret).toString(CryptoJS.enc.Hex);",
        "pm.collectionVariables.set('stripeWebhookSignature', signature);",
    ]
    test = [
        "pm.test('Provider webhook accepted', function () { pm.expect(pm.response.code).to.eql(200); });",
        "pm.test('Provider webhook envelope is a success', function () {",
        "    const res = pm.response.json();",
        "    pm.expect(res.success).to.be.true;",
        "});",
    ]
    return {
        "name": "6.6 Provider Confirms the Payment (Stripe Webhook)",
        "request": {
            "method": "POST",
            "header": [
                {"key": "Content-Type", "value": "application/json"},
                {"key": "Stripe-Signature", "value": "{{stripeWebhookSignature}}"},
            ],
            "body": {"mode": "raw", "raw": json.dumps(body, indent=2)},
            "url": {
                "raw": "{{paymentUrl}}/api/v1/webhooks/payments/stripe",
                "host": ["{{paymentUrl}}"],
                "path": ["api", "v1", "webhooks", "payments", "stripe"],
            },
            "description": "A Stripe intent that was created without confirmation is confirmed by the client, and the provider then notifies the platform. The platform must only mark the payment captured from a signed provider event.",
        },
        "event": [
            {"listen": "prerequest", "script": {"type": "text/javascript", "exec": script}},
            {"listen": "test", "script": {"type": "text/javascript", "exec": test}},
        ],
        "response": [],
    }


CAPTURE_TEST_SCRIPT = [
    "pm.test('Payment capture accepted', () => pm.expect(pm.response.code).to.be.oneOf([200, 201]));",
    "const res = pm.response.json();",
    "pm.test('A provider that needs client confirmation leaves the payment pending', () => {",
    "    pm.expect(res.data.status).to.eql('PENDING');",
    "});",
    "pm.test('The client secret for the confirmation is returned', () => {",
    "    pm.expect(res.data.clientSecret).to.be.a('string');",
    "    pm.expect(res.data.clientSecret.length).to.be.above(0);",
    "});",
]

QUERY_TEST_SCRIPT = [
    "pm.test('Payment retrieved and status is CAPTURED', () => {",
    "    const res = pm.response.json();",
    "    pm.expect(res.data.status).to.eql('CAPTURED');",
    "});",
]


def apply_contract(collection_path: Path, secret: str = STRIPE_WEBHOOK_SECRET) -> dict[str, Any]:
    collection = json.loads(collection_path.read_text(encoding="utf-8"))
    folder = _payment_folder(collection)
    if folder is None:
        raise ValueError(f"no payment folder in {collection_path}")

    capture = _capture_item(folder)
    query = _query_item(folder)
    if capture is None or query is None:
        raise ValueError(f"missing capture or query item in {collection_path}")

    for event in capture.get("event", []):
        if event.get("listen") == "test":
            event["script"]["exec"] = list(CAPTURE_TEST_SCRIPT)
    for event in query.get("event", []):
        if event.get("listen") == "test":
            event["script"]["exec"] = list(QUERY_TEST_SCRIPT)

    existing = [child for child in folder.get("item", []) if child.get("name", "").startswith("6.6 Provider Confirms")]
    for child in existing:
        folder["item"].remove(child)

    webhook = _webhook_request()
    webhook["request"]["header"][1]["value"] = "{{stripeWebhookSignature}}"
    query_index = folder["item"].index(query)
    folder["item"].insert(query_index, webhook)

    collection["variable"] = collection.get("variable", [])
    for variable in collection["variable"]:
        if variable.get("key") == "stripeWebhookSecret":
            variable["value"] = secret
            break
    else:
        collection["variable"].append({"key": "stripeWebhookSecret", "value": secret})

    collection_path.write_text(json.dumps(collection, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return {
        "collection": collection_path.name,
        "webhook_scenario_added": True,
        "capture_assertion": "PENDING with a client secret",
        "query_assertion": "CAPTURED after the signed provider webhook",
    }

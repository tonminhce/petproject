from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

STRIPE_API = "https://api.stripe.com"
STRIPE_VERSION = "2024-06-20"
LIVE_KEY_PREFIXES = ("sk_live_", "rk_live_")
TEST_KEY_PREFIXES = ("sk_test_", "rk_test_")
SECRET_KEY_PATTERN = re.compile(r"^[sr]k_(live|test)_[A-Za-z0-9]{20,}$")
REQUEST_TIMEOUT_SECONDS = 30
ENV_SECRET_KEYS = ("STRIPE_SECRET_KEY", "SHOP_PAYMENT_STRIPE_SECRET_KEY")
ENV_WEBHOOK_SECRETS = ("STRIPE_WEBHOOK_SECRET", "SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET")
ENV_API_BASE = ("STRIPE_API_BASE", "SHOP_PAYMENT_STRIPE_API_BASE")
ENV_PROVIDER = ("SHOP_PAYMENT_PROVIDER",)
ENV_SANDBOX = ("SHOP_PAYMENT_STRIPE_SANDBOX", "STRIPE_SANDBOX")
ENV_PAYMENT_METHOD = ("SHOP_PAYMENT_STRIPE_SANDBOX_PAYMENT_METHOD", "STRIPE_SANDBOX_PAYMENT_METHOD")


class StripeConfigurationError(Exception):
    pass


class StripeApiError(Exception):
    pass


def _first_env(names: tuple[str, ...], env: dict | None = None) -> str:
    source = env if env is not None else dict(os.environ)
    for name in names:
        value = str(source.get(name, "")).strip()
        if value:
            return value
    return ""


def _dotenv_values(path: str) -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        with open(path, "r", encoding="utf-8") as handle:
            for line in handle:
                stripped = line.strip()
                if not stripped or stripped.startswith("#") or "=" not in stripped:
                    continue
                key, value = stripped.split("=", 1)
                values[key.strip()] = value.strip()
    except OSError:
        return values
    return values


def load_credentials(env: dict | None = None) -> dict[str, str]:
    source = dict(env if env is not None else os.environ)
    repository = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
    dotenv = _dotenv_values(os.path.join(repository, ".env"))
    for name, value in dotenv.items():
        if not str(source.get(name, "")).strip() and value:
            source[name] = value
    secret_key = _first_env(ENV_SECRET_KEYS, source)
    webhook_secret = _first_env(ENV_WEBHOOK_SECRETS, source)
    api_base = _first_env(ENV_API_BASE, source) or STRIPE_API
    provider = _first_env(ENV_PROVIDER, source) or "stripe"
    sandbox = _first_env(ENV_SANDBOX, source).lower() in {"true", "1", "yes", "on"}
    payment_method = _first_env(ENV_PAYMENT_METHOD, source) or "pm_card_visa"
    return {
        "secret_key": secret_key,
        "webhook_secret": webhook_secret,
        "api_base": api_base,
        "provider": provider,
        "sandbox": sandbox,
        "payment_method": payment_method,
    }


WEBHOOK_SECRET_PATTERN = re.compile(r"^whsec_[A-Za-z0-9]{32,}$")


def classify_webhook_secret(secret: str) -> str:
    if not secret:
        return "missing"
    if WEBHOOK_SECRET_PATTERN.match(secret):
        return "genuine"
    return "placeholder"


def classify_key(secret_key: str) -> str:
    if not secret_key:
        return "missing"
    if secret_key.startswith(LIVE_KEY_PREFIXES):
        return "live"
    if secret_key.startswith(TEST_KEY_PREFIXES):
        if not SECRET_KEY_PATTERN.match(secret_key):
            return "placeholder"
        return "test"
    return "unrecognised"


def _request(
    method: str,
    path: str,
    credentials: dict[str, str],
    payload: dict[str, Any] | None = None,
    idempotency_key: str = "",
) -> tuple[int, dict[str, Any], dict[str, str]]:
    url = credentials["api_base"].rstrip("/") + path
    body = urllib.parse.urlencode(payload or {}).encode("utf-8")
    request = urllib.request.Request(url, data=body if body else None, method=method)
    request.add_header("Authorization", "Bearer " + credentials["secret_key"])
    request.add_header("Stripe-Version", STRIPE_VERSION)
    if idempotency_key:
        request.add_header("Idempotency-Key", idempotency_key)
    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            raw = response.read().decode("utf-8")
            headers = {key.lower(): value for key, value in response.headers.items()}
            return response.status, (json.loads(raw) if raw else {}), headers
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        headers = {key.lower(): value for key, value in error.headers.items()}
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            parsed = {"error": {"message": raw[:400]}}
        return error.code, parsed, headers


def verify_configuration(env: dict | None = None, credentials: dict[str, str] | None = None) -> dict[str, Any]:
    resolved = credentials or load_credentials(env)
    mode = classify_key(resolved["secret_key"])
    findings: list[dict[str, Any]] = []
    if not resolved["secret_key"]:
        findings.append({"check": "secret_key_present", "status": "FAIL", "detail": "no Stripe secret key is configured"})
    elif mode == "live":
        findings.append({"check": "secret_key_mode", "status": "FAIL", "detail": "the configured Stripe key is a live key"})
    elif mode == "test":
        findings.append({"check": "secret_key_mode", "status": "PASS", "detail": "the configured Stripe key is a test key"})
    else:
        findings.append({"check": "secret_key_mode", "status": "FAIL", "detail": "the configured Stripe key has an unrecognised prefix"})
    webhook_mode = classify_webhook_secret(resolved["webhook_secret"])
    if webhook_mode == "genuine":
        findings.append({"check": "webhook_secret", "status": "PASS", "detail": "a genuine Stripe webhook signing secret is configured"})
    elif webhook_mode == "placeholder":
        findings.append({"check": "webhook_secret", "status": "FAIL", "detail": "the configured webhook secret is a local placeholder, not a genuine Stripe signing secret"})
    else:
        findings.append({"check": "webhook_secret", "status": "FAIL", "detail": "no webhook signing secret is configured"})
    findings.append(
        {
            "check": "provider",
            "status": "PASS" if resolved["provider"] == "stripe" else "FAIL",
            "detail": "the active payment provider is " + resolved["provider"],
        }
    )
    findings.append(
        {
            "check": "sandbox_flag",
            "status": "PASS" if resolved["sandbox"] else "FAIL",
            "detail": "the sandbox flag is " + str(resolved["sandbox"]).lower(),
        }
    )
    api_status = "BLOCKED"
    api_detail = "no usable credential to probe the Stripe API"
    request_id = ""
    if mode == "test":
        code, payload, headers = _request("GET", "/v1/account", resolved)
        request_id = headers.get("request-id", "")
        if code == 200:
            api_status = "PASS"
            api_detail = "the Stripe API accepted the credential and returned account " + str(payload.get("id", ""))
        else:
            api_status = "FAIL"
            api_detail = "the Stripe API rejected the credential: " + str((payload.get("error") or {}).get("message", ""))[:200]
    findings.append({"check": "api_authentication", "status": api_status, "detail": api_detail})
    failed = [item["check"] for item in findings if item["status"] == "FAIL"]
    blocked = [item["check"] for item in findings if item["status"] == "BLOCKED"]
    status = "FAIL" if failed else ("BLOCKED" if blocked else "PASS")
    return {
        "status": status,
        "failed": failed,
        "blocked": blocked,
        "findings": findings,
        "key_mode": mode,
        "request_id": request_id,
        "api_base": resolved["api_base"],
    }


def test_payment(env: dict | None = None, amount: int = 100000, currency: str = "vnd") -> dict[str, Any]:
    credentials = load_credentials(env)
    mode = classify_key(credentials["secret_key"])
    if mode != "test":
        return {
            "status": "BLOCKED",
            "reason": "a real Stripe test-mode secret key is required and the configured key is " + mode,
        }
    idempotency_key = "harness-" + str(int(time.time()))
    created_code, created, created_headers = _request(
        "POST",
        "/v1/payment_intents",
        credentials,
        {
            "amount": str(amount),
            "currency": currency,
            "payment_method_types[]": "card",
            "description": "petproject production audit harness",
            "metadata[harness_run]": "stripe-sandbox-e2e",
        },
        idempotency_key=idempotency_key,
    )
    if created_code != 200:
        return {
            "status": "FAIL",
            "stage": "create_payment_intent",
            "http_status": created_code,
            "error": (created.get("error") or {}).get("message", ""),
            "request_id": created_headers.get("request-id", ""),
        }
    intent = created
    intent_id = str(intent.get("id", ""))
    confirm_code, confirmed, confirm_headers = _request(
        "POST",
        "/v1/payment_intents/" + intent_id + "/confirm",
        credentials,
        {
            "payment_method": credentials["payment_method"],
            "return_url": "https://example.invalid/harness/return",
        },
    )
    final = confirmed if confirm_code == 200 else intent
    return {
        "status": "PASS" if confirm_code == 200 else "FAIL",
        "payment_intent_id": intent_id,
        "idempotency_key": idempotency_key,
        "amount": final.get("amount"),
        "currency": final.get("currency"),
        "state": final.get("status"),
        "payment_method": credentials["payment_method"],
        "create_request_id": created_headers.get("request-id", ""),
        "confirm_request_id": confirm_headers.get("request-id", ""),
        "confirm_http_status": confirm_code,
        "client_secret_present": bool(intent.get("client_secret")),
        "environment": "stripe-test",
    }


def sign_payload(payload: bytes, webhook_secret: str, timestamp: int) -> str:
    signed = str(timestamp).encode("utf-8") + b"." + payload
    digest = hmac.new(webhook_secret.encode("utf-8"), signed, hashlib.sha256).hexdigest()
    return "t=" + str(timestamp) + ",v1=" + digest


def verify_webhook_signature(payload: bytes, signature_header: str, webhook_secret: str, tolerance_seconds: int = 300) -> dict[str, Any]:
    parts: dict[str, str] = {}
    for element in signature_header.split(","):
        if "=" in element:
            name, value = element.split("=", 1)
            parts[name.strip()] = value.strip()
    timestamp = parts.get("t", "")
    received = parts.get("v1", "")
    if not timestamp or not received:
        return {"valid": False, "reason": "the signature header is missing a timestamp or a v1 signature"}
    try:
        age = abs(int(time.time()) - int(timestamp))
    except ValueError:
        return {"valid": False, "reason": "the signature timestamp is not an integer"}
    if age > tolerance_seconds:
        return {"valid": False, "reason": "the signature timestamp is outside the tolerance window", "age_seconds": age}
    expected = sign_payload(payload, webhook_secret, int(timestamp)).split("v1=")[1]
    valid = hmac.compare_digest(expected, received)
    return {
        "valid": valid,
        "reason": "the signature matches" if valid else "the signature does not match the payload",
        "age_seconds": age,
    }


def test_webhooks(env: dict | None = None) -> dict[str, Any]:
    credentials = load_credentials(env)
    if not credentials["webhook_secret"]:
        return {"status": "BLOCKED", "reason": "no Stripe webhook signing secret is configured"}
    if classify_webhook_secret(credentials["webhook_secret"]) != "genuine":
        return {
            "status": "BLOCKED",
            "reason": "the configured webhook secret is a local placeholder, so the signature check "
                      "would only prove that a self signed payload round trips",
        }
    payload = json.dumps({"id": "evt_harness_probe", "type": "payment_intent.succeeded"}).encode("utf-8")
    timestamp = int(time.time())
    valid_signature = sign_payload(payload, credentials["webhook_secret"], timestamp)
    accepted = verify_webhook_signature(payload, valid_signature, credentials["webhook_secret"])
    tampered = verify_webhook_signature(payload + b"tampered", valid_signature, credentials["webhook_secret"])
    expired = verify_webhook_signature(
        payload,
        sign_payload(payload, credentials["webhook_secret"], timestamp - 4000),
        credentials["webhook_secret"],
    )
    status = "PASS" if accepted.get("valid") and not tampered.get("valid") and not expired.get("valid") else "FAIL"
    return {
        "status": status,
        "valid_signature_accepted": accepted.get("valid"),
        "tampered_payload_rejected": not tampered.get("valid"),
        "expired_signature_rejected": not expired.get("valid"),
        "details": {"accepted": accepted, "tampered": tampered, "expired": expired},
    }


def reconcile(env: dict | None = None, limit: int = 25) -> dict[str, Any]:
    credentials = load_credentials(env)
    if classify_key(credentials["secret_key"]) != "test":
        return {"status": "BLOCKED", "reason": "a real Stripe test-mode secret key is required to reconcile"}
    code, payload, headers = _request("GET", "/v1/payment_intents?limit=" + str(limit), credentials)
    if code != 200:
        return {
            "status": "FAIL",
            "http_status": code,
            "error": (payload.get("error") or {}).get("message", ""),
            "request_id": headers.get("request-id", ""),
        }
    intents = payload.get("data") or []
    return {
        "status": "PASS",
        "payment_intents_returned": len(intents),
        "request_id": headers.get("request-id", ""),
        "intents": [
            {
                "id": item.get("id"),
                "amount": item.get("amount"),
                "currency": item.get("currency"),
                "status": item.get("status"),
                "created": item.get("created"),
            }
            for item in intents
        ],
    }

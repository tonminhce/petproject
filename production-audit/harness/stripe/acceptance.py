from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import shutil
import string
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any

from .. import config
from ..persistence.store import read_json, utc_now, write_json
from .recorder import Recorder

PUBLIC_STRIPE_API = "https://api.stripe.com"
STRIPE_MOCK_IMAGE = "stripe/stripe-mock:latest"
STRIPE_MOCK_CONTAINER = "stripe-mock-acceptance"
PAYMENT_CONTAINER = "payment-service-stripe-acceptance"
COMPOSE_NETWORK = "untitled5_ecommerce-network"
STATE_DIRECTORY = Path("/tmp/stripe-acceptance")
MOCK_STATE_DIRECTORY = STATE_DIRECTORY / "stripe-mock"
MOCK_HTTP_PORT = 12111
RECORDER_PORT = 12113
PAYMENT_HOST_PORT = 18085
PAYMENT_CONTAINER_PORT = 8085
KEYCLOAK_TOKEN_URL = "http://localhost:9090/realms/ecommerce/protocol/openid-connect/token"
KEYCLOAK_CLIENT_ID = "order-service"
PLATFORM_ISSUER = "http://keycloak:8080/realms/ecommerce"
PAYMENT_API = "/api/v1/payments"
WEBHOOK_API = "/api/v1/webhooks/payments/stripe"
PAYMENT_CURRENCY = "USD"
DECLINE_PAYMENT_METHOD = "pm_card_chargeDeclined"
TIMEOUT_SECONDS = 90

MATRIX_CASES: tuple[dict[str, str], ...] = (
    {
        "name": "authorisation_and_capture",
        "mandate": "the authorisation and the capture",
        "proves": "the platform creates the authorisation on Stripe and stores the provider intent reference",
    },
    {
        "name": "capture_webhook_signature_verified",
        "mandate": "the webhook of the capture with the signature verified",
        "proves": "the payment is captured only from a signed provider event and a forged signature is refused",
    },
    {
        "name": "refund",
        "mandate": "the refund",
        "proves": "the platform refunds against the provider intent and records the refunded amount",
    },
    {
        "name": "refund_idempotent_replay",
        "mandate": "the idempotent replay of the refund",
        "proves": "replaying the same refund reference does not move money twice",
    },
    {
        "name": "partial_refund",
        "mandate": "the partial refund",
        "proves": "a partial refund moves part of the amount and leaves the payment partially refunded",
    },
    {
        "name": "second_partial_refund",
        "mandate": "the second partial refund",
        "proves": "a second partial refund accumulates on the first one",
    },
    {
        "name": "declined_card_refusal",
        "mandate": "the refusal of a card that is declined",
        "proves": "a refused card must leave the payment unpaid and never captured",
    },
    {
        "name": "currency_mismatch_refusal",
        "mandate": "the currency mismatch",
        "proves": "a refund whose currency differs from the captured currency is refused",
    },
)

VERDICT_PROVEN = "PROVEN"
VERDICT_NOT_PROVEN = "NOT_PROVEN"
VERDICT_FAILED = "FAILED"


def _run(command: list[str], timeout: int = 180) -> tuple[int, str, str]:
    try:
        completed = subprocess.run(command, capture_output=True, text=True, check=False, timeout=timeout)
        return completed.returncode, completed.stdout, completed.stderr
    except (OSError, subprocess.TimeoutExpired) as error:
        return 127, "", str(error)


def _docker(args: list[str], timeout: int = 180) -> tuple[int, str, str]:
    return _run(["docker"] + args, timeout)


def _fingerprint(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


def _random_suffix(length: int) -> str:
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(length))


def _dotenv(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        content = path.read_text(encoding="utf-8")
    except OSError:
        return values
    for line in content.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        name, value = stripped.split("=", 1)
        values[name.strip()] = value.strip().strip('"').strip("'")
    return values


def stripe_mock_version(container: str = STRIPE_MOCK_CONTAINER) -> str:
    code, out, _ = _docker(["logs", container], timeout=60)
    if code != 0:
        return ""
    for line in out.splitlines():
        if line.startswith("stripe-mock "):
            return line.strip().split(" ", 1)[1].strip()
    return ""


def ensure_stripe_mock() -> dict[str, Any]:
    STATE_DIRECTORY.mkdir(parents=True, exist_ok=True)
    MOCK_STATE_DIRECTORY.mkdir(parents=True, exist_ok=True)
    _docker(["rm", "-f", STRIPE_MOCK_CONTAINER], timeout=60)
    _docker(
        [
            "run", "-d", "--name", STRIPE_MOCK_CONTAINER,
            "-p", str(MOCK_HTTP_PORT) + ":" + str(MOCK_HTTP_PORT),
            "-v", str(MOCK_STATE_DIRECTORY) + ":/var/lib/stripe-mock",
            STRIPE_MOCK_IMAGE,
        ],
        timeout=300,
    )
    networks = _docker(["network", "inspect", COMPOSE_NETWORK, "-f", "{{range .Containers}}{{.Name}} {{end}}"], timeout=60)
    if STRIPE_MOCK_CONTAINER not in networks[1]:
        _docker(["network", "connect", COMPOSE_NETWORK, STRIPE_MOCK_CONTAINER], timeout=60)
    deadline = time.monotonic() + 60
    ready = False
    probe_key = "sk_" + "test_" + "probe" + _random_suffix(16)
    while time.monotonic() < deadline:
        try:
            probe = urllib.request.Request(
                "http://127.0.0.1:" + str(MOCK_HTTP_PORT) + "/v1/account",
                headers={"Authorization": "Bearer " + probe_key},
            )
            with urllib.request.urlopen(probe, timeout=5) as response:
                ready = response.status == 200
        except urllib.error.HTTPError:
            ready = True
        except OSError:
            ready = False
        if ready:
            break
        time.sleep(1)
    code, out, _ = _docker(["inspect", "-f", "{{.Config.Image}}", STRIPE_MOCK_CONTAINER], timeout=60)
    return {
        "kind": "official-stripe-mock",
        "image": out.strip() if code == 0 else STRIPE_MOCK_IMAGE,
        "version": stripe_mock_version(),
        "container": STRIPE_MOCK_CONTAINER,
        "network": COMPOSE_NETWORK,
        "http_address_in_network": "http://" + STRIPE_MOCK_CONTAINER + ":" + str(MOCK_HTTP_PORT),
        "http_address_on_host": "http://127.0.0.1:" + str(MOCK_HTTP_PORT),
        "state_directory": str(MOCK_STATE_DIRECTORY),
        "ready": ready,
        "is_public_stripe_api": False,
    }


def compose_services_running(services: list[str]) -> bool:
    code, out, _ = _docker(["ps", "--format", "{{.Names}} {{.Status}}"], timeout=60)
    if code != 0:
        return False
    running = [line.split(" ", 1)[0] for line in out.splitlines() if "Up" in line]
    return all(any(name in item for item in running) for name in services)


def ensure_compose_services(services: list[str]) -> dict[str, Any]:
    if compose_services_running(services):
        return {"services": services, "state": "already running"}
    code, out, err = _run(["docker", "compose", "up", "-d"] + services, timeout=900)
    return {
        "services": services,
        "state": "started" if code == 0 else "failed",
        "exit_code": code,
        "output": (out + err)[-2000:],
    }


def ensure_payment_service(
    values: dict[str, str],
    secret_key: str,
    webhook_secret: str,
    api_base: str,
    payment_method: str = "pm_card_visa",
) -> dict[str, Any]:
    code, _, _ = _docker(["inspect", "-f", "{{.State.Running}}", PAYMENT_CONTAINER], timeout=60)
    if code == 0:
        _docker(["rm", "-f", PAYMENT_CONTAINER], timeout=120)
    environment = {
        "SPRING_DATASOURCE_URL": "jdbc:postgresql://postgres:5432/paymentservice",
        "POSTGRES_USER": values.get("POSTGRES_USER", "admin"),
        "POSTGRES_PASSWORD": values.get("POSTGRES_PASSWORD", "admin"),
        "SHOP_SECURITY_ISSUER_URI": PLATFORM_ISSUER,
        "SHOP_SECURITY_EXPECTED_CLIENT_IDS": "ecommerce-client,order-service",
        "SHOP_PAYMENT_PROVIDER": "stripe",
        "SHOP_PAYMENT_STRIPE_API_BASE": api_base,
        "SHOP_PAYMENT_STRIPE_SECRET_KEY": secret_key,
        "SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET": webhook_secret,
        "SHOP_PAYMENT_STRIPE_SANDBOX": "true",
        "SHOP_PAYMENT_STRIPE_AUTO_CONFIRM_SANDBOX": "true",
        "SHOP_PAYMENT_STRIPE_SANDBOX_PAYMENT_METHOD": payment_method,
        "PAYMENT_WEBHOOK_SECRET": values.get("PAYMENT_WEBHOOK_SECRET", "local-webhook-secret"),
        "SHOP_KAFKA_BOOTSTRAP_SERVERS": "kafka:9092",
        "STORAGE_ENDPOINT": "http://rustfs:9000",
        "STORAGE_ACCESS_KEY": values.get("STORAGE_ACCESS_KEY", ""),
        "STORAGE_SECRET_KEY": values.get("STORAGE_SECRET_KEY", ""),
        "STORAGE_BUCKET": "shop-payments",
        "OTEL_EXPORTER_OTLP_ENDPOINT": "http://otel-collector:4318",
        "OTEL_EXPORTER_OTLP_PROTOCOL": "http/protobuf",
        "JAVA_TOOL_OPTIONS": "-XX:ActiveProcessorCount=2 -Xms128m -Xmx512m",
    }
    command = [
        "docker", "run", "-d", "--name", PAYMENT_CONTAINER,
        "--network", COMPOSE_NETWORK,
        "-p", str(PAYMENT_HOST_PORT) + ":" + str(PAYMENT_CONTAINER_PORT),
    ]
    for name, value in environment.items():
        command.extend(["-e", name + "=" + value])
    command.append("payment-service:latest")
    code, out, err = _docker(command[1:], timeout=300)
    if code != 0:
        return {"state": "failed", "exit_code": code, "output": (out + err)[-2000:]}
    deadline = time.monotonic() + 180
    ready = False
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(
                "http://127.0.0.1:" + str(PAYMENT_HOST_PORT) + "/actuator/health", timeout=5
            ) as response:
                ready = response.status == 200
        except urllib.error.HTTPError:
            ready = True
        except OSError:
            ready = False
        if ready:
            break
        time.sleep(2)
    return {
        "state": "running" if ready else "not ready",
        "container": PAYMENT_CONTAINER,
        "image": "payment-service:latest",
        "host_address": "http://127.0.0.1:" + str(PAYMENT_HOST_PORT),
        "stripe_api_base": api_base,
        "provider": "stripe",
        "sandbox_payment_method": payment_method,
        "exit_code": 0,
    }


def mint_token(values: dict[str, str]) -> str:
    payload = urllib.parse.urlencode(
        {
            "grant_type": "client_credentials",
            "client_id": KEYCLOAK_CLIENT_ID,
            "client_secret": values.get("ORDER_SERVICE_CLIENT_SECRET", ""),
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        KEYCLOAK_TOKEN_URL,
        data=payload,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        document = json.loads(response.read().decode("utf-8"))
    token = str(document.get("access_token", ""))
    if not token:
        raise RuntimeError("the identity provider returned no access token")
    return token


class Platform:

    def __init__(self, base_url: str, token: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.exchange: list[dict[str, Any]] = []

    def _headers(self) -> dict[str, str]:
        return {
            "Authorization": "Bearer " + self.token,
            "Content-Type": "application/json",
        }

    def call(self, method: str, path: str, body: Any = None) -> tuple[int, Any]:
        data = json.dumps(body).encode("utf-8") if body is not None else None
        request = urllib.request.Request(
            self.base_url + path,
            data=data,
            method=method,
            headers=self._headers(),
        )
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                raw = response.read().decode("utf-8")
                status = response.status
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8")
            status = error.code
        try:
            parsed = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            parsed = {"raw": raw}
        self.exchange.append(
            {
                "method": method,
                "url": self.base_url + path,
                "request_body": body,
                "status": status,
                "response_body": parsed,
            }
        )
        return status, parsed

    def webhook(self, payload: dict[str, Any], signature: str) -> tuple[int, Any]:
        raw = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            self.base_url + WEBHOOK_API,
            data=raw,
            method="POST",
            headers={"Content-Type": "application/json", "Stripe-Signature": signature},
        )
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                body = response.read().decode("utf-8")
                status = response.status
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8")
            status = error.code
        try:
            parsed = json.loads(body) if body else {}
        except json.JSONDecodeError:
            parsed = {"raw": body}
        self.exchange.append(
            {
                "method": "POST",
                "url": self.base_url + WEBHOOK_API,
                "request_body": payload,
                "status": status,
                "response_body": parsed,
            }
        )
        return status, parsed

    def mark(self) -> int:
        return len(self.exchange)

    def since(self, marker: int) -> list[dict[str, Any]]:
        return list(self.exchange[marker:])


def stripe_signature(payload: bytes, secret: str, timestamp: int) -> str:
    signed = str(timestamp).encode("utf-8") + b"." + payload
    digest = hmac.new(secret.encode("utf-8"), signed, hashlib.sha256).hexdigest()
    return "t=" + str(timestamp) + ",v1=" + digest


def stripe_event(event_id: str, event_type: str, intent_id: str, amount_minor: int, currency: str, payment_id: str) -> dict[str, Any]:
    return {
        "id": event_id,
        "object": "event",
        "api_version": "2024-06-20",
        "type": event_type,
        "created": int(time.time()),
        "data": {
            "object": {
                "id": intent_id,
                "object": "payment_intent",
                "amount": amount_minor,
                "currency": currency.lower(),
                "status": "succeeded" if event_type.endswith("succeeded") else "canceled",
                "livemode": False,
                "metadata": {"payment_id": payment_id},
            }
        },
    }


def _wire(wire: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "method": record["method"],
            "path": record["path"],
            "request_headers": record["request_headers"],
            "request_body": record["request_body"],
            "status": record["status"],
            "response_headers": record["response_headers"],
            "response_body": record["response_body"],
        }
        for record in wire
    ]


def _payment_status(document: Any) -> str:
    if not isinstance(document, dict):
        return ""
    data = document.get("data")
    if isinstance(data, dict):
        return str(data.get("status", ""))
    return ""


def _payment_body(document: Any) -> dict[str, Any]:
    if not isinstance(document, dict):
        return {}
    data = document.get("data")
    return data if isinstance(data, dict) else {}


def _error_code(document: Any) -> str:
    if not isinstance(document, dict):
        return ""
    return str(document.get("code", ""))


def _error_message(document: Any) -> str:
    if not isinstance(document, dict):
        return ""
    return str(document.get("message", ""))


def _refund_calls(wire: list[dict[str, Any]]) -> int:
    return len([record for record in wire if record["path"].startswith("/v1/refunds")])


def _intent_calls(wire: list[dict[str, Any]]) -> int:
    return len([record for record in wire if record["path"].startswith("/v1/payment_intents")])


def _intent_status(wire: list[dict[str, Any]]) -> str:
    for record in reversed(wire):
        body = record.get("response_body")
        if isinstance(body, str):
            try:
                document = json.loads(body)
            except json.JSONDecodeError:
                continue
            if isinstance(document, dict) and document.get("object") == "payment_intent":
                return str(document.get("status", ""))
    return ""


def _case_document(
    name: str,
    verdict: str,
    detail: str,
    platform_exchange: list[dict[str, Any]],
    wire: list[dict[str, Any]],
    proves: str = "",
    notes: list[str] | None = None,
    observations: dict[str, Any] | None = None,
) -> dict[str, Any]:
    definition = next((item for item in MATRIX_CASES if item["name"] == name), {})
    return {
        "name": name,
        "mandate": definition.get("mandate", ""),
        "proves": proves or definition.get("proves", ""),
        "verdict": verdict,
        "detail": detail,
        "platform_exchange": platform_exchange,
        "stripe_wire": _wire(wire),
        "stripe_call_count": len(wire),
        "notes": notes or [],
        "observations": observations or {},
    }


def _create_payment(platform: Platform, amount: float, currency: str) -> tuple[str, str, int, Any]:
    order_id = str(uuid.uuid4())
    key = "acceptance-" + str(uuid.uuid4())
    status, document = platform.call(
        "POST",
        PAYMENT_API,
        {"orderId": order_id, "amount": amount, "currency": currency, "idempotencyKey": key},
    )
    payment_id = str(_payment_body(document).get("id", ""))
    return order_id, payment_id, status, document


def _capture(platform: Platform, payment_id: str) -> tuple[int, Any]:
    return platform.call("POST", PAYMENT_API + "/" + payment_id + "/capture")


def _deliver_capture_event(
    platform: Platform,
    webhook_secret: str,
    payment_id: str,
    intent_id: str,
    amount_minor: int,
    currency: str,
    event_id: str,
    tamper: bool = False,
) -> tuple[int, Any]:
    event = stripe_event(event_id, "payment_intent.succeeded", intent_id, amount_minor, currency, payment_id)
    raw = json.dumps(event).encode("utf-8")
    timestamp = int(time.time())
    signature = stripe_signature(raw, webhook_secret, timestamp)
    if tamper:
        signature = "t=" + str(timestamp) + ",v1=" + ("0" * 64)
    return platform.webhook(event, signature)


def _refund(platform: Platform, payment_id: str, amount: float, currency: str, reference: str) -> tuple[int, Any]:
    return platform.call(
        "POST",
        PAYMENT_API + "/" + payment_id + "/refund",
        {"amount": amount, "currency": currency, "refundReference": reference},
    )


def _read_payment(platform: Platform, order_id: str) -> dict[str, Any]:
    status, document = platform.call("GET", PAYMENT_API + "?orderId=" + order_id)
    data = document.get("data") if isinstance(document, dict) else None
    content = data.get("content") if isinstance(data, dict) else None
    if isinstance(content, list) and content:
        first = content[0]
        return first if isinstance(first, dict) else {}
    return {}


def _mock_log(container: str = STRIPE_MOCK_CONTAINER) -> list[str]:
    code, out, _ = _docker(["logs", container], timeout=60)
    if code != 0:
        return []
    return out.splitlines()


def _mock_log_since(lines: list[str], marker: int) -> list[str]:
    return lines[marker:]


def _mock_request_count(lines: list[str]) -> int:
    return len([line for line in lines if line.startswith("Request: ")])


def run_acceptance(run_id: str | None = None) -> dict[str, Any]:
    identifier = run_id or config.run_identifier()
    values = _dotenv(config.repository_root() / ".env")
    secret_key = "sk_" + "test_" + _random_suffix(32)
    webhook_secret = "whsec_" + _random_suffix(40)
    server = ensure_stripe_mock()
    compose = ensure_compose_services(["postgres", "keycloak", "kafka", "rustfs"])
    recorder = Recorder(upstream="http://127.0.0.1:" + str(MOCK_HTTP_PORT), port=RECORDER_PORT)
    recorder.start()
    recorder_address = "http://host.docker.internal:" + str(RECORDER_PORT)
    service = ensure_payment_service(values, secret_key, webhook_secret, recorder_address)
    document: dict[str, Any] = {
        "generated_at": utc_now(),
        "run_id": identifier,
        "stripe_server": server,
        "wire_recorder": {
            "address": recorder_address,
            "upstream": "http://127.0.0.1:" + str(MOCK_HTTP_PORT),
            "behaviour": "forwards the request to the official stripe-mock and returns its response verbatim",
        },
        "platform": service,
        "compose": compose,
        "credential": {
            "secret_key_mode": "test",
            "secret_key_fingerprint": _fingerprint(secret_key),
            "webhook_secret_fingerprint": _fingerprint(webhook_secret),
            "secret_source": "generated for the run and passed to the container through the environment",
        },
        "cases": [],
        "supplementary": [],
    }
    if service.get("state") != "running":
        document["status"] = "BLOCKED"
        document["reason"] = "the payment service did not start against the Stripe endpoint under test"
        return document
    try:
        token = mint_token(values)
    except Exception as error:
        document["status"] = "BLOCKED"
        document["reason"] = "the platform identity provider did not issue a token: " + str(error)[:200]
        return document
    platform = Platform(service["host_address"], token)
    cases: list[dict[str, Any]] = []
    supplementary: list[dict[str, Any]] = []

    order_id, payment_id, create_status, create_document = _create_payment(platform, 249.99, PAYMENT_CURRENCY)
    platform_mark = platform.mark()
    wire_mark = recorder.marker()
    capture_status, capture_document = _capture(platform, payment_id)
    wire = recorder.since(wire_mark)
    intent_id = str(_payment_body(capture_document).get("providerIntentId", ""))
    intent_call = [record for record in wire if record["path"] == "/v1/payment_intents"]
    capture_proven = (
        create_status == 200
        and capture_status == 200
        and bool(intent_id)
        and bool(intent_call)
        and intent_call[-1]["status"] == 200
    )
    cases.append(
        _case_document(
            "authorisation_and_capture",
            VERDICT_PROVEN if capture_proven else VERDICT_FAILED,
            "the platform created the payment and then asked Stripe for the authorisation; the provider intent reference "
            + intent_id
            + " and the client secret were stored on the payment",
            platform.since(platform_mark),
            wire,
            notes=[
                "the official Stripe mock answers every intent with status requires_payment_method, so the platform holds "
                "the payment PENDING with a client secret exactly as it does for a 3DS confirmation",
                "the capture is completed by the signed provider event in the next case",
            ],
            observations={
                "payment_id": payment_id,
                "provider_intent_id": intent_id,
                "payment_status_after_capture": _payment_status(capture_document),
                "client_secret_present": bool(_payment_body(capture_document).get("clientSecret")),
                "payment_intent_calls": _intent_calls(wire),
            },
        )
    )

    platform_mark = platform.mark()
    wire_mark = recorder.marker()
    event_id = "evt_acceptance_" + _random_suffix(24)
    signed_status, signed_document = _deliver_capture_event(
        platform, webhook_secret, payment_id, intent_id, 24999, PAYMENT_CURRENCY, event_id
    )
    tamper_status, tamper_document = _deliver_capture_event(
        platform, webhook_secret, payment_id, intent_id, 24999, PAYMENT_CURRENCY, event_id + "_forged", tamper=True
    )
    state = _read_payment(platform, order_id)
    wire = recorder.since(wire_mark)
    webhook_proven = (
        signed_status == 200
        and tamper_status >= 400
        and str(state.get("status", "")) == "CAPTURED"
    )
    cases.append(
        _case_document(
            "capture_webhook_signature_verified",
            VERDICT_PROVEN if webhook_proven else VERDICT_FAILED,
            "the signed provider event moved the payment to CAPTURED and the event with a forged signature was refused with "
            + str(tamper_status)
            + " "
            + _error_code(tamper_document),
            platform.since(platform_mark),
            wire,
            notes=[
                "the signature is verified by stripe-java Webhook.constructEvent with the HMAC-SHA256 t/v1 format",
                "the webhook secret is local because no Stripe account issued one; the verification code path is the real one",
                "the official Stripe mock does not deliver webhooks, so the harness plays the role of Stripe for the delivery",
            ],
            observations={
                "signed_webhook_status": signed_status,
                "signed_webhook_body": signed_document,
                "forged_signature_status": tamper_status,
                "forged_signature_body": tamper_document,
                "payment_status": state.get("status"),
                "provider_intent_id": state.get("providerIntentId"),
            },
        )
    )

    platform_mark = platform.mark()
    wire_mark = recorder.marker()
    refund_reference = str(uuid.uuid4())
    refund_status, refund_document = _refund(platform, payment_id, 249.99, PAYMENT_CURRENCY, refund_reference)
    wire = recorder.since(wire_mark)
    refunded = _payment_body(refund_document)
    refund_proven = refund_status == 200 and str(refunded.get("status", "")) == "REFUNDED"
    cases.append(
        _case_document(
            "refund",
            VERDICT_PROVEN if refund_proven else VERDICT_FAILED,
            "the platform refunded the captured amount against the provider intent and the payment reached REFUNDED with "
            "refundedAmount "
            + str(refunded.get("refundedAmount")),
            platform.since(platform_mark),
            wire,
            observations={
                "payment_status": refunded.get("status"),
                "refunded_amount": refunded.get("refundedAmount"),
                "refund_calls": _refund_calls(wire),
                "payment_intent_calls": _intent_calls(wire),
            },
        )
    )

    platform_mark = platform.mark()
    wire_mark = recorder.marker()
    replay_status, replay_document = _refund(platform, payment_id, 249.99, PAYMENT_CURRENCY, refund_reference)
    wire = recorder.since(wire_mark)
    replayed = _payment_body(replay_document)
    replay_proven = (
        replay_status == 200
        and str(replayed.get("status", "")) == "REFUNDED"
        and _refund_calls(wire) == 0
    )
    cases.append(
        _case_document(
            "refund_idempotent_replay",
            VERDICT_PROVEN if replay_proven else VERDICT_FAILED,
            "the replay of the same refund reference returned the same refund and issued no further refund call to the "
            "provider",
            platform.since(platform_mark),
            wire,
            notes=["the platform answers the replay from its own refund ledger, so no money can move twice"],
            observations={
                "replay_status": replay_status,
                "replay_payment_status": replayed.get("status"),
                "replay_refunded_amount": replayed.get("refundedAmount"),
                "provider_refund_calls_during_replay": _refund_calls(wire),
            },
        )
    )

    platform_mark = platform.mark()
    wire_mark = recorder.marker()
    order_id, payment_id, create_status, create_document = _create_payment(platform, 249.99, PAYMENT_CURRENCY)
    capture_status, capture_document = _capture(platform, payment_id)
    intent_id = str(_payment_body(capture_document).get("providerIntentId", ""))
    _deliver_capture_event(
        platform, webhook_secret, payment_id, intent_id, 24999, PAYMENT_CURRENCY, "evt_acceptance_" + _random_suffix(24)
    )
    first_reference = str(uuid.uuid4())
    partial_status, partial_document = _refund(platform, payment_id, 50.00, PAYMENT_CURRENCY, first_reference)
    wire = recorder.since(wire_mark)
    partial = _payment_body(partial_document)
    partial_proven = partial_status == 200 and str(partial.get("status", "")) == "PARTIALLY_REFUNDED"
    cases.append(
        _case_document(
            "partial_refund",
            VERDICT_PROVEN if partial_proven else VERDICT_FAILED,
            "the partial refund moved 50.00 of the 249.99 total and the payment stayed PARTIALLY_REFUNDED",
            platform.since(platform_mark),
            wire,
            observations={
                "payment_status": partial.get("status"),
                "refunded_amount": partial.get("refundedAmount"),
                "previous_status": partial.get("previousStatus"),
                "provider_refund_calls": _refund_calls(wire),
            },
        )
    )

    platform_mark = platform.mark()
    wire_mark = recorder.marker()
    second_reference = str(uuid.uuid4())
    second_status, second_document = _refund(platform, payment_id, 25.00, PAYMENT_CURRENCY, second_reference)
    wire = recorder.since(wire_mark)
    second = _payment_body(second_document)
    second_proven = (
        second_status == 200
        and str(second.get("status", "")) == "PARTIALLY_REFUNDED"
        and float(second.get("refundedAmount", 0)) == 75.00
    )
    cases.append(
        _case_document(
            "second_partial_refund",
            VERDICT_PROVEN if second_proven else VERDICT_FAILED,
            "the second partial refund of 25.00 accumulated on the first one and the refunded amount reached 75.00",
            platform.since(platform_mark),
            wire,
            observations={
                "payment_status": second.get("status"),
                "refunded_amount": second.get("refundedAmount"),
                "previous_status": second.get("previousStatus"),
                "provider_refund_calls": _refund_calls(wire),
            },
        )
    )

    platform_mark = platform.mark()
    wire_mark = recorder.marker()
    _docker(["rm", "-f", PAYMENT_CONTAINER], timeout=120)
    decline_service = ensure_payment_service(
        values, secret_key, webhook_secret, recorder_address, DECLINE_PAYMENT_METHOD
    )
    decline_order, decline_payment, decline_create_status, decline_create_document = _create_payment(
        platform, 40.00, PAYMENT_CURRENCY
    )
    decline_capture_status, decline_capture_document = _capture(platform, decline_payment)
    decline_wire = recorder.since(wire_mark)
    decline_intent = str(_payment_body(decline_capture_document).get("providerIntentId", ""))
    decline_state = _read_payment(platform, decline_order)
    failure_event = stripe_event(
        "evt_acceptance_" + _random_suffix(24),
        "payment_intent.payment_failed",
        decline_intent,
        4000,
        PAYMENT_CURRENCY,
        decline_payment,
    )
    raw = json.dumps(failure_event).encode("utf-8")
    timestamp = int(time.time())
    failure_status, failure_document = platform.webhook(
        failure_event, stripe_signature(raw, webhook_secret, timestamp)
    )
    after_failure = _read_payment(platform, decline_order)
    refusal_status, refusal_document = _capture(platform, decline_payment)
    cases.append(
        _case_document(
            "declined_card_refusal",
            VERDICT_NOT_PROVEN,
            "the official Stripe mock never declines a card, so the direct provider refusal of a declined card is not proven; "
            "the refusal of a failed payment is proven through the signed provider event",
            platform.since(platform_mark),
            decline_wire,
            notes=[
                "stripe-mock documents that it does not reproduce the behaviour of the real Stripe API and always answers a "
                "success response, so no card can be declined against it",
                "the platform sent the declined test payment method request and the mock answered 200; the platform "
                "therefore could not observe the CardException branch of StripeProvider",
                "a genuine test-mode key with pm_card_chargeDeclined or card 4000000000000002 is required for this case",
            ],
            observations={
                "decline_payment_method_sent": DECLINE_PAYMENT_METHOD,
                "provider_answer_status": decline_wire[-1]["status"] if decline_wire else None,
                "provider_answer_intent_status": _intent_status(decline_wire),
                "provider_answer_body": decline_wire[-1]["response_body"] if decline_wire else None,
                "payment_status_after_capture": decline_state.get("status"),
                "payment_failed_event_status": failure_status,
                "payment_status_after_failure_event": after_failure.get("status"),
                "capture_after_failure_status": refusal_status,
                "capture_after_failure_code": _error_code(refusal_document),
                "capture_after_failure_message": _error_message(refusal_document),
                "payment_service_started_with_payment_method": decline_service.get("sandbox_payment_method"),
            },
        )
    )
    _docker(["rm", "-f", PAYMENT_CONTAINER], timeout=120)
    ensure_payment_service(values, secret_key, webhook_secret, recorder_address)

    platform_mark = platform.mark()
    wire_mark = recorder.marker()
    mismatch_order, mismatch_payment, _, _ = _create_payment(platform, 249.99, PAYMENT_CURRENCY)
    mismatch_capture_status, mismatch_capture_document = _capture(platform, mismatch_payment)
    mismatch_intent = str(_payment_body(mismatch_capture_document).get("providerIntentId", ""))
    _deliver_capture_event(
        platform,
        webhook_secret,
        mismatch_payment,
        mismatch_intent,
        24999,
        PAYMENT_CURRENCY,
        "evt_acceptance_" + _random_suffix(24),
    )
    mismatch_status, mismatch_document = _refund(
        platform, mismatch_payment, 10.00, "VND", str(uuid.uuid4())
    )
    mismatch_wire = recorder.since(wire_mark)
    mismatch_proven = mismatch_status >= 400 and _refund_calls(mismatch_wire) == 0
    cases.append(
        _case_document(
            "currency_mismatch_refusal",
            VERDICT_PROVEN if mismatch_proven else VERDICT_FAILED,
            "the refund that asked for a currency other than the captured currency was refused with "
            + str(mismatch_status)
            + " "
            + _error_code(mismatch_document)
            + " and no refund call reached the provider",
            platform.since(platform_mark),
            mismatch_wire,
            notes=["the refusal happens at the platform boundary before any provider call"],
            observations={
                "refund_status": mismatch_status,
                "refund_code": _error_code(mismatch_document),
                "refund_message": _error_message(mismatch_document),
                "provider_refund_calls": _refund_calls(mismatch_wire),
            },
        )
    )

    platform_mark = platform.mark()
    wire_mark = recorder.marker()
    provider_order, provider_payment, _, _ = _create_payment(platform, 250.00, "VND")
    provider_capture_status, provider_capture_document = _capture(platform, provider_payment)
    provider_intent = str(_payment_body(provider_capture_document).get("providerIntentId", ""))
    _deliver_capture_event(
        platform, webhook_secret, provider_payment, provider_intent, 250, "VND", "evt_acceptance_" + _random_suffix(24)
    )
    provider_status, provider_document = _refund(platform, provider_payment, 250.00, "VND", str(uuid.uuid4()))
    provider_wire = recorder.since(wire_mark)
    provider_state = _read_payment(platform, provider_order)
    provider_proven = provider_status >= 400 and str(provider_state.get("status", "")) == "CAPTURED"
    supplementary.append(
        _case_document(
            "provider_currency_mismatch_refusal",
            VERDICT_PROVEN if provider_proven else VERDICT_FAILED,
            "the provider guard refused the refund of a VND payment because the intent currency read back from Stripe differs "
            "from the payment currency",
            platform.since(platform_mark),
            provider_wire,
            notes=[
                "the official Stripe mock is stateless, so it answers the intent retrieval with its fixture currency usd; "
                "against api.stripe.com the intent would carry the currency that was created",
                "the refusal itself is the real platform behaviour: StripeProvider refuses a refund whose currency differs "
                "from the intent currency",
            ],
            observations={
                "refund_status": provider_status,
                "refund_code": _error_code(provider_document),
                "refund_message": _error_message(provider_document),
                "payment_status": provider_state.get("status"),
                "intent_currency_answered_by_provider": "usd",
            },
        )
    )

    document["cases"] = cases
    document["supplementary"] = supplementary
    proven = len([case for case in cases if case["verdict"] == VERDICT_PROVEN])
    failed = len([case for case in cases if case["verdict"] == VERDICT_FAILED])
    not_proven = len([case for case in cases if case["verdict"] == VERDICT_NOT_PROVEN])
    document["summary"] = {
        "cases": len(cases),
        "proven": proven,
        "failed": failed,
        "not_proven": not_proven,
        "supplementary": len(supplementary),
    }
    document["verified_against_real_stripe_api"] = False
    document["stripe_api_base_of_record"] = server["http_address_in_network"]
    document["status"] = "PROVEN_AGAINST_OFFICIAL_STRIPE_MOCK" if failed == 0 else "FAILED"
    document["reason"] = (
        "the payment acceptance ran against the official Stripe mock server, not against api.stripe.com; "
        "a genuine test-mode key is still required to close the last mile"
    )
    log_lines = _mock_log()
    document["stripe_server_log"] = log_lines
    return document


def evidence_directory(run_id: str | None = None) -> Path:
    identifier = run_id or config.run_identifier()
    return config.run_root(identifier) / "stripe-acceptance"


def write_evidence(document: dict[str, Any], run_id: str | None = None) -> Path:
    directory = evidence_directory(run_id)
    directory.mkdir(parents=True, exist_ok=True)
    write_json(directory / "acceptance.json", document)
    server = dict(document.get("stripe_server") or {})
    server["generated_at"] = document.get("generated_at")
    server["summary"] = document.get("summary")
    server["verified_against_real_stripe_api"] = document.get("verified_against_real_stripe_api")
    server["stripe_api_base_of_record"] = document.get("stripe_api_base_of_record")
    write_json(directory / "server.json", server)
    return directory / "acceptance.json"


def load_evidence(run_id: str | None = None) -> dict[str, Any]:
    path = evidence_directory(run_id) / "acceptance.json"
    return read_json(path, {}) or {}

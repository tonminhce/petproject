from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

from .. import config

ADMIN_USERNAME_KEYS = ("KEYCLOAK_ADMIN_USER", "E2E_ADMIN_USERNAME", "KEYCLOAK_ADMIN_USERNAME")
ADMIN_PASSWORD_KEYS = ("KEYCLOAK_ADMIN_USER_PASSWORD", "E2E_ADMIN_PASSWORD")
CUSTOMER_USERNAME_KEYS = ("KEYCLOAK_USERNAME", "E2E_CUSTOMER_USERNAME")
CUSTOMER_PASSWORD_KEYS = ("KEYCLOAK_TEST_USER_PASSWORD", "KEYCLOAK_PASSWORD", "E2E_CUSTOMER_PASSWORD")

SERVICE_URL_KEYS = (
    ("gatewayUrl", "GATEWAY_PORT"),
    ("authUrl", "AUTH_PORT"),
    ("favouriteUrl", "FAVOURITE_PORT"),
    ("favouritesUrl", "FAVOURITE_PORT"),
    ("inventoryUrl", "INVENTORY_PORT"),
    ("mediaUrl", "MEDIA_PORT"),
    ("orderUrl", "ORDER_PORT"),
    ("paymentUrl", "PAYMENT_PORT"),
    ("productUrl", "PRODUCT_PORT"),
    ("shippingUrl", "SHIPPING_PORT"),
    ("ratingUrl", "RATING_PORT"),
    ("notificationUrl", "NOTIFICATION_PORT"),
    ("taxUrl", "TAX_PORT"),
    ("promotionUrl", "PROMOTION_PORT"),
    ("searchUrl", "SEARCH_PORT"),
)

DEFAULT_PORTS = {
    "GATEWAY_PORT": 8080,
    "AUTH_PORT": 8088,
    "FAVOURITE_PORT": 8081,
    "INVENTORY_PORT": 8082,
    "MEDIA_PORT": 8083,
    "ORDER_PORT": 8084,
    "PAYMENT_PORT": 8085,
    "PRODUCT_PORT": 8086,
    "SHIPPING_PORT": 8087,
    "RATING_PORT": 8089,
    "NOTIFICATION_PORT": 8090,
    "TAX_PORT": 8091,
    "PROMOTION_PORT": 8093,
    "SEARCH_PORT": 8094,
}


def _first(values: dict[str, str], keys: tuple[str, ...]) -> str:
    for key in keys:
        candidate = values.get(key, "").strip()
        if candidate:
            return candidate
    return ""


def build_postman_environment(run_root: Path) -> dict[str, Any]:
    values = config.load_local_environment()
    resolved: dict[str, str] = {}

    admin_user = _first(values, ADMIN_USERNAME_KEYS) or "adminuser"
    admin_password = _first(values, ADMIN_PASSWORD_KEYS)
    customer_user = _first(values, CUSTOMER_USERNAME_KEYS) or "testuser"
    customer_password = _first(values, CUSTOMER_PASSWORD_KEYS)

    resolved["adminUser"] = admin_user
    resolved["normalUser"] = customer_user
    if admin_password:
        resolved["adminPass"] = admin_password
    if customer_password:
        resolved["normalPass"] = customer_password

    for variable, port_key in SERVICE_URL_KEYS:
        port = values.get(port_key, "").strip() or str(DEFAULT_PORTS[port_key])
        resolved[variable] = f"http://localhost:{port}"

    webhook_secret = values.get("SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET", "").strip() or values.get("STRIPE_WEBHOOK_SECRET", "").strip()
    if webhook_secret:
        resolved["stripeWebhookSecret"] = webhook_secret
    resolved["vnpayEnabled"] = "true" if values.get("SHOP_PAYMENT_VNPAY_ENABLED", "").strip().lower() == "true" else "false"
    resolved["momoEnabled"] = "true" if values.get("SHOP_PAYMENT_MOMO_ENABLED", "").strip().lower() == "true" else "false"
    stripe_api_base = values.get("SHOP_PAYMENT_STRIPE_API_BASE", "").strip().rstrip("/")
    stripe_key = values.get("SHOP_PAYMENT_STRIPE_SECRET_KEY", "").strip()
    resolved["stripeCaptureEnabled"] = "true" if stripe_api_base == "https://api.stripe.com" and stripe_key.startswith("sk_test_") else "false"

    missing = [
        name
        for name, value in (
            ("adminPass", admin_password),
            ("normalPass", customer_password),
        )
        if not value
    ]

    environment = {
        "id": str(run_root / "postman-environment.json"),
        "name": "petproject-local",
        "values": [
            {"key": key, "value": value, "enabled": True, "type": "text"}
            for key, value in sorted(resolved.items())
        ],
        "_meta": {
            "generator": "petproject-harness",
            "credential_source": "the local environment file",
        },
    }
    return {
        "environment": environment,
        "missing_credentials": missing,
        "admin_user": admin_user,
        "customer_user": customer_user,
        "webhook_secret_present": bool(webhook_secret),
    }


def write_postman_environment(run_root: Path) -> tuple[Path, dict[str, Any]]:
    document = build_postman_environment(run_root)
    path = run_root / "postman-environment.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(document["environment"], indent=2) + "\n"
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            descriptor = -1
            handle.write(payload)
    finally:
        if descriptor >= 0:
            os.close(descriptor)
    return path, document

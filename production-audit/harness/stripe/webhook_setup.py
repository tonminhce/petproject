"""Point the Stripe webhook endpoint of the account at a running payment service.

Stripe delivers a webhook over the public internet, so a locally running payment service needs a
tunnel. This command opens the tunnel, registers the webhook endpoint against it and writes the
issued signing secret into the git ignored .env so that the service can verify the deliveries.

The command is the reproducible preparation for a real webhook delivery proof.
"""

from __future__ import annotations

import json
import os
import pathlib
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

WEBHOOK_PATH = "/api/v1/webhooks/payments/stripe"
EVENTS = ["payment_intent.succeeded", "payment_intent.payment_failed", "charge.refunded"]
TUNNEL_PATTERN = re.compile(r"https://[a-z0-9-]+\.trycloudflare\.com")


def _secret_key() -> str:
    key = os.environ.get("SHOP_PAYMENT_STRIPE_SECRET_KEY", "").strip()
    if not key:
        raise RuntimeError("SHOP_PAYMENT_STRIPE_SECRET_KEY is not set")
    return key


def _stripe(method: str, path: str, fields: dict[str, str] | None = None) -> tuple[int, Any]:
    body = urllib.parse.urlencode(fields).encode() if fields is not None else None
    request = urllib.request.Request(
        "https://api.stripe.com" + path,
        data=body,
        method=method,
        headers={
            "Authorization": "Bearer " + _secret_key(),
            "Content-Type": "application/x-www-form-urlencoded",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode())


def _open_tunnel(port: int) -> tuple[str, subprocess.Popen | None]:
    """Open a quick tunnel and return its public host url."""
    log = pathlib.Path("/tmp/harness-stripe-tunnel.log")
    try:
        log.unlink()
    except FileNotFoundError:
        pass
    process = subprocess.Popen(
        ["cloudflared", "tunnel", "--url", f"http://localhost:{port}", "--no-autoupdate"],
        stdout=log.open("w"),
        stderr=subprocess.STDOUT,
    )
    deadline = time.time() + 60
    while time.time() < deadline:
        if log.exists():
            match = TUNNEL_PATTERN.search(log.read_text(errors="ignore"))
            if match:
                return match.group(0), process
        time.sleep(2)
    return "", process


def _existing_endpoints() -> list[dict[str, Any]]:
    code, document = _stripe("GET", "/v1/webhook_endpoints")
    if code != 200:
        return []
    return [item for item in (document.get("data") or []) if isinstance(item, dict)]


def _write_secret(env_path: pathlib.Path, secret: str) -> None:
    text = env_path.read_text() if env_path.exists() else ""
    if "SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET=" in text:
        text = re.sub(
            r"(?m)^SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET=.*$",
            "SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET=" + secret,
            text,
        )
    else:
        text = text.rstrip() + "\nSHOP_PAYMENT_STRIPE_WEBHOOK_SECRET=" + secret + "\n"
    env_path.write_text(text)


def _default_env_path() -> pathlib.Path:
    """The git ignored .env of the repository, not the one of the harness folder."""
    return pathlib.Path(__file__).resolve().parents[3] / ".env"


def setup_webhook(port: int = 18085, env_path: str | None = None) -> dict[str, Any]:
    """Register the webhook endpoint and store the signing secret."""
    document: dict[str, Any] = {"state": "failed", "port": port}
    tunnel_url, process = _open_tunnel(port)
    document["tunnel_url"] = tunnel_url
    if not tunnel_url:
        document["reason"] = "the tunnel did not report a public url within 60 seconds"
        return document
    webhook_url = tunnel_url + WEBHOOK_PATH

    endpoints = _existing_endpoints()
    target = next((item for item in endpoints if item.get("url") == webhook_url), None)
    if target is None:
        fields = {"url": webhook_url, "description": "shop payment service via the audit tunnel"}
        for index, event in enumerate(EVENTS):
            fields[f"enabled_events[{index}]"] = event
        code, created = _stripe("POST", "/v1/webhook_endpoints", fields)
        if code != 200:
            document["reason"] = str((created.get("error") or {}).get("message"))[:300]
            return document
        target = created
        document["created"] = True
    else:
        code, updated = _stripe("POST", "/v1/webhook_endpoints/" + str(target.get("id")),
                               {"url": webhook_url})
        if code == 200:
            target = updated
        document["created"] = False

    document["endpoint_id"] = target.get("id")
    document["endpoint_url"] = target.get("url")
    document["enabled_events"] = target.get("enabled_events")

    for item in endpoints:
        if item.get("id") != target.get("id") and item.get("url", "").startswith("https://example.com"):
            _stripe("DELETE", "/v1/webhook_endpoints/" + str(item.get("id")))
            document.setdefault("deleted_stale_endpoints", []).append(item.get("id"))

    code, listed = _stripe("GET", "/v1/webhook_endpoints/" + str(target.get("id")))
    secret = listed.get("secret") if isinstance(listed, dict) else None
    if not secret:
        code, fresh = _stripe("POST", "/v1/webhook_endpoints",
                              {"url": webhook_url, "description": "shop payment service via the audit tunnel",
                               **{f"enabled_events[{index}]": event for index, event in enumerate(EVENTS)}})
        secret = fresh.get("secret") if code == 200 else None
    if not secret:
        document["reason"] = "stripe did not return a signing secret for the webhook endpoint"
        return document

    target_env = pathlib.Path(env_path) if env_path else _default_env_path()
    _write_secret(target_env, secret)
    document["signing_secret_written_to"] = str(target_env)
    document["state"] = "ready"
    document["note"] = "restart the payment service so that it reads the new signing secret"
    if process is not None:
        document["tunnel_pid"] = process.pid
    return document
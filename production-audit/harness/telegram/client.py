from __future__ import annotations

import hashlib
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass

BOT_TOKEN_PATTERN = re.compile(r"^[0-9]{6,12}:[A-Za-z0-9_-]{20,}$")
CHAT_ID_PATTERN = re.compile(r"^-?[0-9]{1,20}$")
LIVE_TOKEN_PATTERN = re.compile(r"^sk_live_")
LIVE_BOT_TOKEN_PATTERN = re.compile(r"^bot[0-9]{6,12}:")
BOT_API_BASE = "https://api.telegram.org"
PUBLIC_BOT_API_BASE = "https://api.telegram.org"
REQUEST_TIMEOUT_SECONDS = 15
MAX_MESSAGE_LENGTH = 4096

ENV_BOT_TOKEN = "TELEGRAM_BOT_TOKEN"
ENV_CHAT_ID = "TELEGRAM_CHAT_ID"
ENV_CI_CHAT_ID = "TELEGRAM_CI_CHAT_ID"
ENV_ALERTS_ENABLED = "TELEGRAM_ALERTS_ENABLED"
ENV_BOT_API_BASE = "TELEGRAM_BOT_API_BASE"


class CredentialError(Exception):
    pass


class DeliveryError(Exception):
    pass


@dataclass(frozen=True)
class Credentials:
    bot_token: str
    chat_id: str
    ci_chat_id: str

    def destination_for(self, route: str) -> str:
        if route == "ci" and self.ci_chat_id:
            return self.ci_chat_id
        return self.chat_id


def alerts_enabled(env: dict | None = None) -> bool:
    source = env if env is not None else os.environ
    raw = str(source.get(ENV_ALERTS_ENABLED, "true")).strip().lower()
    return raw not in {"false", "0", "no", "off", "disabled"}


def bot_api_base(env: dict | None = None) -> str:
    source = env if env is not None else os.environ
    value = str(source.get(ENV_BOT_API_BASE, "") or "").strip()
    if not value:
        return BOT_API_BASE
    return value.rstrip("/")


def uses_public_bot_api(env: dict | None = None) -> bool:
    return bot_api_base(env) == PUBLIC_BOT_API_BASE


def mask_identifier(value: str | int | None) -> str:
    text = str(value if value is not None else "").strip()
    if not text:
        return ""
    return "sha256:" + hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def validate_bot_token(token: str) -> None:
    if not token:
        raise CredentialError("Telegram bot token is empty")
    if LIVE_BOT_TOKEN_PATTERN.match(token):
        raise CredentialError("Refusing a bot token that carries a live-mode prefix")
    if not BOT_TOKEN_PATTERN.match(token):
        raise CredentialError("Telegram bot token does not match the expected bot token format")


def validate_chat_id(chat_id: str, name: str) -> None:
    if not chat_id:
        raise CredentialError(name + " is empty")
    if not CHAT_ID_PATTERN.match(chat_id):
        raise CredentialError(name + " is not a valid Telegram chat identifier")


def resolve_credentials(env: dict | None = None) -> Credentials:
    source = env if env is not None else os.environ
    token = str(source.get(ENV_BOT_TOKEN, "")).strip()
    chat_id = str(source.get(ENV_CHAT_ID, "")).strip()
    ci_chat_id = str(source.get(ENV_CI_CHAT_ID, "")).strip()
    validate_bot_token(token)
    validate_chat_id(chat_id, ENV_CHAT_ID)
    if ci_chat_id:
        validate_chat_id(ci_chat_id, ENV_CI_CHAT_ID)
    return Credentials(bot_token=token, chat_id=chat_id, ci_chat_id=ci_chat_id)


def credentials_from_environment(env: dict | None = None) -> Credentials:
    return resolve_credentials(env)


def credential_status(env: dict | None = None) -> dict:
    source = env if env is not None else os.environ
    report = {
        "enabled": alerts_enabled(source),
        "bot_token_present": bool(str(source.get(ENV_BOT_TOKEN, "")).strip()),
        "chat_id_present": bool(str(source.get(ENV_CHAT_ID, "")).strip()),
        "ci_chat_id_present": bool(str(source.get(ENV_CI_CHAT_ID, "")).strip()),
        "bot_token_format_valid": False,
        "errors": [],
    }
    token = str(source.get(ENV_BOT_TOKEN, "")).strip()
    if token:
        try:
            validate_bot_token(token)
            report["bot_token_format_valid"] = True
        except CredentialError as error:
            report["errors"].append(str(error))
    for name in (ENV_CHAT_ID, ENV_CI_CHAT_ID):
        value = str(source.get(name, "")).strip()
        if not value:
            continue
        try:
            validate_chat_id(value, name)
        except CredentialError as error:
            report["errors"].append(str(error))
    report["ready"] = (
        report["enabled"]
        and report["bot_token_present"]
        and report["bot_token_format_valid"]
        and report["chat_id_present"]
        and not report["errors"]
    )
    return report


def redact(text: str, secrets: list[str] | None = None) -> str:
    result = text
    candidates = list(secrets or [])
    token = str(os.environ.get(ENV_BOT_TOKEN, "")).strip()
    if token:
        candidates.append(token)
    for secret in candidates:
        if secret and len(secret) >= 6:
            result = result.replace(secret, "[redacted]")
    result = re.sub(r"\b[0-9]{6,12}:[A-Za-z0-9_-]{20,}\b", "[redacted-bot-token]", result)
    result = re.sub(r"\bsk_live_[A-Za-z0-9]{6,}\b", "[redacted-stripe-live-key]", result)
    return result


def send_bot_api_request(
    method: str,
    credentials: Credentials,
    payload: dict | None = None,
    timeout: int = REQUEST_TIMEOUT_SECONDS,
    base: str | None = None,
) -> dict:
    endpoint = str(base or bot_api_base()).rstrip("/")
    url = endpoint + "/bot" + credentials.bot_token + "/" + method
    body = urllib.parse.urlencode(payload or {}).encode("utf-8")
    request = urllib.request.Request(url, data=body, method="POST")
    request.add_header("Content-Type", "application/x-www-form-urlencoded")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise DeliveryError(
            "Telegram API at " + endpoint + " returned HTTP " + str(error.code) + ": "
            + redact(detail, [credentials.bot_token])
        ) from error
    except urllib.error.URLError as error:
        raise DeliveryError(
            "Telegram API at " + endpoint + " is unreachable: " + redact(str(error.reason), [credentials.bot_token])
        ) from error
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as error:
        raise DeliveryError("Telegram API at " + endpoint + " returned a non-JSON body") from error
    if not parsed.get("ok"):
        raise DeliveryError(
            "Telegram API at " + endpoint + " rejected the request: "
            + redact(str(parsed.get("description", "unknown")), [credentials.bot_token])
        )
    return parsed


def send_message(
    text: str,
    credentials: Credentials,
    route: str = "runtime",
    parse_mode: str = "HTML",
    disable_notification: bool = False,
    timeout: int = REQUEST_TIMEOUT_SECONDS,
    base: str | None = None,
) -> dict:
    destination = credentials.destination_for(route)
    body = redact(text, [credentials.bot_token])
    if len(body) > MAX_MESSAGE_LENGTH:
        body = body[: MAX_MESSAGE_LENGTH - 3] + "..."
    payload = {
        "chat_id": destination,
        "text": body,
        "parse_mode": parse_mode,
        "disable_web_page_preview": "true",
        "disable_notification": "true" if disable_notification else "false",
    }
    return send_bot_api_request("sendMessage", credentials, payload, timeout, base)


def get_me(credentials: Credentials, timeout: int = REQUEST_TIMEOUT_SECONDS, base: str | None = None) -> dict:
    response = send_bot_api_request("getMe", credentials, {}, timeout, base)
    return response.get("result", {})

from __future__ import annotations

from pathlib import Path

from .. import config
from ..telegram.client import CredentialError, resolve_credentials

BOT_TOKEN_PLACEHOLDER = "__TELEGRAM_BOT_TOKEN__"
CHAT_ID_PLACEHOLDER = "__TELEGRAM_CHAT_ID__"
CI_CHAT_ID_PLACEHOLDER = "__TELEGRAM_CI_CHAT_ID__"


def alertmanager_template_path() -> Path:
    return config.repository_root() / "config" / "observability" / "alertmanager" / "alertmanager.yml.template"


def alertmanager_rules_path() -> Path:
    return config.repository_root() / "config" / "observability" / "alertmanager" / "rules" / "runtime-alerts.yml"


def render_alertmanager_config(bot_token: str, chat_id: str, ci_chat_id: str) -> str:
    template = alertmanager_template_path().read_text(encoding="utf-8")
    rendered = template.replace(BOT_TOKEN_PLACEHOLDER, bot_token)
    rendered = rendered.replace(CI_CHAT_ID_PLACEHOLDER, ci_chat_id or chat_id)
    rendered = rendered.replace(CHAT_ID_PLACEHOLDER, chat_id)
    return rendered


def render_from_environment(env: dict | None = None) -> str:
    credentials = resolve_credentials(env)
    return render_alertmanager_config(credentials.bot_token, credentials.chat_id, credentials.ci_chat_id)


def write_rendered_config(target: Path | None = None, env: dict | None = None) -> Path:
    destination = target or (config.repository_root() / "config" / "observability" / "alertmanager" / "alertmanager.yml")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(render_from_environment(env), encoding="utf-8")
    try:
        destination.chmod(0o600)
    except OSError:
        pass
    return destination


def placeholders_present(text: str) -> bool:
    return BOT_TOKEN_PLACEHOLDER in text or CHAT_ID_PLACEHOLDER in text or CI_CHAT_ID_PLACEHOLDER in text


def credential_problem(env: dict | None = None) -> str:
    try:
        resolve_credentials(env)
    except CredentialError as error:
        return str(error)
    return ""

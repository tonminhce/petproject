from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

DEFAULT_PREFIX = "loadtest"
DEFAULT_REALM = "ecommerce"
PASSWORD_VARIABLE = "LOAD_TEST_CUSTOMER_PASSWORD"
DEFAULT_TOKEN_LIFETIME_SECONDS = 300


def _admin_password() -> str:
    return str(os.environ.get("KEYCLOAK_ADMIN_PASSWORD", "")).strip()


def load_test_password() -> str:
    password = str(os.environ.get(PASSWORD_VARIABLE, "")).strip()
    if not password:
        raise RuntimeError(
            "the load test customer password is not configured, so the harness would create a customer with a "
            "password that is written down in the source. Set " + PASSWORD_VARIABLE + " to a secret value."
        )
    return password


def _token_url(realm: str) -> str:
    base = str(os.environ.get("KEYCLOAK_ADMIN_URL", "")).strip() or "http://localhost:9090"
    return f"{base}/realms/{realm}/protocol/openid-connect/token"


def _admin_token(realm: str) -> str:
    data = urllib.parse.urlencode(
        {
            "client_id": "admin-cli",
            "username": str(os.environ.get("KEYCLOAK_ADMIN", "admin")).strip(),
            "password": _admin_password(),
            "grant_type": "password",
        }
    ).encode()
    request = urllib.request.Request(_token_url("master"), data=data)
    request.add_header("Content-Type", "application/x-www-form-urlencoded")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return str(json.loads(response.read()).get("access_token", ""))
    except (urllib.error.URLError, OSError, ValueError):
        return ""


def _admin_base(realm: str) -> str:
    base = str(os.environ.get("KEYCLOAK_ADMIN_URL", "http://localhost:9090")).strip()
    return f"{base}/admin/realms/{realm}"


def _realm_representation(realm: str, token: str) -> dict[str, Any] | None:
    request = urllib.request.Request(_admin_base(realm))
    request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read())
    except (urllib.error.URLError, OSError, ValueError):
        return None


def _write_realm(realm: str, token: str, representation: dict[str, Any]) -> bool:
    request = urllib.request.Request(
        _admin_base(realm),
        data=json.dumps(representation).encode(),
        method="PUT",
    )
    request.add_header("Authorization", "Bearer " + token)
    request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            response.read()
        return True
    except (urllib.error.URLError, OSError, ValueError):
        return False


def ensure_token_lifetime(realm: str = DEFAULT_REALM, seconds: int = DEFAULT_TOKEN_LIFETIME_SECONDS) -> dict[str, Any]:
    token = _admin_token(realm)
    if not token:
        return {
            "status": "BLOCKED",
            "reason": "the keycloak admin token could not be minted, so the lifetime of the customer tokens "
                      "cannot be set and a long profile would measure expired credentials instead of the platform",
        }
    representation = _realm_representation(realm, token)
    if representation is None:
        return {
            "status": "BLOCKED",
            "reason": "the realm could not be read, so the lifetime of the customer tokens cannot be set",
        }
    original = int(representation.get("accessTokenLifespan") or DEFAULT_TOKEN_LIFETIME_SECONDS)
    if original >= seconds:
        return {"status": "READY", "lifespan_seconds": original, "changed": False, "original_seconds": original}
    representation["accessTokenLifespan"] = int(seconds)
    if not _write_realm(realm, token, representation):
        return {
            "status": "BLOCKED",
            "reason": "the realm could not be written, so the lifetime of the customer tokens cannot be set and a "
                      "long profile would measure expired credentials instead of the platform",
        }
    return {
        "status": "READY",
        "lifespan_seconds": int(seconds),
        "changed": True,
        "original_seconds": original,
    }


def restore_token_lifetime(
    original_seconds: int,
    realm: str = DEFAULT_REALM,
) -> dict[str, Any]:
    token = _admin_token(realm)
    if not token:
        return {"status": "BLOCKED", "reason": "the keycloak admin token could not be minted"}
    representation = _realm_representation(realm, token)
    if representation is None:
        return {"status": "BLOCKED", "reason": "the realm could not be read"}
    representation["accessTokenLifespan"] = int(original_seconds)
    if not _write_realm(realm, token, representation):
        return {"status": "BLOCKED", "reason": "the realm could not be written"}
    return {"status": "READY", "lifespan_seconds": int(original_seconds)}


def provision_customers(
    count: int,
    realm: str = DEFAULT_REALM,
    prefix: str = DEFAULT_PREFIX,
) -> dict[str, Any]:
    token = _admin_token(realm)
    if not token:
        return {
            "status": "BLOCKED",
            "reason": "the keycloak admin token could not be minted, so the load test cannot give every virtual "
                      "user its own customer and cart",
        }
    base = _admin_base(realm)
    usernames: list[str] = []
    created = 0
    for index in range(count):
        username = f"{prefix}-{realm}-{index}"
        payload = json.dumps(
            {
                "username": username,
                "email": f"{username}@loadtest.local",
                "firstName": "Load",
                "lastName": "Tester",
                "enabled": True,
                "emailVerified": True,
                "credentials": [
                    {
                        "type": "password",
                        "value": load_test_password(),
                        "temporary": False,
                    }
                ],
            }
        ).encode()
        request = urllib.request.Request(f"{base}/users", data=payload, method="POST")
        request.add_header("Authorization", "Bearer " + token)
        request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                response.read()
                created += 1
        except urllib.error.HTTPError as error:
            if error.code != 409:
                return {
                    "status": "ERROR",
                    "reason": f"the load test customer {username} could not be created: {error.code}",
                }
        except (urllib.error.URLError, OSError):
            return {
                "status": "ERROR",
                "reason": f"the load test customer {username} could not be created",
            }
        usernames.append(username)
    return {"status": "READY", "usernames": usernames, "created": created, "realm": realm}


def mint_tokens(
    usernames: list[str],
    realm: str = DEFAULT_REALM,
    client_id: str = "ecommerce-client",
) -> dict[str, Any]:
    tokens: list[str] = []
    failed: list[str] = []
    for username in usernames:
        data = urllib.parse.urlencode(
            {
                "client_id": client_id,
                "username": username,
                "password": load_test_password(),
                "grant_type": "password",
            }
        ).encode()
        request = urllib.request.Request(_token_url(realm), data=data)
        request.add_header("Content-Type", "application/x-www-form-urlencoded")
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = json.loads(response.read())
        except (urllib.error.URLError, OSError, ValueError):
            failed.append(username)
            continue
        access_token = str(payload.get("access_token", "")).strip()
        if access_token:
            tokens.append(access_token)
        else:
            failed.append(username)
    if not tokens:
        return {
            "status": "BLOCKED",
            "reason": "no access token could be minted for the load test customers",
            "failed": len(failed),
        }
    return {"status": "READY", "tokens": tokens, "failed": failed, "minted": len(tokens)}

from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

DEFAULT_PREFIX = "loadtest"
DEFAULT_REALM = "ecommerce"
DEFAULT_PASSWORD = "LoadTest-Passw0rd!"


def _admin_password() -> str:
    return str(os.environ.get("KEYCLOAK_ADMIN_PASSWORD", "")).strip()


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
                        "value": DEFAULT_PASSWORD,
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
    for username in usernames:
        data = urllib.parse.urlencode(
            {
                "client_id": client_id,
                "username": username,
                "password": DEFAULT_PASSWORD,
                "grant_type": "password",
            }
        ).encode()
        request = urllib.request.Request(_token_url(realm), data=data)
        request.add_header("Content-Type", "application/x-www-form-urlencoded")
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = json.loads(response.read())
        except (urllib.error.URLError, OSError, ValueError):
            continue
        access_token = str(payload.get("access_token", "")).strip()
        if access_token:
            tokens.append(access_token)
    if not tokens:
        return {
            "status": "BLOCKED",
            "reason": "no access token could be minted for the load test customers",
        }
    return {"status": "READY", "tokens": tokens}

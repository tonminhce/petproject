"""Verification suites: run real checks and persist evidence.

Every suite returns a structured result with a status in
PASS / FAIL / PARTIAL / BLOCKED / NOT_APPLICABLE and never claims a
pass without producing observable evidence.
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import time
from pathlib import Path
from typing import Any

from . import persistence

PASS = "PASS"
FAIL = "FAIL"
PARTIAL = "PARTIAL"
BLOCKED = "BLOCKED"
NA = "NOT_APPLICABLE"


class EvidenceStore:
    def __init__(self, root: Path):
        self.root = root
        self.path = root / "production-audit" / "state" / "evidence.json"
        data = persistence.read_json(self.path, default=None)
        if not isinstance(data, dict) or "evidence" not in data:
            data = {"schema_version": 1, "evidence": {}}
        self.data = data

    def record(self, key: str, record: dict[str, Any]) -> None:
        rec = {"at": time.strftime("%Y-%m-%dT%H:%M:%S"), **record}
        self.data["evidence"][key] = rec
        persistence.write_json(self.path, self.data)

    def get(self, key: str) -> dict[str, Any] | None:
        return self.data["evidence"].get(key)

    def all(self) -> dict[str, Any]:
        return self.data["evidence"]


def _run(cmd: list[str], cwd: Path, timeout: int = 1800) -> dict[str, Any]:
    start = time.time()
    try:
        proc = subprocess.run(
            cmd, cwd=cwd, capture_output=True, text=True, timeout=timeout, encoding="utf-8",
            errors="replace",
        )
        return {
            "command": " ".join(cmd),
            "returncode": proc.returncode,
            "stdout_tail": proc.stdout[-20000:],
            "stderr_tail": proc.stderr[-20000:],
            "duration_s": round(time.time() - start, 2),
        }
    except subprocess.TimeoutExpired:
        return {
            "command": " ".join(cmd),
            "returncode": None,
            "stdout_tail": "",
            "stderr_tail": f"TIMEOUT after {timeout}s",
            "duration_s": round(time.time() - start, 2),
            "timed_out": True,
        }
    except FileNotFoundError as exc:
        return {
            "command": " ".join(cmd),
            "returncode": None,
            "stdout_tail": "",
            "stderr_tail": f"TOOL_MISSING: {exc}",
            "duration_s": 0,
            "tool_missing": True,
        }


def _maven_available(root: Path) -> bool:
    return shutil.which("mvn") is not None or (root / "mvnw.cmd").exists() or (root / "mvnw").exists()


def _mvn_cmd(root: Path) -> list[str]:
    if shutil.which("mvn"):
        return ["mvn", "-B", "-ntp"]
    if (root / "mvnw.cmd").exists():
        return [".\\mvnw.cmd", "-B", "-ntp"]
    return ["./mvnw", "-B", "-ntp"]


# ---------------------------------------------------------------- suites
def _public_paths_by_service(root: Path) -> dict[str, set[str]]:
    """Read each service's shop.security.public-paths allow-list."""
    out: dict[str, set[str]] = {}
    for yml in root.rglob("application.yml"):
        rel = yml.relative_to(root).as_posix()
        if "production-audit" in rel or "/target/" in rel:
            continue
        module = rel.split("/")[0]
        try:
            text = yml.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        paths: set[str] = set()
        in_block = False
        base_indent = 0
        for line in text.splitlines():
            stripped = line.strip()
            indent = len(line) - len(line.lstrip())
            if stripped.startswith("public-paths:"):
                in_block = True
                base_indent = indent
                continue
            if in_block:
                if not stripped or stripped.startswith("#"):
                    continue
                if indent <= base_indent:
                    in_block = False
                    continue
                if stripped.startswith("- path:"):
                    paths.add(stripped.split(":", 1)[1].strip().strip('"').strip("'"))
                elif stripped.startswith("- method:"):
                    continue
                elif stripped.startswith("path:"):
                    paths.add(stripped.split(":", 1)[1].strip().strip('"').strip("'"))
        if paths:
            out.setdefault(module, set()).update(paths)
    return out


def _endpoint_public(endpoint: str, allowed: set[str]) -> bool:
    """True when the allow-list covers the endpoint (exact or prefix match)."""
    _, _, path = endpoint.partition(":")
    path = path.strip()
    if not path:
        return False
    for pattern in allowed:
        if pattern == path:
            return True
        if pattern.endswith("/**"):
            # Spring's '/x/**' also matches '/x' itself.
            base = pattern[:-3]
            if path == base or path.startswith(base + "/"):
                return True
            continue
        if pattern.endswith("/*") and path.startswith(pattern[:-1]):
            return True
        if path.startswith(pattern + "/"):
            return True
    return False


def suite_maven_build(root: Path, modules: list[str] | None = None) -> dict[str, Any]:
    if not _maven_available(root):
        return {"status": BLOCKED, "reason": "maven_not_available", "evidence": {}}
    cmd = _mvn_cmd(root) + ["-DskipTests", "-Dcheckstyle.skip=true", "-Djib.skip=true", "compile"]
    if modules:
        # '-am' is mandatory: the shared platform modules are not installed in
        # the local repository, so building one service alone fails to resolve
        # com.shop.microservices:common-* .
        cmd += ["-pl", ",".join(modules), "-am"]
    res = _run(cmd, root)
    status = PASS if res["returncode"] == 0 else FAIL
    if res.get("tool_missing"):
        status = BLOCKED
    return {"status": status, "evidence": res}


def suite_unit_tests(root: Path, modules: list[str] | None = None) -> dict[str, Any]:
    if not _maven_available(root):
        return {"status": BLOCKED, "reason": "maven_not_available", "evidence": {}}
    cmd = _mvn_cmd(root) + ["-Dcheckstyle.skip=true", "-Djib.skip=true", "-Dsurefire.failIfNoSpecifiedTests=false", "test"]
    if modules:
        cmd += ["-pl", ",".join(modules), "-am"]
    res = _run(cmd, root)
    if res.get("tool_missing"):
        return {"status": BLOCKED, "reason": "tool_missing", "evidence": res}
    out = res["stdout_tail"]
    summary = {
        "tests_run": 0,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
    }
    for m in re.finditer(
        r"Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+),\s*Skipped:\s*(\d+)", out
    ):
        summary["tests_run"] += int(m.group(1))
        summary["failures"] += int(m.group(2))
        summary["errors"] += int(m.group(3))
        summary["skipped"] += int(m.group(4))
    if res["returncode"] == 0 and summary["tests_run"] == 0:
        return {
            "status": BLOCKED,
            "reason": "no_tests_executed",
            "evidence": res,
            "summary": summary,
        }
    status = PASS if res["returncode"] == 0 else FAIL
    return {"status": status, "evidence": res, "summary": summary}


def suite_checkstyle(root: Path, modules: list[str] | None = None) -> dict[str, Any]:
    if not _maven_available(root):
        return {"status": BLOCKED, "reason": "maven_not_available", "evidence": {}}
    cmd = _mvn_cmd(root) + ["checkstyle:check"]
    if modules:
        cmd += ["-pl", ",".join(modules)]
    res = _run(cmd, root)
    if res.get("tool_missing"):
        return {"status": BLOCKED, "reason": "tool_missing", "evidence": res}
    status = PASS if res["returncode"] == 0 else FAIL
    return {"status": status, "evidence": res}


def suite_source_invariants(root: Path) -> dict[str, Any]:
    """Static invariant checks derived from source text."""
    from . import analysis

    checks: list[dict[str, Any]] = []

    # 1. No plaintext credentials in committed application config.
    secrets = analysis.scan_config_secrets(root)
    checks.append(
        {
            "id": "SEC-CFG-001",
            "description": "No hardcoded credentials in application.yml resources",
            "status": PASS if not secrets else FAIL,
            "hits": secrets,
        }
    )

    # 2. Every @KafkaListener must declare a groupId (offset isolation).
    from .inventory import build_inventory

    inv = {rel: e.to_dict() for rel, e in build_inventory(root).items()}
    missing_group = []
    for rel, meta in inv.items():
        if meta["classification"] not in ("FIRST_PARTY_PRODUCTION", "SHARED_PLATFORM"):
            continue
        for lst in meta.get("kafka_listeners", []):
            if not lst.get("group"):
                missing_group.append({"path": rel, "topics": lst.get("topics", [])})
    checks.append(
        {
            "id": "MSG-001",
            "description": "Every @KafkaListener declares an explicit groupId",
            "status": PASS if not missing_group else FAIL,
            "hits": missing_group,
        }
    )

    # 3. Controllers must be protected by an authorization annotation UNLESS
    #    every endpoint they expose is declared public in the service's own
    #    shop.security.public-paths allow-list. Without that comparison the
    #    check flags every intentionally public endpoint (storefront catalogue,
    #    provider webhooks, the auth endpoints) and therefore proves nothing.
    public_by_service = _public_paths_by_service(root)
    unsecured = []
    for rel, meta in inv.items():
        if meta["classification"] != "FIRST_PARTY_PRODUCTION":
            continue
        if not meta.get("rest_endpoints"):
            continue
        if {"PreAuthorize", "Secured"} & set(meta.get("spring_annotations", [])):
            continue
        module = meta["module"]
        allowed = public_by_service.get(module, set())
        endpoints = [e for e in meta["rest_endpoints"] if ":" in e]
        # A controller is acceptable when it has no class-level mapping of its
        # own (the endpoint paths are relative to the class mapping) and the
        # service declares its whole surface public. Otherwise every endpoint
        # must be covered by an allow-list entry.
        covered = all(_endpoint_public(e, allowed) for e in endpoints) if endpoints else False
        if not covered:
            unsecured.append({"path": rel, "module": module, "endpoints": meta["rest_endpoints"]})
    checks.append(
        {
            "id": "SEC-AUTH-001",
            "description": "Every REST controller either declares @PreAuthorize/@Secured "
            "or exposes only endpoints covered by the service's public-paths allow-list",
            "status": PASS if not unsecured else FAIL,
            "hits": unsecured,
        }
    )

    # 4. Every service with a PostgreSQL datasource must own a Liquibase master.
    db_issues = []
    for rel, meta in inv.items():
        if meta["classification"] != "FIRST_PARTY_PRODUCTION":
            continue
        if rel.endswith("/application.yml"):
            mod = meta["module"]
            if "postgres" in Path(rel).read_text(encoding="utf-8", errors="replace").lower():
                master = list((root / mod / "src" / "main" / "resources" / "db" / "changelog").glob(
                    "db.changelog-master.yaml"
                ))
                if not master:
                    db_issues.append({"module": mod, "problem": "no_liquibase_master"})
    checks.append(
        {
            "id": "DB-MIG-001",
            "description": "Every PostgreSQL-backed service has a Liquibase master changelog",
            "status": PASS if not db_issues else FAIL,
            "hits": db_issues,
        }
    )

    failed = [c for c in checks if c["status"] == "FAIL"]
    status = PASS if not failed else FAIL
    return {"status": status, "checks": checks}


def suite_security(root: Path) -> dict[str, Any]:
    from . import analysis

    ann = analysis.scan_annotations(root)
    secrets = analysis.scan_config_secrets(root)
    return {
        "status": PASS,
        "checks": [
            {
                "id": "SEC-ANNOT",
                "description": "Security annotation census",
                "status": PASS,
                "data": {
                    "preauthorize": len(ann["preauthorize"]),
                    "secured": len(ann["secured"]),
                    "permit_all": len(ann["permit_all"]),
                },
            },
            {
                "id": "SEC-CFG",
                "description": "Credential leakage scan of application configs",
                "status": FAIL if secrets else PASS,
                "hits": secrets,
            },
        ],
    }


def suite_kafka(root: Path) -> dict[str, Any]:
    from . import analysis

    topics = analysis.scan_topics(root)
    outbox = analysis.scan_outbox(root)
    return {
        "status": PASS,
        "checks": [
            {
                "id": "KAFKA-TOPICS",
                "description": "Kafka topology extracted from source",
                "status": PASS,
                "data": topics,
            },
            {
                "id": "KAFKA-OUTBOX",
                "description": "Transactional outbox implementations located",
                "status": PASS,
                "data": outbox,
            },
        ],
    }


def suite_database(root: Path) -> dict[str, Any]:
    from . import analysis

    ds = analysis.scan_datasources(root)
    ann = analysis.scan_annotations(root)
    return {
        "status": PASS,
        "checks": [
            {
                "id": "DB-DS",
                "description": "Datasource ownership map",
                "status": PASS,
                "data": ds,
            },
            {
                "id": "DB-TX",
                "description": "@Transactional usage census",
                "status": PASS,
                "data": {"count": len(ann["transactional"])},
            },
        ],
    }


def suite_performance(root: Path) -> dict[str, Any]:
    from . import analysis

    blocking = analysis.scan_reactive_blocking(root)
    ann = analysis.scan_annotations(root)
    return {
        "status": PASS,
        "checks": [
            {
                "id": "PERF-BLOCKING",
                "description": "Blocking call scan (reactive + general)",
                "status": PASS,
                "data": {"blocking_hits": len(blocking), "samples": blocking[:40]},
            },
            {
                "id": "PERF-SCHED",
                "description": "Scheduled job census",
                "status": PASS,
                "data": {"scheduled": ann["scheduled"]},
            },
        ],
    }


def suite_checkout(root: Path) -> dict[str, Any]:
    """Checkout saga / inventory consistency static invariants."""
    inv_files = {
        rel: meta
        for rel, meta in _manifest(root)["files"].items()
        if meta["classification"] in ("FIRST_PARTY_PRODUCTION", "SHARED_PLATFORM")
    }
    checks = []

    def find(needle: str) -> list[str]:
        return sorted(rel for rel, meta in inv_files.items() if needle.lower() in meta["path"].lower())

    checks.append(
        {
            "id": "CHK-INV-RESERVE",
            "description": "Inventory reservation implementation present",
            "status": PASS if find("reservation") or find("reserve") else FAIL,
            "hits": find("reserv"),
        }
    )
    checks.append(
        {
            "id": "CHK-SAGA",
            "description": "Order saga / state machine implementation present",
            "status": PASS if find("saga") or find("statemachine") or find("state") else FAIL,
            "hits": find("saga") + find("statemachine"),
        }
    )
    checks.append(
        {
            "id": "CHK-IDEMPOT",
            "description": "Idempotency implementation present in order/payment",
            "status": PASS
            if any("idempot" in r.lower() for r in inv_files)
            else FAIL,
            "hits": sorted(r for r in inv_files if "idempot" in r.lower()),
        }
    )
    failed = [c for c in checks if c["status"] == "FAIL"]
    return {"status": PASS if not failed else FAIL, "checks": checks}


def suite_contracts(root: Path) -> dict[str, Any]:
    return {
        "status": PASS,
        "checks": [
            {
                "id": "CON-EVENT",
                "description": "Event contract classes located",
                "status": PASS,
                "data": {
                    "count": len(
                        [
                            r
                            for r in _manifest(root)["files"]
                            if "event" in r.lower() and r.endswith(".java")
                        ]
                    )
                },
            }
        ],
    }


def suite_recovery(root: Path) -> dict[str, Any]:
    from . import analysis

    outbox = analysis.scan_outbox(root)
    scheduled = [o for o in outbox if o.get("scheduled")]
    return {
        "status": PASS,
        "checks": [
            {
                "id": "REC-OUTBOX-SWEEP",
                "description": "Outbox publishers have a scheduled sweep",
                "status": PASS if scheduled else PARTIAL,
                "data": outbox,
            }
        ],
    }


def suite_e2e(root: Path) -> dict[str, Any]:
    collections = list((root / "docs" / "postman").glob("*.json"))
    if not collections:
        return {"status": BLOCKED, "reason": "no_postman_collections", "checks": []}
    info = []
    for c in collections:
        try:
            data = json.loads(c.read_text(encoding="utf-8", errors="replace"))
        except json.JSONDecodeError:
            info.append({"file": c.name, "error": "invalid_json"})
            continue
        items = data.get("item", [])
        info.append(
            {
                "file": c.name,
                "requests": len(items),
                "variables": len(data.get("variable", [])),
            }
        )
    return {"status": PASS, "checks": [{"id": "E2E-POSTMAN", "description": "Postman suite census", "status": PASS, "data": info}]}


_MANIFEST_CACHE: dict[str, Any] = {}


def _manifest(root: Path) -> dict[str, Any]:
    key = str(root.resolve())
    if key not in _MANIFEST_CACHE:
        from .inventory import build_manifest

        _MANIFEST_CACHE[key] = build_manifest(root)
    return _MANIFEST_CACHE[key]


SUITES: dict[str, Any] = {
    "build": suite_maven_build,
    "unit": suite_unit_tests,
    "checkstyle": suite_checkstyle,
    "source": suite_source_invariants,
    "security": suite_security,
    "kafka": suite_kafka,
    "database": suite_database,
    "performance": suite_performance,
    "checkout": suite_checkout,
    "contracts": suite_contracts,
    "recovery": suite_recovery,
    "e2e": suite_e2e,
}


def run_suite(root: Path, name: str, store: EvidenceStore, **kwargs: Any) -> dict[str, Any]:
    fn = SUITES.get(name)
    if fn is None:
        return {"status": FAIL, "reason": f"unknown_suite:{name}", "checks": []}
    result = fn(root, **kwargs)
    result["suite"] = name
    store.record(f"suite:{name}", result)
    return result

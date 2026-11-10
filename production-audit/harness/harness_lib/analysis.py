"""Static analysis passes over the real source tree.

Each pass produces structured, evidence-bearing output derived from
actual source text -- never from documentation claims.
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

POM_NS = "{http://maven.apache.org/POM/4.0.0}"


def _text(pom: Path) -> str:
    try:
        return pom.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def maven_modules(root: Path) -> dict[str, Any]:
    """Parse every module pom and build the inter-module dependency graph."""
    poms = sorted(root.glob("**/pom.xml"))
    poms = [p for p in poms if "production-audit" not in p.parts and "target" not in p.parts]
    modules: dict[str, dict[str, Any]] = {}
    edges: list[dict[str, str]] = []
    for pom in poms:
        rel = pom.relative_to(root).as_posix()
        artifact = pom.parent.name
        content = _text(pom)
        deps: list[str] = []
        deps_scope: list[dict[str, str]] = []
        try:
            tree = ET.fromstring(content)
        except ET.ParseError:
            tree = None
        if tree is not None:
            for dep in tree.iter(f"{POM_NS}dependency"):
                gid = dep.findtext(f"{POM_NS}groupId") or ""
                aid = dep.findtext(f"{POM_NS}artifactId") or ""
                scope = dep.findtext(f"{POM_NS}scope") or "compile"
                if gid == "com.shop.microservices":
                    deps.append(aid)
                    deps_scope.append({"artifact": aid, "scope": scope})
                    edges.append({"from": artifact, "to": aid})
        modules[artifact] = {
            "path": rel,
            "artifact": artifact,
            "depends_on": sorted(set(deps)),
            "dependency_scopes": deps_scope,
            "is_shared": "utils/" in rel.replace("\\", "/"),
            "is_service": rel.count("/") == 1 and rel.endswith("-service") or artifact.endswith("-service"),
            "has_tests": (pom.parent / "src" / "test").exists(),
        }
    return {"modules": modules, "edges": edges, "pom_count": len(poms)}


def _java_files(root: Path, sub: str = "src/main/java") -> list[Path]:
    out = []
    for p in root.rglob("*.java"):
        rel = p.relative_to(root).as_posix()
        if "production-audit" in rel or "/target/" in rel:
            continue
        if f"/{sub}/" in "/" + rel:
            out.append(p)
    return out


def scan_annotations(root: Path) -> dict[str, Any]:
    """Collect security / transactional / scheduling / kafka usage."""
    findings: dict[str, list[dict[str, Any]]] = {
        "preauthorize": [],
        "secured": [],
        "permit_all": [],
        "transactional": [],
        "scheduled": [],
        "kafka_listener": [],
        "kafka_producer": [],
        "reactive_blocking": [],
        "hardcoded_secret": [],
        "cacheable": [],
    }
    secret_re = re.compile(
        r"(?i)\b(password|secret|api[_-]?key|token)\s*=\s*\"([^\"]{6,})\""
    )
    for p in _java_files(root):
        rel = p.relative_to(root).as_posix()
        text = p.read_text(encoding="utf-8", errors="replace")
        lines = text.splitlines()
        for i, line in enumerate(lines, 1):
            if "@PreAuthorize" in line:
                findings["preauthorize"].append({"path": rel, "line": i, "code": line.strip()})
            if "@Secured" in line:
                findings["secured"].append({"path": rel, "line": i, "code": line.strip()})
            if "permitAll" in line:
                findings["permit_all"].append({"path": rel, "line": i, "code": line.strip()})
            if "@Transactional" in line:
                findings["transactional"].append({"path": rel, "line": i, "code": line.strip()})
            if "@Scheduled" in line:
                findings["scheduled"].append({"path": rel, "line": i, "code": line.strip()})
            if "@KafkaListener" in line:
                findings["kafka_listener"].append({"path": rel, "line": i, "code": line.strip()})
            if "KafkaTemplate" in line and ("private" in line or "public" in line):
                findings["kafka_producer"].append({"path": rel, "line": i, "code": line.strip()})
            if "@Cacheable" in line:
                findings["cacheable"].append({"path": rel, "line": i, "code": line.strip()})
            m = secret_re.search(line)
            if m and not line.strip().startswith("//"):
                findings["hardcoded_secret"].append({"path": rel, "line": i, "code": line.strip()})
    return findings


def scan_reactive_blocking(root: Path) -> list[dict[str, Any]]:
    """Find blocking calls used inside reactive (Mono/Flux) chains."""
    hits: list[dict[str, Any]] = []
    blocking = (
        "jdbcTemplate.query",
        "entityManager.createQuery",
        ".block(",
        "Thread.sleep",
        "restTemplate.",
        "webClient.block",
    )
    for p in _java_files(root):
        rel = p.relative_to(root).as_posix()
        if "gateway" in rel:
            continue
        text = p.read_text(encoding="utf-8", errors="replace")
        for i, line in enumerate(text.splitlines(), 1):
            if any(b in line for b in blocking):
                hits.append({"path": rel, "line": i, "code": line.strip()})
    return hits


def scan_config_secrets(root: Path) -> list[dict[str, Any]]:
    """Look for credentials committed in configuration resources."""
    hits: list[dict[str, Any]] = []
    pattern = re.compile(
        r"(?i)^\s*(password|secret|api[-_]?key|client[-_]?secret|private[-_]?key)\s*[:=]\s*(?!\$\{|\$ENV|)\"?([^\s\"']{4,})"
    )
    for p in root.rglob("application*.y*ml"):
        rel = p.relative_to(root).as_posix()
        if "production-audit" in rel:
            continue
        for i, line in enumerate(p.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            m = pattern.match(line)
            if m:
                hits.append({"path": rel, "line": i, "key": m.group(1), "value_masked": m.group(2)[:2] + "***"})
    return hits


def scan_topics(root: Path) -> dict[str, Any]:
    """Extract Kafka topic names from producers, consumers and configs."""
    produced: dict[str, set[str]] = {}
    consumed: dict[str, set[str]] = {}
    groups: dict[str, set[str]] = {}
    for p in _java_files(root):
        rel = p.relative_to(root).as_posix()
        text = p.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r"\.send\(\s*\"([^\"]+)\"", text):
            produced.setdefault(rel, set()).add(m.group(1))
        for m in re.finditer(r"\.send\(\s*TopicConfig\.(\w+)", text):
            produced.setdefault(rel, set()).add(f"TopicConfig.{m.group(1)}")
        for m in re.finditer(r"@KafkaListener\s*\((.*?)\)\s*(?:\n|public|private|void|@)", text, re.S):
            blob = m.group(1)
            tm = re.search(r'topics\s*=\s*(?:"([^"]+)"|\{([^}]*)\})', blob)
            topics: list[str] = []
            if tm:
                if tm.group(1):
                    topics = [tm.group(1)]
                elif tm.group(2):
                    topics = [
                        x.strip().strip('"') for x in tm.group(2).split(",") if x.strip().strip('"')
                    ]
            gm = re.search(r'groupId\s*=\s*"([^"]*)"', blob)
            for t in topics:
                consumed.setdefault(rel, set()).add(t)
                if gm:
                    groups.setdefault(t, set()).add(gm.group(1))
    return {
        "producers": {k: sorted(v) for k, v in produced.items() if v},
        "consumers": {k: sorted(v) for k, v in consumed.items() if v},
        "topic_to_groups": {k: sorted(v) for k, v in groups.items()},
    }


def scan_outbox(root: Path) -> list[dict[str, Any]]:
    """Locate transactional outbox implementations."""
    hits: list[dict[str, Any]] = []
    for p in _java_files(root):
        rel = p.relative_to(root).as_posix()
        text = p.read_text(encoding="utf-8", errors="replace")
        if "outbox" in rel.lower() or "Outbox" in text:
            hits.append(
                {
                    "path": rel,
                    "has_outbox_entity": "OutboxEvent" in text or "outbox_event" in text,
                    "has_publisher": "Publisher" in text or "publish" in text.lower(),
                    "scheduled": "@Scheduled" in text,
                    "transactional": "@Transactional" in text,
                }
            )
    return hits


def scan_datasources(root: Path) -> dict[str, Any]:
    """Extract per-service datasource and external dependency config."""
    out: dict[str, Any] = {}
    for p in root.rglob("application.yml"):
        rel = p.relative_to(root).as_posix()
        if "production-audit" in rel:
            continue
        text = p.read_text(encoding="utf-8", errors="replace")
        jdbc = re.findall(r"jdbc:postgresql://([^\s\"']+)", text)
        out[rel] = {
            "datasources": sorted(set(jdbc)),
            "mentions_kafka": "kafka" in text,
            "mentions_redis": "redis" in text,
            "mentions_elasticsearch": "elasticsearch" in text,
            "mentions_stripe": "stripe" in text,
            "mentions_keycloak": "keycloak" in text,
            "mentions_rustfs": "rustfs" in text or "s3" in text.lower(),
        }
    return out

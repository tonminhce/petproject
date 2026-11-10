"""Repository inventory + source manifest generation.

Classifies every tracked/untracked first-party file, computes a stable
content hash and line count, and reconciles the manifest against the
existing coverage ledger so that changed files invalidate stale review
evidence.
"""

from __future__ import annotations

import hashlib
import os
import re
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

JAVA_MAIN = "FIRST_PARTY_PRODUCTION"
JAVA_TEST = "FIRST_PARTY_TEST"
SHARED = "SHARED_PLATFORM"
MIGRATION = "PERSISTENCE_MIGRATION"
CONTRACT = "CONTRACT"
INFRA = "INFRASTRUCTURE"
GENERATED = "GENERATED"
VENDOR = "VENDOR"
DOCS = "DOCUMENTATION"
BUILD = "BUILD_ARTIFACT"
UNKNOWN = "UNKNOWN"

IN_SCOPE_CLASSES = {
    JAVA_MAIN,
    JAVA_TEST,
    SHARED,
    MIGRATION,
    CONTRACT,
    INFRA,
}

EXCLUDE_DIR_NAMES = {
    ".git",
    "target",
    "node_modules",
    ".idea",
    ".vscode",
    "production-audit",
    ".mvn",
    ".superpowers",
    ".gradle",
    "dist",
    "build",
    ".cache",
}

# Directories whose contents are generated / vendored and never first-party.
VENDOR_DIR_MARKERS = ("/vendor/", "/node_modules/", "/third_party/", "/thirdparty/")

PACKAGE_RE = re.compile(r"^package\s+([\w.]+)\s*;", re.M)
CLASS_RE = re.compile(r"\b(?:class|interface|enum|record|@interface)\s+(\w+)")
SPRING_BEAN_RE = re.compile(
    r"@(Service|Component|Repository|Controller|RestController|Configuration|ControllerAdvice"
    r"|Entity|Scheduled|Transactional|KafkaListener|EventListener|PreAuthorize|Secured)\b"
)
KAFKA_LISTENER_RE = re.compile(r"@KafkaListener\s*\(([^)]*)\)", re.S)
TOPIC_RE = re.compile(r'topics\s*=\s*(?:"([^"]+)"|\{([^}]*)\})')
GROUP_RE = re.compile(r'groupId\s*=\s*"([^"]*)"')
REST_MAPPING_RE = re.compile(
    r"@(Get|Post|Put|Delete|Patch|Request)Mapping\s*(?:\(([^)]*)\))?"
)


@dataclass
class FileEntry:
    path: str
    module: str
    classification: str
    sha256: str
    lines: int
    size: int
    package: str = ""
    types: list[str] = field(default_factory=list)
    spring_annotations: list[str] = field(default_factory=list)
    kafka_listeners: list[dict[str, Any]] = field(default_factory=list)
    rest_endpoints: list[str] = field(default_factory=list)
    in_scope: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {
            "path": self.path,
            "module": self.module,
            "classification": self.classification,
            "sha256": self.sha256,
            "lines": self.lines,
            "size": self.size,
            "package": self.package,
            "types": self.types,
            "spring_annotations": sorted(set(self.spring_annotations)),
            "kafka_listeners": self.kafka_listeners,
            "rest_endpoints": self.rest_endpoints,
            "in_scope": self.in_scope,
        }


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def module_of(rel: str) -> str:
    parts = Path(rel).parts
    if parts[0] == "utils" and len(parts) > 1:
        return f"utils/{parts[1]}"
    if len(parts) > 1 and (parts[1] == "src" or parts[1].endswith("-service")):
        return parts[0]
    return parts[0] if parts else "root"


def _is_shared_module(mod: str) -> bool:
    return mod.startswith("utils/")


def classify(rel: str, path: Path) -> str:
    p = rel.replace("\\", "/")
    if any(m in p for m in VENDOR_DIR_MARKERS):
        return VENDOR
    name = path.name
    if p.endswith(".java"):
        if "/src/test/" in p or "/src/it/" in p:
            return JAVA_TEST
        return SHARED if _is_shared_module(module_of(p)) else JAVA_MAIN
    if "/db/changelog/" in p or name.endswith(".sql"):
        return MIGRATION
    if p.startswith("docs/architecture/") or name.endswith(".openapi.yaml") or "/openapi/" in p:
        return CONTRACT
    if p.startswith(("docker/", "k8s/", "config/", ".github/", "scripts/", "load-tests/", "mock-services/")):
        return INFRA
    if name in ("pom.xml",) or p.startswith(".mvn/"):
        return BUILD
    if name.endswith((".md", ".adoc", ".txt")) or p.startswith("docs/"):
        return DOCS
    if name.endswith((".yml", ".yaml", ".properties", ".json", ".xml")):
        return INFRA
    return UNKNOWN


# The canonical API path constants from common-core ApiPaths. Controllers
# reference them symbolically (@RequestMapping(ApiPaths.PRODUCTS)), so the
# harness resolves them to the literal path before matching against a
# service's public-paths allow-list.
API_PATHS = {
    "API": "/api", "V1": "/v1", "API_V1": "/api/v1",
    "AUTH": "/api/v1/auth", "USERS": "/api/v1/users", "ROLES": "/api/v1/roles",
    "PRODUCTS": "/api/v1/products", "CATEGORIES": "/api/v1/categories",
    "BRANDS": "/api/v1/brands", "CARTS": "/api/v1/carts", "ORDERS": "/api/v1/orders",
    "PAYMENTS": "/api/v1/payments", "INVENTORY": "/api/v1/inventory",
    "SHIPPINGS": "/api/v1/shippings", "FAVOURITES": "/api/v1/favourites",
    "PROMOTIONS": "/api/v1/promotions", "MEDIAS": "/api/v1/medias",
    "BACKOFFICE_MEDIAS": "/api/v1/backoffice/medias",
    "NOTIFICATIONS": "/api/v1/notifications",
    "PAYMENT_NOTIFICATIONS": "/api/v1/payment-notifications", "EMAILS": "/api/v1/emails",
    "RATINGS": "/api/v1/ratings",
    "INTERNAL_PRODUCT_MEDIA_REFERENCES": "/internal/products/media-references",
    "BACKOFFICE_RATINGS": "/api/v1/backoffice/ratings",
    "BACKOFFICE_PRODUCTS": "/api/v1/backoffice/products",
    "BACKOFFICE_CATEGORIES": "/api/v1/backoffice/categories",
    "BACKOFFICE_BRANDS": "/api/v1/backoffice/brands",
    "SEARCH": "/api/v1/search", "BACKOFFICE_SEARCH": "/api/v1/backoffice/search",
    "BACKOFFICE_PROMOTIONS": "/api/v1/backoffice/promotions",
    "BACKOFFICE_TAX_CLASSES": "/api/v1/backoffice/tax-classes",
    "BACKOFFICE_TAX_RATES": "/api/v1/backoffice/tax-rates",
    "TAX_CALCULATION": "/api/v1/tax/calculate",
    "BACKOFFICE_NOTIFICATIONS": "/api/v1/backoffice/notifications",
    "BACKOFFICE_PAYMENTS": "/api/v1/backoffice/payments",
    "WEBHOOK_PAYMENTS": "/api/v1/webhooks/payments",
    "BACKOFFICE_SHIPMENTS": "/api/v1/backoffice/shipments",
    "WEBHOOK_SHIPPING": "/api/v1/webhooks/shipping",
}

CLASS_MAPPING_RE = re.compile(r"@RequestMapping\s*\(\s*ApiPaths\.(\w+)\s*\)")
LITERAL_MAPPING_RE = re.compile(r'@RequestMapping\s*\(\s*"([^"]*)"\s*\)')


def parse_java(path: Path, text: str) -> tuple[str, list[str], list[str], list[dict[str, Any]], list[str]]:
    pkg = ""
    m = PACKAGE_RE.search(text)
    if m:
        pkg = m.group(1)
    types = sorted(set(CLASS_RE.findall(text)))
    annotations = sorted(set(SPRING_BEAN_RE.findall(text)))
    listeners: list[dict[str, Any]] = []
    for raw in KAFKA_LISTENER_RE.findall(text):
        topics: list[str] = []
        tm = TOPIC_RE.search(raw)
        if tm:
            if tm.group(1):
                topics = [tm.group(1).strip()]
            elif tm.group(2):
                topics = [
                    x.strip().strip('"')
                    for x in tm.group(2).split(",")
                    if x.strip().strip('"')
                ]
        gm = GROUP_RE.search(raw)
        listeners.append({"topics": topics, "group": gm.group(1) if gm else ""})
    # Class-level mapping (the FIRST @RequestMapping in the file is the one on
    # the controller class) is used as the prefix for every method endpoint.
    base = ""
    cm = CLASS_MAPPING_RE.search(text)
    lm = LITERAL_MAPPING_RE.search(text)
    if cm:
        base = API_PATHS.get(cm.group(1), "")
    elif lm:
        base = lm.group(1)
    endpoints: list[str] = []
    for verb, args in REST_MAPPING_RE.findall(text):
        args = args or ""
        if "ApiPaths." in args:
            continue  # the class-level mapping, not an endpoint
        path = ""
        pm = re.search(r'["\']([^"\']+)["\']', args)
        if pm:
            path = pm.group(1)
        if base:
            # A method-level path is always relative to the class-level
            # mapping, whether or not it starts with a slash.
            path = base + path
        endpoints.append(f"{verb.upper()}:{path}")
    return pkg, types, annotations, listeners, endpoints


def walk_repo(root: Path) -> list[Path]:
    found: list[Path] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIR_NAMES and not d.startswith(".git")]
        for fn in filenames:
            found.append(Path(dirpath) / fn)
    return found


def build_inventory(root: Path) -> dict[str, FileEntry]:
    entries: dict[str, FileEntry] = {}
    for path in walk_repo(root):
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        cls = classify(rel, path)
        try:
            digest = sha256_of(path)
            raw = path.read_bytes()
            size = len(raw)
            lines = raw.count(b"\n") + (1 if raw and not raw.endswith(b"\n") else 0)
            text = raw.decode("utf-8", errors="replace")
        except OSError:
            continue
        entry = FileEntry(
            path=rel,
            module=module_of(rel),
            classification=cls,
            sha256=digest,
            lines=lines,
            size=size,
            in_scope=cls in IN_SCOPE_CLASSES,
        )
        if cls in (JAVA_MAIN, JAVA_TEST, SHARED):
            pkg, types, anns, listeners, endpoints = parse_java(path, text)
            entry.package = pkg
            entry.types = types
            entry.spring_annotations = anns
            entry.kafka_listeners = listeners
            entry.rest_endpoints = endpoints
        entries[rel] = entry
    return entries


def git_state(root: Path) -> dict[str, Any]:
    def run(*args: str) -> str:
        try:
            out = subprocess.run(
                ["git", *args], cwd=root, capture_output=True, text=True, timeout=60
            )
            return out.stdout.strip()
        except Exception as exc:  # pragma: no cover - defensive
            return f"ERROR:{exc}"

    status = run("status", "--porcelain")
    return {
        "commit": run("rev-parse", "HEAD"),
        "branch": run("rev-parse", "--abbrev-ref", "HEAD"),
        "status_porcelain": status,
        "dirty_files": [ln[3:] for ln in status.splitlines() if len(ln) > 3],
        "dirty": bool(status),
        "last_commit_subject": run("log", "-1", "--format=%s"),
        "last_commit_date": run("log", "-1", "--format=%ad"),
    }


def build_manifest(root: Path) -> dict[str, Any]:
    entries = build_inventory(root)
    gstate = git_state(root)
    by_class: dict[str, int] = {}
    by_module: dict[str, dict[str, Any]] = {}
    for e in entries.values():
        by_class[e.classification] = by_class.get(e.classification, 0) + 1
        m = by_module.setdefault(
            e.module, {"files": 0, "lines": 0, "main": 0, "test": 0, "shared": 0, "other": 0}
        )
        m["files"] += 1
        m["lines"] += e.lines
        if e.classification == JAVA_MAIN:
            m["main"] += 1
        elif e.classification == JAVA_TEST:
            m["test"] += 1
        elif e.classification == SHARED:
            m["shared"] += 1
        else:
            m["other"] += 1
    return {
        "schema_version": 1,
        "generated_at": subprocess.run(
            ["python", "-c", "import datetime;print(datetime.datetime.now().isoformat(timespec='seconds'))"],
            capture_output=True,
            text=True,
        ).stdout.strip(),
        "root": str(root),
        "git": gstate,
        "totals": {
            "files": len(entries),
            "lines": sum(e.lines for e in entries.values()),
            "in_scope_files": sum(1 for e in entries.values() if e.in_scope),
            "in_scope_lines": sum(e.lines for e in entries.values() if e.in_scope),
        },
        "by_classification": dict(sorted(by_class.items())),
        "by_module": dict(sorted(by_module.items())),
        "files": {rel: e.to_dict() for rel, e in sorted(entries.items())},
    }


def reconcile_manifest(manifest: dict[str, Any], coverage: dict[str, Any]) -> dict[str, Any]:
    """Invalidate review evidence for files whose hash changed or vanished."""
    files = manifest.get("files", {})
    reviews = coverage.setdefault("reviews", {})
    invalidated: list[dict[str, Any]] = []
    for rel, review in list(reviews.items()):
        meta = files.get(rel)
        if meta is None:
            reviews.pop(rel, None)
            invalidated.append({"path": rel, "reason": "file_removed"})
            continue
        if review.get("sha256") and review["sha256"] != meta["sha256"]:
            invalidated.append(
                {
                    "path": rel,
                    "reason": "content_changed",
                    "old_sha256": review["sha256"],
                    "new_sha256": meta["sha256"],
                }
            )
            reviews.pop(rel, None)
    coverage["invalidated"] = invalidated
    coverage["invalidated_at"] = manifest.get("generated_at")
    return coverage

from __future__ import annotations

import hashlib
import subprocess
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Iterable

from .. import config
from ..persistence.store import sha256_file, utc_now, write_json

SKIPPED_DIRECTORIES = (
    ".git",
    ".gitnexus",
    ".idea",
    ".mvn/wrapper",
    ".run",
    ".superpowers",
    ".worktrees",
    "node_modules",
    "target",
    "dist",
    "build",
    ".idea",
    ".vscode",
    ".pytest_cache",
    "__pycache__",
    ".venv",
)

LANGUAGE_BY_SUFFIX = {
    ".java": "java",
    ".kt": "kotlin",
    ".groovy": "groovy",
    ".sh": "shell",
    ".bash": "shell",
    ".py": "python",
    ".js": "javascript",
    ".mjs": "javascript",
    ".cjs": "javascript",
    ".ts": "typescript",
    ".tsx": "typescript",
    ".jsx": "javascript",
    ".yaml": "yaml",
    ".yml": "yaml",
    ".properties": "properties",
    ".sql": "sql",
    ".xml": "xml",
    ".json": "json",
    ".html": "html",
    ".htm": "html",
    ".vue": "vue",
    ".tf": "terraform",
    ".gradle": "groovy",
    ".conf": "conf",
    ".cfg": "conf",
    ".ini": "conf",
    ".toml": "toml",
    ".md": "markdown",
    ".sqlx": "sql",
}

COMMENT_GATE_SUFFIXES = (
    ".java",
    ".kt",
    ".groovy",
    ".sh",
    ".bash",
    ".py",
    ".js",
    ".mjs",
    ".cjs",
    ".ts",
    ".tsx",
    ".jsx",
    ".yaml",
    ".yml",
    ".properties",
    ".sql",
    ".xml",
    ".tf",
    ".gradle",
    ".conf",
    ".cfg",
    ".ini",
    ".toml",
    ".html",
    ".htm",
    ".vue",
)

SECRET_BEARING_NAMES = (".env", ".env.local", ".env.prod", ".env.development", "id_rsa", "id_ed25519")


@dataclass
class SourceFile:
    path: str
    category: str
    module: str
    language: str
    lines: int
    bytes: int
    sha256: str
    git_blob: str | None
    tracked: bool
    comment_gate: bool
    reviewed_lines: list[int] = field(default_factory=list)
    coverage_state: str = "DISCOVERED"

    def to_document(self) -> dict[str, Any]:
        return asdict(self)


def _git(root: Path, arguments: list[str]) -> str:
    try:
        completed = subprocess.run(
            ["git", "-C", str(root), *arguments],
            capture_output=True,
            text=True,
            check=False,
            timeout=120,
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    return completed.stdout.strip()


def _git_blob_map(root: Path) -> dict[str, str]:
    output = _git(root, ["ls-files", "-s"])
    mapping: dict[str, str] = {}
    for line in output.splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[0] == "100644":
            mapping[parts[3]] = parts[1]
    return mapping


def classify(relative_path: str, root: Path) -> tuple[str, str]:
    path = Path(relative_path)
    parts = path.parts
    module = parts[0] if parts else "root"
    if module == "utils" and len(parts) > 1:
        module = "utils/" + parts[1]

    if module == ".claude":
        return "VENDORED_TOOLING", module
    if module == "production-audit":
        return "HARNESS", module
    if path.suffix.lower() in (".html", ".htm") and module == "docs":
        return "GENERATED", module
    if "src" in parts and "test" in parts:
        return "TEST_CODE", module
    if module.startswith("utils/"):
        return "SHARED_LIBRARY", module
    if module in config.SERVICE_MODULES:
        return "PRODUCTION_CODE", module
    if module == "k8s" or module == "docker" or module == "config" or module == "scripts":
        return "INFRASTRUCTURE", module
    if module == "docs":
        return "DOCUMENTATION", module
    if module == "tests" or module == "load-tests":
        return "INFRASTRUCTURE", module
    if module == "mock-services":
        return "INFRASTRUCTURE", module
    if path.name in ("pom.xml", "mvnw", "mvnw.cmd", "lombok.config"):
        return "BUILD", module
    if path.suffix.lower() == ".md":
        return "DOCUMENTATION", module
    if path.name.startswith("openapi") or "openapi" in path.name.lower():
        return "CONTRACT", module
    return "INFRASTRUCTURE", module


def _is_skipped(relative_path: Path) -> bool:
    for part in relative_path.parts:
        if part in SKIPPED_DIRECTORIES:
            return True
        if part.startswith("target") and part == "target":
            return True
    if relative_path.name == ".flattened-pom.xml":
        return True
    return False


def collect_files(root: Path) -> list[Path]:
    collected: list[Path] = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(root)
        if _is_skipped(relative):
            continue
        if relative.parts and relative.parts[0] == "production-audit" and relative.parts[-1] != "harness.py":
            if len(relative.parts) < 3 or relative.parts[1] != "harness":
                continue
        collected.append(path)
    return sorted(collected)


def build_inventory(root: Path | None = None, include_harness: bool = True) -> dict[str, Any]:
    repository = root or config.repository_root()
    blob_map = _git_blob_map(repository)
    tracked_output = set(_git(repository, ["ls-files"]).splitlines())
    entries: list[SourceFile] = []

    for path in collect_files(repository):
        relative = path.relative_to(repository).as_posix()
        if not include_harness and relative.startswith("production-audit/"):
            continue
        if path.name in SECRET_BEARING_NAMES:
            continue
        if path.suffix.lower() not in LANGUAGE_BY_SUFFIX:
            continue
        if path.name in ("Dockerfile", "Containerfile", "Makefile"):
            pass
        else:
            if path.suffix.lower() not in LANGUAGE_BY_SUFFIX:
                continue
        category, module = classify(relative, repository)
        try:
            payload = path.read_bytes()
        except OSError:
            continue
        line_count = payload.count(b"\n") + (1 if payload and not payload.endswith(b"\n") else 0)
        entries.append(
            SourceFile(
                path=relative,
                category=category,
                module=module,
                language=LANGUAGE_BY_SUFFIX.get(path.suffix.lower(), "unknown"),
                lines=line_count,
                bytes=len(payload),
                sha256=sha256_file(path),
                git_blob=blob_map.get(relative),
                tracked=relative in tracked_output,
                comment_gate=path.suffix.lower() in COMMENT_GATE_SUFFIXES
                and category not in ("DOCUMENTATION", "GENERATED", "VENDORED_TOOLING", "HARNESS"),
            )
        )

    summary: dict[str, int] = {}
    language_summary: dict[str, int] = {}
    module_summary: dict[str, int] = {}
    for entry in entries:
        summary[entry.category] = summary.get(entry.category, 0) + 1
        language_summary[entry.language] = language_summary.get(entry.language, 0) + 1
        module_summary[entry.module] = module_summary.get(entry.module, 0) + 1

    return {
        "generated_at": utc_now(),
        "repository_root": str(repository),
        "revision": _git(repository, ["rev-parse", "HEAD"]),
        "branch": _git(repository, ["rev-parse", "--abbrev-ref", "HEAD"]),
        "file_count": len(entries),
        "total_lines": sum(entry.lines for entry in entries),
        "category_summary": summary,
        "language_summary": language_summary,
        "module_summary": module_summary,
        "source_fingerprint": source_fingerprint(repository),
        "files": [entry.to_document() for entry in entries],
    }


def source_fingerprint(root: Path | None = None) -> str:
    repository = root or config.repository_root()
    digest = hashlib.sha256()
    for path in sorted(_fingerprint_files(repository), key=lambda item: item.as_posix()):
        if path.name in SECRET_BEARING_NAMES or path.name.startswith(".env"):
            continue
        try:
            payload = path.read_bytes()
        except OSError:
            continue
        digest.update(path.relative_to(repository).as_posix().encode("utf-8"))
        digest.update(b"\x00")
        digest.update(hashlib.sha256(payload).digest())
    return digest.hexdigest()


def harness_fingerprint(root: Path | None = None) -> str:
    repository = root or config.repository_root()
    digest = hashlib.sha256()
    harness_root = repository / "production-audit" / "harness"
    if not harness_root.is_dir():
        return ""
    for path in sorted(harness_root.rglob("*.py"), key=lambda item: item.as_posix()):
        if path.name.startswith(".") or "__pycache__" in path.parts:
            continue
        try:
            payload = path.read_bytes()
        except OSError:
            continue
        digest.update(path.relative_to(repository).as_posix().encode("utf-8"))
        digest.update(b"\x00")
        digest.update(hashlib.sha256(payload).digest())
    return digest.hexdigest()


def _fingerprint_files(repository: Path) -> list[Path]:
    files = [
        path for path in collect_files(repository)
        if not _is_harness_path(path, repository)
    ]
    files.extend(_audit_configuration_files(repository))
    return files


def _is_harness_path(path: Path, repository: Path) -> bool:
    try:
        relative = path.relative_to(repository)
    except ValueError:
        return False
    parts = relative.parts
    return len(parts) >= 3 and parts[0] == "production-audit" and parts[1] == "harness"


def _audit_configuration_files(repository: Path) -> list[Path]:
    configuration_root = repository / "production-audit" / "config"
    if not configuration_root.is_dir():
        return []
    return [path for path in configuration_root.rglob("*.json") if path.is_file()]


def persist_inventory(document: dict[str, Any]) -> Path:
    return write_json(config.source_manifest_path(), document)


def load_inventory() -> dict[str, Any]:
    from ..persistence.store import read_json

    return read_json(config.source_manifest_path(), {}) or {}


def inventory_files(document: dict[str, Any] | None = None) -> Iterable[dict[str, Any]]:
    data = document or load_inventory()
    return data.get("files", [])

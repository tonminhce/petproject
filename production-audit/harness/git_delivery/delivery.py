from __future__ import annotations

import re
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .. import config
from ..persistence.store import read_json, utc_now, write_json

CREDENTIAL_TOKEN_PATTERN = re.compile(
    r"(?i)\b(?:sk|pk|rk)_(?:live|test)_[A-Za-z0-9]{16,}"
    r"|\bAKIA[0-9A-Z]{16}\b"
    r"|\bgh[pousr]_[A-Za-z0-9]{20,}\b"
    r"|\bxox[baprs]-[A-Za-z0-9-]{10,}\b"
    r"|\bjdbc:[a-z0-9]+://[^\s:/@]+:[^\s@/]+@"
    r"|\bwhsec_(?!test_)[A-Za-z0-9]{16,}\b"
    r"|-----BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY-----"
)

SECRET_KEY_NAME_PATTERN = re.compile(
    r"(?i)\b(?:password|passwd|secret[_-]?key|secret|api[_-]?key|apikey|access[_-]?key"
    r"|client[_-]?secret|private[_-]?key|auth[_-]?token|access[_-]?token|refresh[_-]?token)\b"
)

ASSIGNMENT_PATTERN = re.compile(
    r"(?i)(?:^|[\"'\s:=,(\[])\s*[\"']?([A-Za-z0-9_.\-]*(?:password|passwd|secret|api[_-]?key|apikey|access[_-]?key|client[_-]?secret|private[_-]?key|token)[A-Za-z0-9_.\-]*)[\"']?\s*[=:]\s*[\"']([^\"'\n]{8,})[\"']"
)

TEST_CREDENTIAL_PREFIXES = (
    "whsec_test_",
)

PLACEHOLDER_VALUES = {
    "password",
    "passwd",
    "username",
    "secret",
    "client_secret",
    "clientsecret",
    "access_key",
    "accesskey",
    "api_key",
    "apikey",
    "private_key",
    "privatekey",
    "changeme",
    "change_me",
    "change-me",
    "placeholder",
    "example",
    "dummy",
    "redacted",
    "none",
    "null",
    "true",
    "false",
    "admin",
    "test",
    "value",
    "default",
    "your_password",
    "your-secret",
    "your_secret",
    "not-a-secret",
}

SECRET_FILE_NAMES = (".env", ".env.local", ".env.prod", ".env.development", "id_rsa", "id_ed25519", "credentials.json")

GIT_DELIVERY_STATES = (
    "NOT_READY",
    "READY_TO_COMMIT",
    "COMMITTED",
    "PUSH_PENDING",
    "PUSHED",
    "PUSH_BLOCKED",
    "REJECTED_BY_GATES",
)


class GitDeliveryError(RuntimeError):
    pass


@dataclass
class GitResult:
    command: list[str]
    exit_code: int
    stdout: str
    stderr: str

    @property
    def ok(self) -> bool:
        return self.exit_code == 0

    def to_document(self) -> dict[str, Any]:
        return {
            "command": " ".join(self.command),
            "exit_code": self.exit_code,
            "stdout": self.stdout[-4000:],
            "stderr": self.stderr[-4000:],
        }


@dataclass
class DeliveryRecord:
    timestamp: str = field(default_factory=utc_now)
    action: str = ""
    branch: str = ""
    commit_sha: str = ""
    state: str = "NOT_READY"
    remote: str = ""
    remote_branch: str = ""
    detail: str = ""
    exit_code: int = 0

    def to_document(self) -> dict[str, Any]:
        return {
            "timestamp": self.timestamp,
            "action": self.action,
            "branch": self.branch,
            "commit_sha": self.commit_sha,
            "state": self.state,
            "remote": self.remote,
            "remote_branch": self.remote_branch,
            "detail": self.detail,
            "exit_code": self.exit_code,
        }


def run_git(root: Path, arguments: list[str], timeout: int = 300) -> GitResult:
    try:
        completed = subprocess.run(
            ["git", "-C", str(root), *arguments],
            capture_output=True,
            text=True,
            check=False,
            timeout=timeout,
        )
    except (OSError, subprocess.SubprocessError) as error:
        return GitResult(["git", *arguments], 128, "", str(error))
    return GitResult(["git", *arguments], completed.returncode, completed.stdout, completed.stderr)


def current_branch(root: Path) -> str:
    return run_git(root, ["rev-parse", "--abbrev-ref", "HEAD"]).stdout.strip()


def current_commit(root: Path) -> str:
    return run_git(root, ["rev-parse", "HEAD"]).stdout.strip()


def is_protected_branch(branch: str) -> bool:
    normalized = branch.strip()
    if normalized.startswith("origin/"):
        normalized = normalized[len("origin/"):]
    if normalized in config.PROTECTED_BRANCH_NAMES:
        return True
    for name in config.PROTECTED_BRANCH_NAMES:
        if normalized.startswith(name + "/"):
            return True
    return False


def ensure_audit_branch(root: Path, branch_name: str) -> GitResult:
    existing = run_git(root, ["branch", "--list", branch_name])
    if existing.stdout.strip():
        checkout = run_git(root, ["checkout", branch_name])
        return checkout
    return run_git(root, ["checkout", "-b", branch_name])


def detect_secrets(payload: str, include_assignments: bool = True) -> list[dict[str, str]]:
    findings: list[dict[str, str]] = []
    for match in CREDENTIAL_TOKEN_PATTERN.finditer(payload):
        findings.append({"pattern": "CREDENTIAL_TOKEN", "offset": str(match.start()), "value": match.group(0)})
    if not include_assignments:
        return findings
    for match in ASSIGNMENT_PATTERN.finditer(payload):
        value = match.group(2).strip()
        if value.lower() in PLACEHOLDER_VALUES:
            continue
        if value.startswith("${") or value.startswith("{{") or value.startswith("<"):
            continue
        if value.startswith("$"):
            continue
        if "$(" in value or "${" in value or "%s" in value or "%(" in value:
            continue
        if any(value.startswith(prefix) for prefix in TEST_CREDENTIAL_PREFIXES):
            continue
        if value.lower().startswith("secret") and len(value) <= 16:
            continue
        findings.append({"pattern": "SECRET_ASSIGNMENT", "offset": str(match.start()), "value": value})
    return findings


def is_test_source(relative_path: str) -> bool:
    normalized = relative_path.replace("\\", "/")
    name = normalized.rsplit("/", 1)[-1]
    if "/src/test/" in normalized or "/tests/" in normalized:
        return True
    if name.endswith("Test.java") or name.endswith("IT.java") or name.endswith("Tests.java"):
        return True
    if name.startswith("test_") and name.endswith(".py"):
        return True
    if name.endswith(".test.ts") or name.endswith(".spec.ts") or name.endswith(".test.js"):
        return True
    return False


def allowed_tokens_for(exceptions: dict[str, list[str]], relative: str) -> list[str]:
    tokens: list[str] = []
    for key, values in exceptions.items():
        if key.endswith("*"):
            if relative.startswith(key[:-1]):
                tokens.extend(values)
        elif key == relative:
            tokens.extend(values)
    return tokens


def scan_paths_for_secrets(
    root: Path,
    relative_paths: list[str],
    exceptions: dict[str, list[str]] | None = None,
) -> dict[str, list[dict[str, str]]]:
    allowlist = exceptions if exceptions is not None else secret_scan_exceptions()
    allowlist_path = _relative_to_root(config.secret_scan_exceptions_path(), root)
    findings: dict[str, list[dict[str, str]]] = {}
    for relative in relative_paths:
        if relative == allowlist_path:
            continue
        absolute = root / relative
        if not absolute.exists() or not absolute.is_file():
            continue
        if absolute.name in SECRET_FILE_NAMES:
            findings[relative] = [{"pattern": "SECRET_FILE_NAME", "offset": "0"}]
            continue
        try:
            payload = absolute.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        allowed = allowed_tokens_for(allowlist, relative)
        detected = []
        for finding in detect_secrets(payload, include_assignments=not is_test_source(relative)):
            matched = finding.get("value") or ""
            if not matched:
                offset = int(finding["offset"])
                matched = payload[offset:offset + 64].split("\n")[0]
            if any(candidate and candidate in matched for candidate in allowed):
                continue
            detected.append(finding)
        if detected:
            findings[relative] = detected
    return findings


def secret_scan_exceptions(path: Any = None) -> dict[str, list[str]]:
    document = read_json(path or config.secret_scan_exceptions_path(), {}) or {}
    exceptions: dict[str, list[str]] = {}
    for entry in document.get("exceptions", []):
        exceptions.setdefault(entry.get("path", ""), []).append(entry.get("token", ""))
    return exceptions


def staged_file_contents(root: Path) -> dict[str, str]:
    listing = run_git(root, ["diff", "--cached", "--name-only"])
    contents: dict[str, str] = {}
    for relative in listing.stdout.splitlines():
        relative = relative.strip()
        if not relative:
            continue
        blob = run_git(root, ["show", f":{relative}"])
        if blob.exit_code == 0:
            contents[relative] = blob.stdout
    return contents


def _relative_to_root(path: Path, root: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return ""


def scan_staged_content_for_secrets(root: Path) -> dict[str, list[dict[str, str]]]:
    exceptions = secret_scan_exceptions()
    allowlist_path = _relative_to_root(config.secret_scan_exceptions_path(), root)
    findings: dict[str, list[dict[str, str]]] = {}
    for relative, content in staged_file_contents(root).items():
        if relative == allowlist_path:
            continue
        if relative in SECRET_FILE_NAMES:
            findings[relative] = [{"pattern": "SECRET_FILE_NAME", "offset": "0"}]
            continue
        allowed = allowed_tokens_for(exceptions, relative)
        detected = []
        for finding in detect_secrets(content, include_assignments=not is_test_source(relative)):
            matched = finding.get("value") or ""
            if not matched:
                offset = int(finding["offset"])
                matched = content[offset:offset + 64].split("\n")[0]
            if any(candidate and candidate in matched for candidate in allowed):
                continue
            detected.append(finding)
        if detected:
            findings[relative] = detected
    return findings


def staged_paths(root: Path) -> list[str]:
    result = run_git(root, ["diff", "--cached", "--name-only"])
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def _expand_paths(root: Path, paths: list[str]) -> set[str]:
    expanded: set[str] = set()
    for path in paths:
        absolute = root / path
        if absolute.is_dir():
            for child in absolute.rglob("*"):
                if child.is_file():
                    expanded.add(child.relative_to(root).as_posix())
        else:
            expanded.add(path)
    return expanded


def staged_diff(root: Path) -> str:
    return run_git(root, ["diff", "--cached"]).stdout


def commit_verified(root: Path, message: str, paths: list[str], allow_paths: list[str] | None = None) -> DeliveryRecord:
    branch = current_branch(root)
    if is_protected_branch(branch):
        return DeliveryRecord(action="commit", branch=branch, state="REJECTED_BY_GATES", detail="refusing to commit on protected branch")

    if not paths:
        return DeliveryRecord(action="commit", branch=branch, state="REJECTED_BY_GATES", detail="no explicit paths supplied")

    allowed = _expand_paths(root, allow_paths or paths)
    requested = _expand_paths(root, paths)
    unauthorized = sorted(path for path in requested if path not in allowed)
    if unauthorized:
        return DeliveryRecord(action="commit", branch=branch, state="REJECTED_BY_GATES", detail=f"paths outside allow-list: {unauthorized}")

    secret_findings = scan_paths_for_secrets(root, paths)
    if secret_findings:
        return DeliveryRecord(action="commit", branch=branch, state="REJECTED_BY_GATES", detail=f"secret scan failed: {sorted(secret_findings)}")

    reset = run_git(root, ["reset"])
    if not reset.ok:
        return DeliveryRecord(action="commit", branch=branch, state="REJECTED_BY_GATES", detail=f"git reset failed: {reset.stderr.strip()}")

    add = run_git(root, ["add", "--", *paths])
    if not add.ok:
        return DeliveryRecord(action="commit", branch=branch, state="REJECTED_BY_GATES", detail=f"git add failed: {add.stderr.strip()}")

    staged = staged_paths(root)
    unexpected = [path for path in staged if path not in allowed]
    if unexpected:
        run_git(root, ["reset"])
        return DeliveryRecord(action="commit", branch=branch, state="REJECTED_BY_GATES", detail=f"unexpected staged paths: {unexpected}")
    staged_findings = scan_staged_content_for_secrets(root)
    if staged_findings:
        run_git(root, ["reset"])
        return DeliveryRecord(action="commit", branch=branch, state="REJECTED_BY_GATES", detail=f"secret detected in staged content: {sorted(staged_findings)}")

    commit = run_git(root, ["commit", "-m", message])
    if not commit.ok:
        return DeliveryRecord(action="commit", branch=branch, state="REJECTED_BY_GATES", detail=f"git commit failed: {commit.stderr.strip()}", exit_code=commit.exit_code)

    sha = current_commit(root)
    return DeliveryRecord(action="commit", branch=branch, commit_sha=sha, state="COMMITTED", detail=message)


def push_verified(root: Path, remote: str = "origin") -> DeliveryRecord:
    branch = current_branch(root)
    if is_protected_branch(branch):
        return DeliveryRecord(action="push", branch=branch, state="REJECTED_BY_GATES", detail="refusing to push a protected branch")
    if branch in ("", "HEAD"):
        return DeliveryRecord(action="push", branch=branch, state="REJECTED_BY_GATES", detail="detached HEAD")

    remotes = run_git(root, ["remote"])
    available = {line.strip() for line in remotes.stdout.splitlines() if line.strip()}
    if remote not in available:
        return DeliveryRecord(action="push", branch=branch, state="PUSH_BLOCKED", detail=f"remote not configured: {remote}")

    result = run_git(root, ["push", remote, f"HEAD:refs/heads/{branch}"])
    if result.ok:
        return DeliveryRecord(action="push", branch=branch, commit_sha=current_commit(root), state="PUSHED", remote=remote, remote_branch=branch, detail=result.stdout.strip())
    return DeliveryRecord(action="push", branch=branch, commit_sha=current_commit(root), state="PUSH_BLOCKED", remote=remote, remote_branch=branch, detail=result.stderr.strip()[:2000], exit_code=result.exit_code)


def record_delivery(record: DeliveryRecord, path: Any = None) -> Path:
    target = path or config.git_deliveries_path()
    document = read_json(target, {}) or {}
    records = document.get("deliveries", [])
    records.append(record.to_document())
    document["updated_at"] = utc_now()
    document["deliveries"] = records
    return write_json(target, document)


def delivery_report(path: Any = None) -> dict[str, Any]:
    document = read_json(path or config.git_deliveries_path(), {}) or {}
    deliveries = document.get("deliveries", [])
    summary: dict[str, int] = {}
    for entry in deliveries:
        summary[entry.get("state", "UNKNOWN")] = summary.get(entry.get("state", "UNKNOWN"), 0) + 1
    return {
        "total": len(deliveries),
        "state_summary": summary,
        "deliveries": deliveries,
        "unpushed": [entry for entry in deliveries if entry.get("state") == "PUSH_BLOCKED"],
    }


def repository_status(root: Path) -> dict[str, Any]:
    branch = current_branch(root)
    return {
        "branch": branch,
        "commit": current_commit(root),
        "protected": is_protected_branch(branch),
        "remotes": [line.strip() for line in run_git(root, ["remote", "-v"]).stdout.splitlines()],
        "working_tree": run_git(root, ["status", "--porcelain=v1"]).stdout.splitlines(),
        "ahead_behind": run_git(root, ["rev-list", "--left-right", "--count", "HEAD...@{u}"]).stdout.strip() if "@{u}" in run_git(root, ["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"]).stdout else "no upstream",
    }

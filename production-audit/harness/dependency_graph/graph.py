from __future__ import annotations

import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .. import config

ARTIFACT_PATTERN = re.compile(r"<artifactId>\s*([^<\s]+)\s*</artifactId>")


@dataclass
class ModuleEdge:
    source: str
    target: str
    scope: str
    dependency: str

    def to_document(self) -> dict[str, Any]:
        return {"source": self.source, "target": self.target, "scope": self.scope, "dependency": self.dependency}


def module_pom_paths(root: Path | None = None) -> dict[str, Path]:
    repository = root or config.repository_root()
    result: dict[str, Path] = {}
    for pom in repository.rglob("pom.xml"):
        if "target" in pom.parts:
            continue
        relative = pom.relative_to(repository)
        if len(relative.parts) == 1:
            result["root"] = pom
        elif relative.parts[0] == "utils":
            result["utils/" + relative.parts[1]] = pom
        else:
            result[relative.parts[0]] = pom
    return result


def _artifact_ids(pom: Path) -> list[str]:
    text = pom.read_text(encoding="utf-8", errors="replace")
    return [match.group(1) for match in ARTIFACT_PATTERN.finditer(text)]


def build_dependency_graph(root: Path | None = None) -> dict[str, Any]:
    repository = root or config.repository_root()
    poms = module_pom_paths(repository)
    internal_names = {name.split("/")[-1] for name in poms}

    edges: list[ModuleEdge] = []
    for module, pom in sorted(poms.items()):
        text = pom.read_text(encoding="utf-8", errors="replace")
        scope = "compile"
        for block in re.findall(r"<dependency>(.*?)</dependency>", text, re.DOTALL):
            artifact_match = ARTIFACT_PATTERN.search(block)
            if not artifact_match:
                continue
            artifact = artifact_match.group(1)
            scope_match = re.search(r"<scope>\s*([^<\s]+)\s*</scope>", block)
            block_scope = scope_match.group(1) if scope_match else "compile"
            if artifact in internal_names and artifact != module:
                edges.append(ModuleEdge(source=module, target=artifact, scope=block_scope, dependency=artifact))

    adjacency: dict[str, list[str]] = {}
    for edge in edges:
        adjacency.setdefault(edge.source, []).append(edge.target)

    cycles = detect_cycles(adjacency)
    return {
        "modules": sorted(poms),
        "internal_edges": [edge.to_document() for edge in edges],
        "adjacency": {key: sorted(value) for key, value in sorted(adjacency.items())},
        "cycles": cycles,
        "layering": classify_layers(edges),
    }


def detect_cycles(adjacency: dict[str, list[str]]) -> list[list[str]]:
    cycles: list[list[str]] = []
    visiting: list[str] = []
    visited: set[str] = set()

    def visit(node: str, path: list[str]) -> None:
        if node in path:
            index = path.index(node)
            cycles.append(path[index:] + [node])
            return
        if node in visited:
            return
        visiting.append(node)
        path.append(node)
        for neighbour in adjacency.get(node, []):
            visit(neighbour, path)
        path.pop()
        visiting.pop()
        visited.add(node)

    for node in sorted(adjacency):
        visit(node, [])
    unique: list[list[str]] = []
    seen: set[tuple[str, ...]] = set()
    for cycle in cycles:
        key = tuple(sorted(set(cycle)))
        if key not in seen:
            seen.add(key)
            unique.append(cycle)
    return unique


def classify_layers(edges: list[ModuleEdge]) -> dict[str, list[str]]:
    shared = {edge.target for edge in edges if edge.source.startswith("utils/")}
    services = {edge.target for edge in edges if not edge.source.startswith("utils/") and not edge.target.startswith("utils/")}
    service_to_shared = {edge.source for edge in edges if edge.source not in shared and edge.target in shared}
    shared_to_service = {edge.source for edge in edges if edge.source in shared and edge.target not in shared}
    return {
        "shared_libraries": sorted(shared),
        "services": sorted(services),
        "services_depending_on_shared": sorted(service_to_shared),
        "shared_depending_on_services": sorted(shared_to_service),
        "layering_violations": sorted(shared_to_service),
    }


def module_usage_evidence(root: Path | None = None) -> dict[str, str]:
    repository = root or config.repository_root()
    result: dict[str, str] = {}
    for module in list(config.SERVICE_MODULES) + list(config.SHARED_MODULES):
        base = repository / module if module in config.SERVICE_MODULES else repository / "utils" / module
        if not base.exists():
            continue
        completed = subprocess.run(
            ["git", "-C", str(repository), "log", "-1", "--format=%H", "--", str(base.relative_to(repository))],
            capture_output=True,
            text=True,
            check=False,
        )
        result[module] = completed.stdout.strip()
    return result

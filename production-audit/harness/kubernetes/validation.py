from __future__ import annotations

import json
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any

from .. import config, yamlmini

REQUIRED_WORKLOAD_KINDS = ("Deployment", "Service", "ConfigMap", "ServiceAccount")
MULTI_REPLICA_THRESHOLD = 2
LOCAL_HOSTNAME = re.compile(r"(localhost|127\.0\.0\.1|0\.0\.0\.0|host\.docker\.internal)")
LITERAL_SECRET = re.compile(r"(?i)(password|secret|token|api[-_]?key)\s*:\s*[\"']?[A-Za-z0-9._/+-]{8,}")


class KubernetesReport:
    def __init__(self) -> None:
        self.checks: list[dict[str, Any]] = []

    def add(self, identifier: str, name: str, status: str, detail: str, evidence: list[str] | None = None) -> None:
        self.checks.append(
            {
                "id": identifier,
                "name": name,
                "status": status,
                "detail": detail,
                "evidence": evidence or [],
            }
        )

    def document(self) -> dict[str, Any]:
        failed = [item["id"] for item in self.checks if item["status"] == "FAIL"]
        passed = [item["id"] for item in self.checks if item["status"] == "PASS"]
        return {
            "status": "FAIL" if failed else "PASS",
            "failed_checks": failed,
            "passed_checks": passed,
            "checks": self.checks,
        }


def manifest_paths() -> list[Path]:
    root = config.repository_root() / "k8s"
    if not root.exists():
        return []
    return sorted(path for path in root.rglob("*.yaml") if path.is_file())


def load_manifests() -> tuple[list[dict[str, Any]], list[str]]:
    documents: list[dict[str, Any]] = []
    errors: list[str] = []
    for path in manifest_paths():
        try:
            parsed = yamlmini.load_all(path.read_text(encoding="utf-8"))
        except yamlmini.YamlError as error:
            errors.append(path.name + ": " + str(error))
            continue
        for entry in parsed:
            if isinstance(entry, dict):
                entry["__source__"] = path.name
                documents.append(entry)
    return documents, errors


def _workload_name(document: dict[str, Any]) -> str:
    return str((document.get("metadata") or {}).get("name", ""))


def kubeconform_result() -> dict[str, Any]:
    if shutil.which("kubeconform") is None:
        return {
            "status": "BLOCKED",
            "reason": "kubeconform is not installed, so the offline schema validation could not run",
        }
    try:
        result = subprocess.run(
            ["kubeconform", "-strict", "-summary", "-ignore-missing-schemas", str(config.repository_root() / "k8s")],
            capture_output=True,
            text=True,
            check=False,
            timeout=300,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"status": "BLOCKED", "reason": "kubeconform could not run: " + str(error)}
    summary = (result.stdout or "").strip().split("\n")[-1]
    return {
        "status": "PASS" if result.returncode == 0 else "FAIL",
        "exit_code": result.returncode,
        "summary": summary,
        "stdout": (result.stdout or "").strip()[-2000:],
        "stderr": (result.stderr or "").strip()[-1000:],
    }


def validate_manifests() -> dict[str, Any]:
    report = KubernetesReport()
    documents, errors = load_manifests()
    if errors:
        report.add("K8S-01", "MANIFEST_PARSING", "FAIL", "manifests could not be parsed", errors)
    else:
        report.add(
            "K8S-01",
            "MANIFEST_PARSING",
            "PASS",
            "every manifest parses",
            ["documents: " + str(len(documents))],
        )
    if not documents:
        report.add("K8S-00", "MANIFEST_PRESENCE", "FAIL", "no kubernetes manifests were found")
        return report.document()

    kinds = sorted({str(document.get("kind", "")) for document in documents})
    missing_kinds = [kind for kind in REQUIRED_WORKLOAD_KINDS if kind not in kinds]
    if missing_kinds:
        report.add(
            "K8S-02",
            "RESOURCE_COVERAGE",
            "FAIL",
            "required resource kinds are missing: " + ", ".join(missing_kinds),
            ["kinds present: " + ", ".join(kinds)],
        )
    else:
        report.add(
            "K8S-02",
            "RESOURCE_COVERAGE",
            "PASS",
            "the required resource kinds are present",
            ["kinds present: " + ", ".join(kinds)],
        )

    deployments = [document for document in documents if document.get("kind") == "Deployment"]
    probe_problems: list[str] = []
    resource_problems: list[str] = []
    security_problems: list[str] = []
    for deployment in deployments:
        name = _workload_name(deployment)
        pod_spec = ((deployment.get("spec") or {}).get("template") or {}).get("spec") or {}
        for container in pod_spec.get("containers") or []:
            container_name = str(container.get("name", "container"))
            if not container.get("livenessProbe"):
                probe_problems.append(name + "/" + container_name + " has no livenessProbe")
            if not container.get("readinessProbe"):
                probe_problems.append(name + "/" + container_name + " has no readinessProbe")
            if not container.get("startupProbe"):
                probe_problems.append(name + "/" + container_name + " has no startupProbe")
            resources = container.get("resources") or {}
            requests = resources.get("requests") or {}
            limits = resources.get("limits") or {}
            for field in ("cpu", "memory"):
                if field not in requests:
                    resource_problems.append(name + "/" + container_name + " has no " + field + " request")
                if field not in limits:
                    resource_problems.append(name + "/" + container_name + " has no " + field + " limit")
            if not container.get("securityContext") and not pod_spec.get("securityContext"):
                security_problems.append(name + "/" + container_name + " has no securityContext")
    if probe_problems:
        report.add("K8S-03", "HEALTH_PROBES", "FAIL", "; ".join(probe_problems[:12]))
    else:
        report.add(
            "K8S-03",
            "HEALTH_PROBES",
            "PASS",
            "every container declares startup, readiness and liveness probes",
            ["deployments: " + str(len(deployments))],
        )
    if resource_problems:
        report.add("K8S-04", "RESOURCE_BUDGET", "FAIL", "; ".join(resource_problems[:12]))
    else:
        report.add(
            "K8S-04",
            "RESOURCE_BUDGET",
            "PASS",
            "every container declares cpu and memory requests and limits",
            ["deployments: " + str(len(deployments))],
        )
    if security_problems:
        report.add("K8S-05", "SECURITY_CONTEXT", "FAIL", "; ".join(security_problems[:12]))
    else:
        report.add(
            "K8S-05",
            "SECURITY_CONTEXT",
            "PASS",
            "every workload declares a security context",
        )

    services = [document for document in documents if document.get("kind") == "Service"]
    deployment_names = {_workload_name(document) for document in deployments}
    deployment_labels: dict[str, dict[str, Any]] = {}
    for deployment in deployments:
        template = (deployment.get("spec") or {}).get("template") or {}
        labels = (template.get("metadata") or {}).get("labels") or {}
        deployment_labels[_workload_name(deployment)] = labels
    selector_problems: list[str] = []
    for service in services:
        selector = ((service.get("spec") or {}).get("selector")) or {}
        matched = [
            name
            for name, labels in deployment_labels.items()
            if selector and all(labels.get(key) == value for key, value in selector.items())
        ]
        if not matched:
            selector_problems.append(
                "service " + _workload_name(service) + " selects no deployment"
            )
    if selector_problems:
        report.add("K8S-06", "SERVICE_SELECTORS", "FAIL", "; ".join(selector_problems))
    else:
        report.add(
            "K8S-06",
            "SERVICE_SELECTORS",
            "PASS",
            "every service selects a declared deployment",
            ["services: " + str(len(services))],
        )

    autoscalers = [document for document in documents if document.get("kind") == "HorizontalPodAutoscaler"]
    hpa_problems: list[str] = []
    for autoscaler in autoscalers:
        name = _workload_name(autoscaler)
        spec = autoscaler.get("spec") or {}
        target = (spec.get("scaleTargetRef") or {}).get("name")
        if not target:
            hpa_problems.append(name + " has no scaleTargetRef")
        elif target not in deployment_names:
            hpa_problems.append(name + " targets " + str(target) + " which is not a declared deployment")
        if not spec.get("minReplicas") and spec.get("minReplicas") != 0:
            hpa_problems.append(name + " has no minReplicas")
        if not spec.get("maxReplicas"):
            hpa_problems.append(name + " has no maxReplicas")
        metrics = spec.get("metrics") or []
        if not metrics:
            hpa_problems.append(name + " has no metrics")
        for metric in metrics:
            if metric.get("type") == "Resource" and not metric.get("resource"):
                hpa_problems.append(name + " has a resource metric without a resource target")
    if hpa_problems:
        report.add("K8S-07", "AUTOSCALING", "FAIL", "; ".join(hpa_problems))
    else:
        report.add(
            "K8S-07",
            "AUTOSCALING",
            "PASS",
            "every autoscaler targets a declared deployment with bounded replicas and metrics",
            ["autoscalers: " + str(len(autoscalers))],
        )

    disruptions = [document for document in documents if document.get("kind") == "PodDisruptionBudget"]
    unprotected = []
    for deployment in deployments:
        name = _workload_name(deployment)
        replicas = (deployment.get("spec") or {}).get("replicas") or 1
        if replicas >= MULTI_REPLICA_THRESHOLD and not any(
            name in str((budget.get("spec") or {}).get("selector", {}).get("matchLabels", {}))
            for budget in disruptions
        ):
            unprotected.append(name)
    if unprotected:
        report.add(
            "K8S-08",
            "DISRUPTION_BUDGET",
            "FAIL",
            "multi replica deployments without a pod disruption budget: " + ", ".join(unprotected),
        )
    else:
        report.add(
            "K8S-08",
            "DISRUPTION_BUDGET",
            "PASS",
            "every multi replica deployment is protected by a pod disruption budget",
        )

    literal_problems: list[str] = []
    hostname_problems: list[str] = []
    for path in manifest_paths():
        text = path.read_text(encoding="utf-8")
        for number, line in enumerate(text.split("\n"), start=1):
            if "value:" in line and LITERAL_SECRET.search(line):
                literal_problems.append(path.name + ":" + str(number) + " embeds a credential literal")
            if "value:" in line and LOCAL_HOSTNAME.search(line):
                hostname_problems.append(path.name + ":" + str(number) + " points at a developer machine address")
    if literal_problems:
        report.add("K8S-09", "SECRET_HYGIENE", "FAIL", "; ".join(literal_problems[:10]))
    else:
        report.add("K8S-09", "SECRET_HYGIENE", "PASS", "no manifest embeds a credential literal")
    if hostname_problems:
        report.add("K8S-10", "HOSTNAME_HYGIENE", "FAIL", "; ".join(hostname_problems[:10]))
    else:
        report.add("K8S-10", "HOSTNAME_HYGIENE", "PASS", "no manifest points at a developer machine address")

    namespaces = sorted(
        {
            str((document.get("metadata") or {}).get("namespace", ""))
            for document in documents
            if (document.get("metadata") or {}).get("namespace")
        }
    )
    if len(namespaces) > 1:
        report.add(
            "K8S-11",
            "NAMESPACE_CONSISTENCY",
            "FAIL",
            "manifests span more than one namespace: " + ", ".join(namespaces),
        )
    else:
        report.add(
            "K8S-11",
            "NAMESPACE_CONSISTENCY",
            "PASS",
            "every namespaced manifest uses " + (namespaces[0] if namespaces else "the default namespace"),
        )

    ingresses = [document for document in documents if document.get("kind") == "Ingress"]
    ingress_problems = []
    for ingress in ingresses:
        if not (ingress.get("spec") or {}).get("tls"):
            ingress_problems.append("ingress " + _workload_name(ingress) + " has no tls section")
    if ingress_problems:
        report.add("K8S-12", "INGRESS_TLS", "FAIL", "; ".join(ingress_problems))
    elif not ingresses:
        report.add("K8S-12", "INGRESS_TLS", "FAIL", "no ingress resource is declared")
    else:
        report.add("K8S-12", "INGRESS_TLS", "PASS", "every ingress declares tls")

    schema = kubeconform_result()
    if schema.get("status") == "PASS":
        report.add(
            "K8S-13",
            "SCHEMA_VALIDATION",
            "PASS",
            "kubeconform validated the manifests that have a published kubernetes schema",
            [str(schema.get("summary", ""))],
        )
    elif schema.get("status") == "FAIL":
        report.add(
            "K8S-13",
            "SCHEMA_VALIDATION",
            "FAIL",
            "kubeconform rejected at least one manifest",
            [str(schema.get("summary", "")), str(schema.get("stdout", ""))[:800]],
        )
    else:
        report.add(
            "K8S-13",
            "SCHEMA_VALIDATION",
            "BLOCKED",
            str(schema.get("reason", "the schema validation could not run")),
        )

    observation = load_autoscaling_observation()
    if observation["status"] == "PASS":
        report.add(
            "K8S-14",
            "AUTOSCALING_OBSERVATION",
            "PASS",
            "the autoscaler was observed on a real cluster to scale up under a load and to scale back down",
            observation.get("evidence", []),
        )
    elif observation["status"] == "BLOCKED":
        report.add(
            "K8S-14",
            "AUTOSCALING_OBSERVATION",
            "BLOCKED",
            observation.get("reason", "no autoscaling observation is recorded"),
            observation.get("evidence", []),
        )
    else:
        report.add(
            "K8S-14",
            "AUTOSCALING_OBSERVATION",
            "FAIL",
            observation.get("reason", "the recorded autoscaling observation does not prove the behaviour of the autoscaler"),
            observation.get("problems", []),
        )

    return report.document()


def observation_path() -> Path:
    return config.run_root() / "k8s-results" / "autoscaling.json"


def _autoscaler_expectations() -> dict[str, Any]:
    documents, _ = load_manifests()
    expectations: dict[str, Any] = {}
    for document in documents:
        if document.get("kind") != "HorizontalPodAutoscaler":
            continue
        spec = document.get("spec") or {}
        metrics = spec.get("metrics") or []
        target: dict[str, Any] = {}
        for metric in metrics:
            if metric.get("type") == "Resource":
                target = ((metric.get("resource") or {}).get("target")) or {}
        expectations[_workload_name(document)] = {
            "minReplicas": spec.get("minReplicas"),
            "maxReplicas": spec.get("maxReplicas"),
            "scaleTarget": (spec.get("scaleTargetRef") or {}).get("name"),
            "averageUtilization": target.get("averageUtilization"),
        }
    return expectations


def _observed_replicas(observation: dict[str, Any], phase: str) -> int | None:
    entry = (observation.get("observations") or {}).get(phase) or {}
    value = entry.get("replicas")
    if isinstance(value, bool) or not isinstance(value, int):
        return None
    return value


def evaluate_autoscaling_observation(observation: dict[str, Any]) -> dict[str, Any]:
    problems: list[str] = []
    evidence: list[str] = []
    cluster = observation.get("cluster") or {}
    if not cluster.get("kubernetes_version"):
        problems.append("the observation records no kubernetes version")
    if not cluster.get("cluster_name"):
        problems.append("the observation records no cluster name")
    if not (observation.get("image") or {}).get("digest"):
        problems.append("the observation records no deployed image")
    applied = ((observation.get("autoscaler_manifest_as_applied") or {}).get("spec")) or {}
    expectations = _autoscaler_expectations()
    if not expectations:
        problems.append("the manifests declare no autoscaler to compare the observation with")
    for name, expected in expectations.items():
        if applied.get("minReplicas") != expected.get("minReplicas"):
            problems.append(name + " in the observation has minReplicas=" + str(applied.get("minReplicas")) + " while the manifest declares " + str(expected.get("minReplicas")))
        if applied.get("maxReplicas") != expected.get("maxReplicas"):
            problems.append(name + " in the observation has maxReplicas=" + str(applied.get("maxReplicas")) + " while the manifest declares " + str(expected.get("maxReplicas")))
        if (applied.get("scaleTargetRef") or {}).get("name") != expected.get("scaleTarget"):
            problems.append(name + " in the observation targets another deployment than the manifest declares")
        metrics = applied.get("metrics") or []
        applied_target: dict[str, Any] = {}
        if metrics:
            applied_target = (((metrics[0].get("resource") or {}).get("target")) or {})
        if applied_target.get("averageUtilization") != expected.get("averageUtilization"):
            problems.append(name + " in the observation targets " + str(applied_target.get("averageUtilization")) + " percent cpu while the manifest declares " + str(expected.get("averageUtilization")))
    before = _observed_replicas(observation, "before_load")
    under = _observed_replicas(observation, "under_load")
    after = _observed_replicas(observation, "after_load")
    if before is None or under is None or after is None:
        problems.append("the observation records no complete set of replica counts")
    else:
        evidence.append("replicas before=" + str(before) + " under load=" + str(under) + " after load=" + str(after))
        if under <= before:
            problems.append("the observation shows no scale up, " + str(under) + " replicas under the load against " + str(before) + " before")
        if after > before:
            problems.append("the observation shows no scale down, " + str(after) + " replicas after the load against " + str(before) + " before")
    for phase in ("before_load", "under_load", "after_load"):
        entry = (observation.get("observations") or {}).get(phase) or {}
        if not entry.get("observed_at"):
            problems.append("the observation of the phase " + phase + " carries no timestamp")
        if not isinstance(entry.get("cpu_utilization_percent"), int):
            problems.append("the observation of the phase " + phase + " carries no cpu utilisation")
    verdict = observation.get("verdict") or {}
    if not verdict.get("scaled_up_under_real_load"):
        problems.append("the verdict does not claim a scale up under a real load")
    if not verdict.get("scaled_back_down_after_load"):
        problems.append("the verdict does not claim a scale back down")
    load = observation.get("load") or {}
    if not load.get("total_requests"):
        problems.append("the observation records no load")
    else:
        evidence.append("load: " + str(load.get("total_requests")) + " requests at " + str(load.get("rate_per_second")) + " per second against " + str(load.get("target")))
    return {"status": "PASS" if not problems else "FAIL", "problems": problems, "evidence": evidence}


def load_autoscaling_observation() -> dict[str, Any]:
    path = observation_path()
    if not path.exists():
        return {
            "status": "BLOCKED",
            "reason": "no autoscaling observation of a real cluster is recorded at " + str(path),
            "evidence": [],
        }
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        return {
            "status": "FAIL",
            "reason": "the recorded autoscaling observation could not be read: " + str(error),
            "evidence": [],
        }
    result = evaluate_autoscaling_observation(document)
    if result["status"] == "FAIL":
        result["reason"] = "the recorded autoscaling observation does not prove that the autoscaler scaled up and scaled back down"
    else:
        result["reason"] = "the recorded autoscaling observation proves that the autoscaler scaled up under a real load and scaled back down"
    return result

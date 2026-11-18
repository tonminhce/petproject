from __future__ import annotations

from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from .. import config
from ..persistence.store import read_json, utc_now, write_json

RESEARCH_TOPICS = {
    "java-25-concurrency": "https://docs.oracle.com/en/java/javase/25/",
    "spring-bean-scopes": "https://docs.spring.io/spring-framework/reference/core/beans/factory-scopes.html",
    "spring-transactions": "https://docs.spring.io/spring-framework/reference/data-access/transaction.html",
    "spring-boot-4": "https://docs.spring.io/spring-boot/reference/",
    "spring-kafka": "https://docs.spring.io/spring-kafka/reference/kafka.html",
    "kafka-guarantees": "https://kafka.apache.org/documentation/",
    "postgres-locking": "https://www.postgresql.org/docs/current/explicit-locking.html",
    "postgres-isolation": "https://www.postgresql.org/docs/current/transaction-iso.html",
    "redis-atomicity": "https://redis.io/docs/latest/develop/using-commands/transactions/",
    "stripe-webhooks": "https://docs.stripe.com/webhooks",
    "stripe-idempotency": "https://docs.stripe.com/api/idempotent_requests",
    "keycloak": "https://www.keycloak.org/documentation",
    "spring-cloud-gateway": "https://docs.spring.io/spring-cloud-gateway/reference/",
    "resilience4j": "https://resilience4j.readme.io/docs/getting-started-3",
    "testcontainers": "https://java.testcontainers.org/",
    "owasp": "https://cheatsheetseries.owasp.org/",
    "bucket4j": "https://bucket4j.com/",
}


@dataclass
class ResearchEntry:
    topic: str
    url: str
    version: str = ""
    finding: str = ""
    comparison_with_code: str = ""
    decision: str = ""
    risks: str = ""
    acceptance_criteria: list[str] = field(default_factory=list)
    tests: list[str] = field(default_factory=list)
    retrieved_at: str = field(default_factory=utc_now)

    def to_document(self) -> dict[str, Any]:
        return asdict(self)


def load_research(path: Any = None) -> dict[str, Any]:
    return read_json(path or config.research_path(), {}) or {}


def save_research(document: dict[str, Any], path: Any = None) -> Any:
    document["updated_at"] = utc_now()
    return write_json(path or config.research_path(), document)


def research_index() -> dict[str, Any]:
    document = load_research()
    entries = document.get("entries", {})
    return {
        "topics_known": RESEARCH_TOPICS,
        "recorded": {topic: entry for topic, entry in entries.items()},
        "missing": [topic for topic in RESEARCH_TOPICS if topic not in entries],
        "count": len(entries),
    }


def record_research(entry: ResearchEntry, path: Any = None) -> dict[str, Any]:
    document = load_research(path)
    entries = document.setdefault("entries", {})
    entries[entry.topic] = entry.to_document()
    save_research(document, path)
    return entries[entry.topic]


def seed_research_index(path: Any = None) -> dict[str, Any]:
    document = load_research(path)
    document.setdefault("entries", {})
    document["reference_index"] = RESEARCH_TOPICS
    save_research(document, path)
    return document

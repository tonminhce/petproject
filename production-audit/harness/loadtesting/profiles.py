from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class SafetyLimits:
    max_requests_per_second: int = 500
    max_virtual_users: int = 500
    max_duration_seconds: int = 1800
    max_total_requests: int = 5_000_000


@dataclass(frozen=True)
class Profile:
    name: str
    scenario: str
    requests_per_second: int
    virtual_users: int
    duration_seconds: int
    description: str
    reaches_payment_provider: bool = False
    max_business_failure_rate: float = 0.05
    max_shed_rate: float = 0.01
    max_shed_rate: float = 0.01
    tags: tuple[str, ...] = field(default_factory=tuple)


PROFILES: dict[str, Profile] = {
    "baseline": Profile(
        name="baseline",
        scenario="browse",
        requests_per_second=20,
        virtual_users=80,
        duration_seconds=120,
        description="PROFILE A representative normal traffic",
        tags=("profile-a",),
    ),
    "ramp": Profile(
        name="ramp",
        scenario="full",
        requests_per_second=50,
        virtual_users=200,
        duration_seconds=180,
        description="PROFILE B gradually increasing request rate and concurrency",
        tags=("profile-b",),
    ),
    "spike": Profile(
        name="spike",
        scenario="full",
        requests_per_second=200,
        virtual_users=400,
        duration_seconds=120,
        description="PROFILE C black friday spike",
        tags=("profile-c",),
    ),
    "sustained-peak": Profile(
        name="sustained-peak",
        scenario="full",
        requests_per_second=100,
        virtual_users=150,
        duration_seconds=600,
        description="PROFILE D sustained high utilisation",
        tags=("profile-d",),
    ),
    "flash-sale": Profile(
        name="flash-sale",
        scenario="flash-sale",
        requests_per_second=150,
        virtual_users=400,
        duration_seconds=120,
        description="PROFILE E many users race for one limited stock product",
        tags=("profile-e",),
    ),
    "promotion-storm": Profile(
        name="promotion-storm",
        scenario="promotion-storm",
        requests_per_second=120,
        virtual_users=400,
        duration_seconds=120,
        description="PROFILE F many users redeem the same coupon at once",
        tags=("profile-f",),
    ),
    "soak": Profile(
        name="soak",
        scenario="full",
        requests_per_second=40,
        virtual_users=60,
        duration_seconds=1200,
        description="PROFILE J soak to detect memory retention and latency growth",
        tags=("profile-j",),
    ),
    "recovery": Profile(
        name="recovery",
        scenario="browse",
        requests_per_second=10,
        virtual_users=10,
        duration_seconds=60,
        description="PROFILE I return to normal traffic and measure recovery",
        tags=("profile-i",),
    ),
    "kafka-backlog": Profile(
        name="kafka-backlog",
        scenario="full",
        requests_per_second=80,
        virtual_users=100,
        duration_seconds=180,
        description="PROFILE G consumers fall behind while event production increases",
        tags=("profile-g",),
    ),
    "failure-during-peak": Profile(
        name="failure-during-peak",
        scenario="full",
        requests_per_second=150,
        virtual_users=150,
        duration_seconds=120,
        description="PROFILE H controlled dependency failure during elevated load",
        tags=("profile-h",),
    ),
}

ALLOWED_SCALES = (1, 3, 5, 10)


def profile_names() -> list[str]:
    return sorted(PROFILES)


def resolve_profile(name: str, scale: int = 1) -> Profile:
    if name not in PROFILES:
        raise KeyError("unknown load profile: " + name)
    if scale not in ALLOWED_SCALES:
        raise ValueError("unsupported scale multiplier: " + str(scale))
    base = PROFILES[name]
    return Profile(
        name=base.name,
        scenario=base.scenario,
        requests_per_second=base.requests_per_second * scale,
        virtual_users=base.virtual_users * scale,
        duration_seconds=base.duration_seconds,
        description=base.description + " at " + str(scale) + "x",
        reaches_payment_provider=base.reaches_payment_provider,
        tags=base.tags,
        max_business_failure_rate=base.max_business_failure_rate,
        max_shed_rate=base.max_shed_rate,
    )


def enforce_safety(profile: Profile, limits: SafetyLimits | None = None) -> list[str]:
    boundary = limits or SafetyLimits()
    violations: list[str] = []
    if profile.requests_per_second > boundary.max_requests_per_second:
        violations.append(
            "requests per second " + str(profile.requests_per_second) + " exceeds the safety cap "
            + str(boundary.max_requests_per_second)
        )
    if profile.virtual_users > boundary.max_virtual_users:
        violations.append(
            "virtual users " + str(profile.virtual_users) + " exceeds the safety cap "
            + str(boundary.max_virtual_users)
        )
    if profile.duration_seconds > boundary.max_duration_seconds:
        violations.append(
            "duration " + str(profile.duration_seconds) + "s exceeds the safety cap "
            + str(boundary.max_duration_seconds) + "s"
        )
    total = profile.requests_per_second * profile.duration_seconds
    if total > boundary.max_total_requests:
        violations.append(
            "planned request volume " + str(total) + " exceeds the safety cap "
            + str(boundary.max_total_requests)
        )
    return violations


def describe_profiles() -> list[dict[str, Any]]:
    return [
        {
            "name": profile.name,
            "scenario": profile.scenario,
            "requests_per_second": profile.requests_per_second,
            "virtual_users": profile.virtual_users,
            "duration_seconds": profile.duration_seconds,
            "description": profile.description,
            "reaches_payment_provider": profile.reaches_payment_provider,
        }
        for profile in (PROFILES[name] for name in profile_names())
    ]

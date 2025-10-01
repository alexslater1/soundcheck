"""The dataset profiles. Only the counts are fixed here; the
shapes
are in `distributions`."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Profile:
    name: str
    users: int
    artists: int
    venues: int
    gigs: int
    posts: int
    user_follows: int
    artist_follows: int
    likes: int
    events: int


PROFILES: dict[str, Profile] = {
    "small": Profile(
        "small", 5_000, 3_000, 800, 8_000, 50_000, 100_000, 40_000, 300_000, 1_000_000
    ),
    "medium": Profile(
        "medium",
        100_000,
        20_000,
        4_000,
        50_000,
        1_000_000,
        2_000_000,
        600_000,
        8_000_000,
        10_000_000,
    ),
    # Listed for completeness; cut: medium is enough.
    "large": Profile(
        "large",
        1_000_000,
        50_000,
        8_000,
        150_000,
        5_000_000,
        20_000_000,
        5_000_000,
        50_000_000,
        50_000_000,
    ),
}

RUNNABLE = ("small", "medium")


def profile(name: str) -> Profile:
    if name not in PROFILES:
        raise ValueError(f"unknown profile {name!r}: one of {', '.join(PROFILES)}")
    if name not in RUNNABLE:
        raise ValueError(f"profile {name!r} is cut: use small or medium")
    return PROFILES[name]

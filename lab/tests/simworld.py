"""A made-up world for the simulator's tests (no database)."""

from datetime import UTC, datetime, timedelta

import numpy as np

from soundcheck_lab.sim import world

NOW = datetime(2026, 10, 5, 12, tzinfo=UTC)
GENRES = ("Indie", "Rock", "Techno", "Jazz", "Folk", "Pop")


def uid(kind: int, n: int) -> str:
    return f"00000000-0000-7000-8000-{kind:04d}{n:08d}"


def synthetic_world(seed: int = 7) -> world.World:
    """A made-up world: 60 acts with one or two genres, 240 posts by 12 authors at 40 gigs in two
    towns (a quarter of the posts have no gig)."""
    rng = np.random.default_rng(1234)
    genres = []
    for a in range(60):
        for g in rng.choice(len(GENRES), size=int(rng.integers(1, 3)), replace=False):
            genres.append((uid(1, a), GENRES[int(g)], float(rng.uniform(0.3, 1.0))))
    posts, acts = [], []
    for p in range(240):
        gig = None if p % 4 == 0 else uid(3, p % 40)
        starts = NOW - timedelta(days=p % 40) if gig else None
        town = ("London", "Leeds")[p % 2] if gig else None
        posts.append((uid(2, p), uid(4, p % 12), gig, starts, town))
        for a in sorted({int(x) for x in rng.choice(60, size=int(rng.integers(1, 3)))}):
            acts.append((uid(2, p), uid(1, a)))
    return world.build(posts, acts, genres, [("Leeds", 2), ("London", 1)], seed)


WORLD = synthetic_world()

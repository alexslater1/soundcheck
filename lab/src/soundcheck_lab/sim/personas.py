"""Synthetic personas with hidden tastes.

SYNTHETIC: these are not people. Persona n of seed s signs in as `sim_<s>_<n>@sim.soundcheck.test`
(handles allow only a–z, 0–9 and _), so a rerun with the same seed reuses the same accounts, and the
console tags them synthetic by their domain (`soundcheck.admin.synthetic-email-domains`).

Each persona is drawn from its own branch of one seed tree (`numpy.random.SeedSequence(seed)`,
spawn key (n, 0)), so persona 7 is the same whether the run has 10 personas or 200."""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime

import numpy as np

from soundcheck_lab.seed.personas import Persona
from soundcheck_lab.sim.click import DEFAULT, Params, Taste, cosine, true_affinity
from soundcheck_lab.sim.world import World

SIM_DOMAIN = "sim.soundcheck.test"
MAX_SEED = 10**9  # keeps the handle within 30 characters


def rng_for(seed: int, *key: int) -> np.random.Generator:
    """The generator at {@code key} in the seed's tree: independent of every other key."""
    return np.random.default_rng(np.random.SeedSequence(entropy=seed, spawn_key=key))


@dataclass(frozen=True)
class Attendance:
    gig_id: str
    status: str  # WENT for a gig whose day has come, GOING otherwise


@dataclass(frozen=True)
class SimPersona:
    n: int
    seed: int
    taste: Taste
    city: str | None
    social: float  # 0..1: follows more authors, taps profiles more
    session_length: int  # items seen per session, 8..40
    follow_artists: tuple[str, ...]  # favourite acts it follows at the start
    attend: tuple[Attendance, ...]  # 0..3 gigs it marks at the start
    rounds: tuple[int, ...]  # the round of each of its sessions, ascending

    @property
    def handle(self) -> str:
        return f"sim_{self.seed}_{self.n:03d}"

    @property
    def account(self) -> Persona:
        """The seed's account shape, so `seed.api.Api` can sign it in."""
        return Persona(
            handle=self.handle,
            display_name=f"Sim {self.seed}-{self.n:03d}",
            city=self.city or "",
            genres=tuple(sorted(self.taste.genres, key=lambda g: -self.taste.genres[g])),
            bio="Synthetic persona (simulator).",
            domain=SIM_DOMAIN,
        )


def _pick(
    rng: np.random.Generator, options: Sequence[str], weights: Sequence[float], k: int
) -> list[str]:
    """{@code k} distinct options, drawn with probability ∝ weight."""
    k = min(k, len(options))
    if k <= 0:
        return []
    p = np.asarray(weights, dtype=float)
    p = p / p.sum()
    chosen = rng.choice(len(options), size=k, replace=False, p=p)
    return [options[int(i)] for i in chosen]


def attendance_status(starts_at: datetime | None, now: datetime) -> str:
    """WENT once the gig has started (the API refuses WENT before its day), GOING before."""
    return "GOING" if starts_at is not None and starts_at > now else "WENT"


def rounds_for(rng: np.random.Generator, sessions: int, days: int) -> tuple[int, ...]:
    """Which rounds a persona's sessions fall in: distinct rounds when there are enough."""
    if sessions <= days:
        picked = rng.choice(days, size=sessions, replace=False)
    else:
        picked = rng.integers(0, days, size=sessions)
    return tuple(sorted(int(r) for r in picked))


def make_one(
    world: World,
    seed: int,
    n: int,
    sessions: int,
    days: int,
    now: datetime,
    params: Params = DEFAULT,
) -> SimPersona:
    rng = rng_for(seed, n, 0)
    # Genres: 3–4 favourites, likelier the more posts carry them, weighted by a Dirichlet draw.
    genre_posts = world.genre_posts()
    genres = list(world.genres)
    total = sum(genre_posts.values()) or 1
    favourite_genres = _pick(
        rng,
        genres,
        [0.5 * genre_posts[g] / total + 0.5 / len(genres) for g in genres],
        int(rng.integers(3, 5)),
    )
    weights: list[float] = (
        [float(w) for w in rng.dirichlet(np.ones(len(favourite_genres)))]
        if favourite_genres
        else []
    )
    taste_genres = dict(zip(favourite_genres, weights, strict=True))
    # Home town: likelier where more of the posts' gigs were.
    city_posts = world.city_posts()
    towns = sorted(city_posts)
    city = _pick(rng, towns, [city_posts[t] for t in towns], 1)
    social = float(rng.beta(2.0, 3.0))
    session_length = int(rng.integers(8, 41))
    # Favourite acts: 5–15, likelier the closer their genres are to the persona's.
    artists = list(world.artists)
    favourites = _pick(
        rng,
        artists,
        [0.05 + cosine(taste_genres, world.artist_genres[a]) for a in artists],
        int(rng.integers(5, 16)),
    )
    follow_artists = [a for a in favourites if rng.random() < 0.3 + 0.4 * social] or favourites[:1]
    # Authors: more for a social persona, likelier the closer their posts are to its taste.
    partial = Taste(taste_genres, frozenset(favourites), frozenset())
    authors = list(world.authors)
    by_author: dict[str, list[float]] = {a: [] for a in authors}
    for post_id, post in world.posts.items():
        by_author[post.author_id].append(true_affinity(partial, world.item(post_id), params))
    followed = _pick(
        rng,
        authors,
        [0.05 + float(np.mean(by_author[a])) for a in authors],
        int(rng.binomial(6, social)),
    )
    # Gigs: 0–3 with posts, likelier with a favourite act or in the home town.
    gigs = sorted({p.gig_id for p in world.posts.values() if p.gig_id})
    gig_score: dict[str, float] = dict.fromkeys(gigs, 0.05)
    starts: dict[str, datetime | None] = {}
    for post in world.posts.values():
        if post.gig_id:
            starts[post.gig_id] = post.gig_starts_at
            bonus = (0.5 if set(post.artist_ids) & set(favourites) else 0.0) + (
                0.3 if city and post.city == city[0] else 0.0
            )
            gig_score[post.gig_id] = max(gig_score[post.gig_id], 0.05 + bonus)
    attend = tuple(
        Attendance(g, attendance_status(starts.get(g), now))
        for g in sorted(_pick(rng, gigs, [gig_score[g] for g in gigs], int(rng.integers(0, 4))))
    )
    return SimPersona(
        n=n,
        seed=seed,
        taste=Taste(taste_genres, frozenset(favourites), frozenset(followed)),
        city=city[0] if city else None,
        social=social,
        session_length=session_length,
        follow_artists=tuple(sorted(follow_artists)),
        attend=attend,
        rounds=rounds_for(rng_for(seed, n, 2), sessions, days),
    )


def make(
    world: World, seed: int, count: int, sessions: int, days: int, now: datetime
) -> list[SimPersona]:
    """{@code count} personas of {@code seed}. Same seed, same world → the same personas."""
    if not 0 <= seed < MAX_SEED:
        raise ValueError(f"seed must be in [0, {MAX_SEED})")
    return [make_one(world, seed, n, sessions, days, now) for n in range(count)]

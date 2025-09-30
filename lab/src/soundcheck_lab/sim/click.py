"""The click model: how a synthetic persona reacts to one feed item.

SYNTHETIC: nothing here is a person. Each persona has a hidden taste; each post a hidden quality.
How likely the persona is to engage with an item is

    P(engage) = σ(bias + α·trueAffinity + β·quality − γ·log(1 + k) + ε),   ε ~ N(0, σε)

where k is the item's position in the session (people engage less the further they scroll) and
trueAffinity mixes how well the post's genres match the persona's (cosine), whether one of its acts
is a favourite, and whether the persona follows its author. The ranker never sees trueAffinity or
quality, only the events they cause, so the lab can test whether it learns them back.

Every function here is pure: randomness comes in as a `numpy.random.Generator`, so a seed fixes
every draw (tests/test_sim_click.py)."""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, fields, replace
from typing import Any, cast

import numpy as np
import numpy.typing as npt

from soundcheck_lab import events as ev


@dataclass(frozen=True)
class Params:
    """The click model's parameters. Fixed before any training (the rule: the model must
    not be tuned so a learned config wins)."""

    bias: float = -1.5  # the intercept: a cold item at the top engages σ(-1.5) ≈ 18 % of the time
    alpha: float = 5.0  # weight of trueAffinity (0..1): a perfect match at the top engages ≈ 97 %
    beta: float = 0.5  # weight of the post's hidden quality, N(0, 1)
    gamma: float = 0.3  # position decay per log(1 + k)
    noise_sd: float = 0.5  # σε: per-item mood
    w_genre: float = (
        0.6  # trueAffinity = w_genre·cosine + w_artist·favourite act + w_author·followed
    )
    w_artist: float = 0.3
    w_author: float = 0.1
    watch_engaged: tuple[float, float] = (6.0, 1.5)  # Beta(a, b) watch fraction when engaged
    watch_skipped: tuple[float, float] = (1.0, 6.0)  # ... and when not
    like_rate: float = 0.5  # P(LIKE | engaged) = like_rate · P(engage)
    profile_tap_rate: float = 0.06  # P(PROFILE_TAP | engaged) = rate · (0.5 + social)
    gig_tap_rate: float = 0.04  # P(GIG_TAP | engaged, the post has a gig)
    hide_rate: float = 0.03  # P(HIDE | not engaged, trueAffinity < hide_below)
    hide_below: float = 0.1


DEFAULT = Params()


def with_overrides(params: Params, overrides: Sequence[str]) -> Params:
    """`["alpha=2.0", "w_artist=0.7"]` → a copy of `params` with those numbers changed, for the
    sensitivity runs (does the lab still recover the rules when the rules change?). Only the scalar
    parameters can be set; the defaults stay fixed.

    Raises ValueError for an unknown or non-numeric parameter, or a missing value."""
    scalars = {f.name for f in fields(params) if isinstance(getattr(params, f.name), float)}
    changes: dict[str, float] = {}
    for item in overrides:
        name, sep, value = item.partition("=")
        name = name.strip()
        if not sep or not value.strip():
            raise ValueError(f"{item!r}: expected name=value")
        if name not in scalars:
            known = ", ".join(sorted(scalars))
            raise ValueError(f"{name!r} is not a click-model parameter ({known})")
        try:
            changes[name] = float(value)
        except ValueError as e:
            raise ValueError(f"{item!r}: {value!r} is not a number") from e
    # Every name is a float field (checked above); mypy can't see that through **.
    return replace(params, **cast(dict[str, Any], changes))


@dataclass(frozen=True)
class Taste:
    """A persona's hidden taste: genre weights (a Dirichlet draw, summing to 1), favourite acts and
    the authors it follows."""

    genres: Mapping[str, float]
    favourite_artists: frozenset[str]
    followed_authors: frozenset[str]


@dataclass(frozen=True)
class Item:
    """What the click model knows about a post: the world's ground truth (sim/world.py)."""

    genres: Mapping[str, float]
    artist_ids: Sequence[str]
    author_id: str
    quality: float
    has_gig: bool


@dataclass(frozen=True)
class Reaction:
    p_engage: float
    engaged: bool
    watch: float  # fraction of the clip watched, 0..1
    like: bool
    profile_tap: bool
    gig_tap: bool
    hide: bool


def sigmoid(x: float) -> float:
    return 1.0 / (1.0 + math.exp(-x))


def cosine(a: Mapping[str, float], b: Mapping[str, float]) -> float:
    """Cosine similarity of two sparse non-negative vectors; 0 when either is empty."""
    dot = sum(w * b[k] for k, w in a.items() if k in b)
    norm = math.sqrt(sum(w * w for w in a.values())) * math.sqrt(sum(w * w for w in b.values()))
    return dot / norm if norm > 0 else 0.0


def true_affinity(taste: Taste, item: Item, params: Params = DEFAULT) -> float:
    """0..1: how much this persona would like this post if quality and position didn't matter."""
    genre = cosine(taste.genres, item.genres)
    artist = 1.0 if taste.favourite_artists.intersection(item.artist_ids) else 0.0
    author = 1.0 if item.author_id in taste.followed_authors else 0.0
    return params.w_genre * genre + params.w_artist * artist + params.w_author * author


def p_engage(
    affinity: float, quality: float, position: int, params: Params = DEFAULT, noise: float = 0.0
) -> float:
    """σ(bias + α·affinity + β·quality − γ·log(1 + position) + noise)."""
    return sigmoid(
        params.bias
        + params.alpha * affinity
        + params.beta * quality
        - params.gamma * math.log1p(position)
        + noise
    )


def react(
    rng: np.random.Generator,
    taste: Taste,
    item: Item,
    position: int,
    *,
    social: float,
    liked_before: bool,
    params: Params = DEFAULT,
) -> Reaction:
    """One persona's reaction to one item. Draws the same number of random numbers whatever happens,
    so one item's outcome never shifts the draws of the next."""
    noise = float(rng.normal(0.0, params.noise_sd))
    u = rng.random(5)
    affinity = true_affinity(taste, item, params)
    p = p_engage(affinity, item.quality, position, params, noise)
    engaged = bool(u[0] < p)
    a, b = params.watch_engaged if engaged else params.watch_skipped
    watch = float(rng.beta(a, b))
    return Reaction(
        p_engage=p,
        engaged=engaged,
        watch=watch,
        like=engaged and not liked_before and bool(u[1] < params.like_rate * p),
        profile_tap=engaged and bool(u[2] < params.profile_tap_rate * (0.5 + social)),
        gig_tap=engaged and item.has_gig and bool(u[3] < params.gig_tap_rate),
        hide=(not engaged) and affinity < params.hide_below and bool(u[4] < params.hide_rate),
    )


def event_kinds(reaction: Reaction) -> list[tuple[str, float | None]]:
    """The events one reaction sends, in the app's order: IMPRESSION and VIEW_START for every item
    seen, WATCH_PROGRESS at each quarter crossed, COMPLETE at 95 %, then LIKE, the taps, HIDE."""
    kinds: list[tuple[str, float | None]] = [("IMPRESSION", None), ("VIEW_START", None)]
    kinds += ev.watch_kinds(reaction.watch)
    for flag, kind in (
        (reaction.like, "LIKE"),
        (reaction.profile_tap, "PROFILE_TAP"),
        (reaction.gig_tap, "GIG_TAP"),
        (reaction.hide, "HIDE"),
    ):
        if flag:
            kinds.append((kind, None))
    return kinds


def ranks(values: Sequence[float]) -> npt.NDArray[Any]:
    """1-based ranks, ties sharing their average rank."""
    _, inverse, counts = np.unique(
        np.asarray(values, dtype=float), return_inverse=True, return_counts=True
    )
    ends = np.cumsum(counts)
    average = ends - (counts - 1) / 2.0
    result: npt.NDArray[Any] = average[inverse]
    return result


def spearman(x: Sequence[float], y: Sequence[float]) -> float:
    """Spearman's rank correlation (ties averaged); 0 when either side is constant."""
    if len(x) != len(y):
        raise ValueError("spearman: x and y differ in length")
    rx, ry = ranks(x), ranks(y)
    rx, ry = rx - rx.mean(), ry - ry.mean()
    denominator = math.sqrt(float((rx * rx).sum()) * float((ry * ry).sum()))
    return float((rx * ry).sum()) / denominator if denominator > 0 else 0.0

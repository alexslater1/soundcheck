"""The shapes of the synthetic dataset. Pure functions on a seeded
`numpy.random.Generator`: the same seed and inputs give the same arrays, and nothing here touches a
database. Times are Unix seconds (float64, UTC); entities are referred to by their index.

- Zipf weights for who gets followed and which posts get engagement (a few get most of it);
- a power law for artist popularity;
- people and venues clustered round the UK cities by population, with jitter;
- gigs from 18 months ago to 6 months ahead, posts within a day of a past gig;
- events and likes over the last 60 days, weighted by the post's popularity.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta

import numpy as np
import numpy.typing as npt

Floats = npt.NDArray[np.float64]
Ints = npt.NDArray[np.int64]

DAY = 86_400.0
GIGS_BACK_DAYS = 548  # 18 months
GIGS_AHEAD_DAYS = 183  # 6 months
ENGAGEMENT_DAYS = 60  # events and likes; inside the 90-day event retention, so it never drops them
PARTITIONS_AHEAD_DAYS = 7  # as the API's start-up (EventPartitionMaintenance)
KM_PER_DEGREE = 111.32


def zipf_weights(n: int, exponent: float, rng: np.random.Generator) -> Floats:
    """Weights ∝ 1/rank^exponent, summing to 1, with the ranks shuffled over the n entities (so an
    entity's index says nothing about its popularity)."""
    ranks = rng.permutation(n) + 1
    w = 1.0 / np.power(ranks.astype(np.float64), exponent)
    return np.asarray(w / w.sum(), dtype=np.float64)


def power_law_popularity(n: int, alpha: float, rng: np.random.Generator) -> Floats:
    """Artist popularity in (0, 1]: Pareto-distributed (a few acts draw most of the fans), scaled
    so the
    most popular is 1."""
    raw = rng.pareto(alpha, n) + 1.0
    return np.asarray(raw / raw.max(), dtype=np.float64)


def cdf(weights: Floats) -> Floats:
    """The cumulative distribution to sample from with `pick`."""
    c = np.cumsum(weights)
    return np.asarray(c / c[-1], dtype=np.float64)


def pick(cumulative: Floats, k: int, rng: np.random.Generator) -> Ints:
    """k indices drawn (with replacement) in proportion to the weights behind `cumulative`."""
    idx = np.searchsorted(cumulative, rng.random(k), side="right")
    return np.minimum(idx, len(cumulative) - 1).astype(np.int64)


def allocate(total: int, weights: Floats, cap: int, rng: np.random.Generator) -> Ints:
    """Splits `total` over the entities in proportion to `weights`, none above `cap` (an author
    posts
    from at most so many gigs). Sums to exactly `total`."""
    if cap * len(weights) < total:
        raise ValueError("cap too low for the total")
    counts = rng.multinomial(total, weights / weights.sum()).astype(np.int64)
    while True:
        over = np.maximum(counts - cap, 0)
        spill = int(over.sum())
        if spill == 0:
            return counts
        counts -= over
        room = (counts < cap).astype(np.float64) * weights
        counts += rng.multinomial(spill, room / room.sum()).astype(np.int64)


def unique_pairs(
    n_pairs: int,
    src_cdf: Floats,
    dst_cdf: Floats,
    rng: np.random.Generator,
    no_self: bool,
) -> tuple[Ints, Ints]:
    """Exactly n_pairs distinct (src, dst) pairs, each side drawn from its weights; with
    `no_self`, never
    src == dst (a user can't follow themself). Duplicates are dropped and topped up, keeping the
    first
    draw of each pair, so the result is deterministic."""
    n_dst = len(dst_cdf)
    keys: Ints = np.empty(0, dtype=np.int64)
    while len(keys) < n_pairs:
        want = int((n_pairs - len(keys)) * 1.2) + 1000
        src = pick(src_cdf, want, rng)
        dst = pick(dst_cdf, want, rng)
        if no_self:
            keep = src != dst
            src, dst = src[keep], dst[keep]
        merged = np.concatenate([keys, src * n_dst + dst])
        _, first = np.unique(merged, return_index=True)
        keys = merged[np.sort(first)]
    keys = keys[:n_pairs]
    return keys // n_dst, keys % n_dst


def haversine_km(lat1: Floats, lng1: Floats, lat2: Floats, lng2: Floats) -> Floats:
    """Great-circle distance in km."""
    p1, p2 = np.radians(lat1), np.radians(lat2)
    dp, dl = p2 - p1, np.radians(lng2 - lng1)
    a = np.sin(dp / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(dl / 2) ** 2
    return np.asarray(2 * 6371.0 * np.arcsin(np.sqrt(a)), dtype=np.float64)


def places_near_cities(
    n: int,
    city_lat: Floats,
    city_lng: Floats,
    city_population: Floats,
    rng: np.random.Generator,
    scale_km: float = 4.0,
    max_km: float = 25.0,
) -> tuple[Ints, Floats, Floats]:
    """n points clustered round the cities: a city in proportion to its population, then a random
    bearing and a distance (exponential, mean `scale_km`, at most `max_km`) from its centre.
    Returns (city index, lat, lng)."""
    city = pick(cdf(city_population), n, rng)
    dist = np.minimum(rng.exponential(scale_km, n), max_km)
    bearing = rng.uniform(0, 2 * np.pi, n)
    lat0, lng0 = city_lat[city], city_lng[city]
    lat = lat0 + dist * np.cos(bearing) / KM_PER_DEGREE
    lng = lng0 + dist * np.sin(bearing) / (KM_PER_DEGREE * np.cos(np.radians(lat0)))
    return city, lat, lng


def gig_starts(n: int, now: float, rng: np.random.Generator) -> Floats:
    """Gig start times from 18 months ago to 6 months ahead: a uniform day, doors-to-stage in the
    evening (18:00–21:30 UTC, on the half hour)."""
    first = _midnight(now) - GIGS_BACK_DAYS * DAY
    days = rng.integers(0, GIGS_BACK_DAYS + GIGS_AHEAD_DAYS, n)
    half_hours = rng.integers(0, 8, n)
    return first + days * DAY + 18 * 3600.0 + half_hours * 1800.0


def post_times(gig_start: Floats, now: float, rng: np.random.Generator) -> Floats:
    """When a post from a past gig is published: within a day of the gig's start (from the encore to
    the next day's train home), never in the future."""
    t = gig_start + rng.uniform(3600.0, DAY, len(gig_start))
    return np.minimum(t, now - 60.0)


def partition_days(now: float, window_days: int = ENGAGEMENT_DAYS) -> list[date]:
    """The UTC days that need an event partition: every day of the engagement window up to today,
    and the week ahead that the API creates at start-up."""
    today = datetime.fromtimestamp(now, UTC).date()
    first = today - timedelta(days=window_days)
    return [first + timedelta(days=i) for i in range(window_days + PARTITIONS_AHEAD_DAYS + 1)]


def engagement_window(now: float, window_days: int = ENGAGEMENT_DAYS) -> tuple[float, float]:
    """[start, end) of the events and likes: the last `window_days` days, starting at a midnight
    so the
    first day's partition is the first one created."""
    return _midnight(now) - window_days * DAY, now


def engagement_times(
    published: Floats, start: float, end: float, rng: np.random.Generator
) -> Floats:
    """A time in the window for each engagement, never before its post was published."""
    lo = np.maximum(published, start)
    return lo + rng.random(len(published)) * (end - lo)


def day_events(
    day_start: float,
    day_end: float,
    n: int,
    user_cdf: Floats,
    post_cdf: Floats,
    published: Floats,
    rng: np.random.Generator,
) -> tuple[Ints, Ints, Floats]:
    """One day's n events (user, post, time), sorted by time. Posts are drawn by popularity; an
    event
    drawn for a post not yet published at its time is drawn again."""
    users: list[Ints] = []
    posts: list[Ints] = []
    times: list[Floats] = []
    have = 0
    while have < n:
        want = n - have
        post = pick(post_cdf, want, rng)
        t = rng.uniform(day_start, day_end, want)
        ok = published[post] <= t
        users.append(pick(user_cdf, int(ok.sum()), rng))
        posts.append(post[ok])
        times.append(t[ok])
        have += int(ok.sum())
    u, p, t = np.concatenate(users), np.concatenate(posts), np.concatenate(times)
    order = np.argsort(t, kind="stable")
    return u[order], p[order], t[order]


EVENT_TYPES = (
    "IMPRESSION",
    "VIEW_START",
    "WATCH_PROGRESS",
    "COMPLETE",
    "LIKE",
    "PROFILE_TAP",
    "GIG_TAP",
    "SHARE",
    "HIDE",
)
# A feed is mostly scrolled past: impressions, then fewer views, fewer still watched to the end.
EVENT_TYPE_WEIGHTS = (0.50, 0.22, 0.14, 0.06, 0.04, 0.015, 0.015, 0.006, 0.004)


def event_types(n: int, rng: np.random.Generator) -> tuple[Ints, Floats]:
    """Each event's type (index into EVENT_TYPES) and value (the watched fraction of a
    WATCH_PROGRESS: 0.25, 0.5 or 0.75; NaN otherwise)."""
    kind = pick(cdf(np.array(EVENT_TYPE_WEIGHTS)), n, rng)
    value = np.full(n, np.nan)
    progress = kind == EVENT_TYPES.index("WATCH_PROGRESS")
    value[progress] = rng.choice(np.array([0.25, 0.5, 0.75]), int(progress.sum()))
    return kind, value


def _midnight(t: float) -> float:
    return float(np.floor(t / DAY) * DAY)

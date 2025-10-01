"""seed-scale's generators: deterministic per seed, and the expected shapes."""

from __future__ import annotations

import csv
import uuid
from datetime import UTC, datetime
from pathlib import Path

import numpy as np
import polars as pl
import pytest

from soundcheck_lab.scale import distributions as d
from soundcheck_lab.scale.dataset import Dataset, Reference
from soundcheck_lab.scale.ids import uuid7, uuid7_strings
from soundcheck_lab.scale.load import check_local, partition_name
from soundcheck_lab.scale.profiles import PROFILES, Profile, profile

CITIES = Path(__file__).resolve().parents[2] / "fixtures" / "geo" / "uk-cities.csv"
NOW = datetime(2026, 10, 6, 1, 0, tzinfo=UTC).timestamp()
TINY = Profile("tiny", 3_000, 600, 120, 1_500, 6_000, 40_000, 5_000, 30_000, 60_000)


def reference() -> Reference:
    with CITIES.open() as f:
        rows = list(csv.DictReader(f))
    return Reference(
        city_id=np.array([int(r["id"]) for r in rows]),
        city_name=[r["name"] for r in rows],
        city_lat=np.array([float(r["lat"]) for r in rows]),
        city_lng=np.array([float(r["lng"]) for r in rows]),
        city_population=np.array([float(r["population"]) for r in rows]),
        genre_ids=np.arange(1, 25),
    )


def build(seed: int = 42) -> tuple[Dataset, dict[str, pl.DataFrame]]:
    data = Dataset(TINY, seed, NOW, reference(), "$argon2id$v=19$m=1,t=1,p=1$c2FsdA$aGFzaA")
    frames = {
        "users": data.users(),
        "prefs": data.genre_preferences(),
        "artists": data.artists(),
        "artist_genres": data.artist_genres(),
        "venues": data.venues(),
        "gigs": data.gigs(),
        "gig_artists": data.gig_artists(),
    }
    data.plan_content()
    frames |= {
        "media": data.media(),
        "posts": data.posts(),
        "clips": data.clips(),
        "post_artists": data.post_artists(),
        "attendance": data.attendance(),
        "follows": data.user_follows(),
        "artist_follows": data.artist_follows(),
        "likes": data.likes(),
        "events": pl.concat(list(data.events(chunk_rows=20_000))),
    }
    return data, frames


@pytest.fixture(scope="module")
def tiny() -> tuple[Dataset, dict[str, pl.DataFrame]]:
    return build()


# ── ids ──────────────────────────────────────────────────────────────────────────────────────────


def test_uuid7_has_version_7_and_the_rfc_variant() -> None:
    u = uuid7(1_760_000_000_000, bytes(range(10)))
    assert u.version == 7
    assert u.variant == uuid.RFC_4122
    assert int(u.hex[:12], 16) == 1_760_000_000_000


def test_uuid7s_sort_by_created_at() -> None:
    rng = np.random.default_rng(1)
    ms = np.sort(rng.integers(1_700_000_000_000, 1_800_000_000_000, 5_000))
    ids = uuid7_strings(ms, rng)
    assert ids == sorted(ids)
    assert all(uuid.UUID(i).version == 7 for i in ids[:50])
    assert len(set(ids)) == len(ids)


def test_uuid7_batch_is_deterministic_per_seed() -> None:
    ms = np.arange(1_700_000_000_000, 1_700_000_000_100)
    assert uuid7_strings(ms, np.random.default_rng(3)) == uuid7_strings(
        ms, np.random.default_rng(3)
    )


# ── pure distributions ─────────────────────────────────────────────────────────────────────────


def test_allocate_sums_to_the_total_and_respects_the_cap() -> None:
    rng = np.random.default_rng(5)
    w = d.zipf_weights(1_000, 1.2, rng)
    counts = d.allocate(50_000, w, 120, rng)
    assert counts.sum() == 50_000
    assert counts.max() <= 120


def test_unique_pairs_are_distinct_exact_and_never_self() -> None:
    rng = np.random.default_rng(6)
    n = 500
    src, dst = d.unique_pairs(
        20_000, d.cdf(np.ones(n)), d.cdf(d.zipf_weights(n, 0.8, rng)), rng, True
    )
    assert len(src) == 20_000
    assert len(set(zip(src.tolist(), dst.tolist(), strict=True))) == 20_000
    assert not np.any(src == dst)


def test_partition_days_cover_the_engagement_window_and_the_week_ahead() -> None:
    days = d.partition_days(NOW)
    assert days[0].isoformat() == "2026-08-07"
    assert days[-1].isoformat() == "2026-10-13"
    assert partition_name("signals.interaction_event", days[0]) == "interaction_event_p20260807"


# ── the dataset ────────────────────────────────────────────────────────────────────────────────


def test_same_seed_same_rows() -> None:
    _, a = build(7)
    _, b = build(7)
    for name in ("users", "gigs", "posts", "follows", "likes", "events"):
        assert a[name].equals(b[name]), name
    _, c = build(8)
    assert not a["posts"].equals(c["posts"])


def test_row_counts_are_the_profiles(tiny: tuple[Dataset, dict[str, pl.DataFrame]]) -> None:
    _, f = tiny
    assert f["users"].height == TINY.users
    assert f["artists"].height == TINY.artists
    assert f["venues"].height == TINY.venues
    assert f["gigs"].height == TINY.gigs
    assert f["posts"].height == TINY.posts
    assert f["follows"].height == TINY.user_follows
    assert f["artist_follows"].height == TINY.artist_follows
    assert f["likes"].height == TINY.likes
    assert f["events"].height == TINY.events


def test_the_top_one_percent_hold_a_stated_share_of_follows(
    tiny: tuple[Dataset, dict[str, pl.DataFrame]],
) -> None:
    """Zipf (s = 0.8) over who is followed: the top 1 % of accounts hold 15–45 % of all follows
    (a uniform choice would give them 1 %)."""
    data, _ = tiny
    received = np.sort(np.bincount(data.follow_dst, minlength=TINY.users))[::-1]
    top = received[: TINY.users // 100].sum() / received.sum()
    assert 0.15 <= top <= 0.45, top


def test_every_post_sits_within_a_day_of_its_past_gig(
    tiny: tuple[Dataset, dict[str, pl.DataFrame]],
) -> None:
    data, _ = tiny
    starts = data.gig_starts[data.post_gig]
    assert np.all(np.abs(data.post_published - starts) <= d.DAY)
    assert np.all(starts < NOW)
    assert np.all(data.post_published <= NOW)


def test_an_author_posts_once_per_gig(tiny: tuple[Dataset, dict[str, pl.DataFrame]]) -> None:
    data, _ = tiny
    keys = data.post_author * TINY.gigs + data.post_gig
    assert len(np.unique(keys)) == TINY.posts


def test_every_user_sits_within_30_km_of_a_real_city(
    tiny: tuple[Dataset, dict[str, pl.DataFrame]],
) -> None:
    data, _ = tiny
    ref = reference()
    km = d.haversine_km(
        data.user_lat, data.user_lng, ref.city_lat[data.user_city], ref.city_lng[data.user_city]
    )
    assert km.max() <= 30.0


def test_events_fall_only_on_days_that_get_partitions(
    tiny: tuple[Dataset, dict[str, pl.DataFrame]],
) -> None:
    _, f = tiny
    days = set(f["events"]["occurred_at"].dt.date().unique().to_list())
    assert days <= set(d.partition_days(NOW))
    latest = f["events"]["occurred_at"].max()
    assert isinstance(latest, datetime)
    assert latest <= datetime.fromtimestamp(NOW, UTC).replace(tzinfo=None)


def test_engagement_never_precedes_its_post(tiny: tuple[Dataset, dict[str, pl.DataFrame]]) -> None:
    _, f = tiny
    published = f["posts"].select(pl.col("id").alias("post_id"), "published_at")
    for name, at in (("likes", "created_at"), ("events", "occurred_at")):
        joined = f[name].join(published, on="post_id")
        assert joined.height == f[name].height
        assert (joined[at] >= joined["published_at"]).all(), name


def test_event_ids_ascend_with_time(tiny: tuple[Dataset, dict[str, pl.DataFrame]]) -> None:
    _, f = tiny
    events = f["events"]
    assert events["id"].is_sorted()
    assert events["occurred_at"].is_sorted()
    assert events["id"].n_unique() == events.height


def test_like_counts_match_the_like_rows(tiny: tuple[Dataset, dict[str, pl.DataFrame]]) -> None:
    _, f = tiny
    per_post = f["likes"].group_by("post_id").len()
    joined = f["posts"].join(per_post, left_on="id", right_on="post_id", how="left").fill_null(0)
    assert (joined["like_count"] == joined["len"]).all()


def test_every_clip_has_its_own_ready_media(tiny: tuple[Dataset, dict[str, pl.DataFrame]]) -> None:
    _, f = tiny
    assert f["clips"]["media_id"].n_unique() == f["clips"].height
    assert set(f["clips"]["media_id"]) == set(f["media"]["id"])
    assert (f["media"]["status"] == "READY").all()
    assert f["clips"]["post_id"].n_unique() == TINY.posts


def test_gig_locations_are_their_venues(tiny: tuple[Dataset, dict[str, pl.DataFrame]]) -> None:
    _, f = tiny
    joined = f["gigs"].join(
        f["venues"].select(
            pl.col("id").alias("venue_id"), pl.col("location").alias("venue_location")
        ),
        on="venue_id",
    )
    assert (joined["location"] == joined["venue_location"]).all()


def test_login_pool_is_500_public_followers(tiny: tuple[Dataset, dict[str, pl.DataFrame]]) -> None:
    data, f = tiny
    pool = data.login_pool()
    assert len(pool) == 500
    public = f["users"].filter(pl.col("profile_visibility") == "PUBLIC")["handle"]
    assert set(pool) <= set(public)


# ── guards ─────────────────────────────────────────────────────────────────────────────────────


def test_only_the_local_perf_database_is_accepted() -> None:
    check_local("postgresql://s:s@127.0.0.1:55500/soundcheck_perf", "http://localhost:8201")
    with pytest.raises(ValueError):
        check_local("postgresql://s:s@localhost:5432/soundcheck", "http://localhost:8201")
    with pytest.raises(ValueError):
        check_local("postgresql://s:s@rds.amazonaws.com/soundcheck_perf", "http://localhost:8201")
    with pytest.raises(ValueError):
        check_local(
            "postgresql://s:s@localhost:55500/soundcheck_perf", "https://soundcheck.example"
        )


def test_profiles_have_their_fixed_counts() -> None:
    assert profile("medium").posts == 1_000_000
    assert profile("small").events == 1_000_000
    assert PROFILES["large"].users == 1_000_000
    with pytest.raises(ValueError):
        profile("large")

"""Builds the dataset's tables as polars frames, in foreign-key order, from a profile and a seed.

Each `*_frames` method returns the rows of one table (or yields them in chunks, for the events) with
columns named as the table's. The shapes come from `distributions`; this module only lays them out
as
rows. Nothing here touches the database: the reference data it needs (cities, genres, the shared
password hash) is passed in.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from dataclasses import dataclass
from typing import Any

import numpy as np
import numpy.typing as npt
import polars as pl

from soundcheck_lab.scale import distributions as d
from soundcheck_lab.scale.ids import uuid7_strings
from soundcheck_lab.scale.profiles import Profile

EMAIL_DOMAIN = "perf.soundcheck.test"
LOGIN_POOL = 500
PRIVATE_SHARE = 0.05  # users with a private profile
MONTH = 30.44 * d.DAY

# Synthetic names: three word lists give 64,000 distinct acts, enough for every profile.
_ADJ = [
    "Velvet",
    "Neon",
    "Silver",
    "Hollow",
    "Electric",
    "Paper",
    "Golden",
    "Static",
    "Crimson",
    "Quiet",
    "Wild",
    "Lunar",
    "Rusty",
    "Glass",
    "Midnight",
    "Broken",
    "Northern",
    "Lost",
    "Bright",
    "Fading",
    "Sonic",
    "Iron",
    "Cosmic",
    "Pale",
    "Restless",
    "Amber",
    "Frozen",
    "Distant",
    "Burning",
    "Hidden",
    "Gentle",
    "Savage",
    "Little",
    "Royal",
    "Endless",
    "Wandering",
    "Secret",
    "Violet",
    "Atomic",
    "Stray",
]
_NOUN = [
    "Foxes",
    "Echoes",
    "Harbours",
    "Tides",
    "Wolves",
    "Lanterns",
    "Rivers",
    "Pilots",
    "Ghosts",
    "Comets",
    "Engines",
    "Gardens",
    "Mirrors",
    "Sparrows",
    "Orchards",
    "Signals",
    "Thieves",
    "Saints",
    "Machines",
    "Horses",
    "Satellites",
    "Kites",
    "Islands",
    "Choirs",
    "Arcades",
    "Owls",
    "Riots",
    "Canyons",
    "Daggers",
    "Bandits",
    "Poets",
    "Waves",
    "Hearts",
    "Crowns",
    "Shadows",
    "Drums",
    "Bells",
    "Cities",
    "Strangers",
    "Animals",
]
_PLACE = [
    "Club",
    "Rooms",
    "Hall",
    "Social",
    "Tavern",
    "Lounge",
    "Theatre",
    "Arms",
    "Works",
    "Exchange",
    "Bar",
    "Cellar",
    "Ballroom",
    "Institute",
    "Warehouse",
    "Chapel",
    "Studio",
    "Yard",
    "Depot",
    "Garage",
]

# The worker's ladder (media/internal/transcode/Rung.java) for a portrait phone clip.
_RENDITIONS_PORTRAIT = json.dumps(
    [
        {
            "name": "360p",
            "width": 360,
            "height": 640,
            "bitrate": 800000,
            "playlist": "360p/index.m3u8",
        },
        {
            "name": "540p",
            "width": 540,
            "height": 960,
            "bitrate": 1600000,
            "playlist": "540p/index.m3u8",
        },
        {
            "name": "720p",
            "width": 720,
            "height": 1280,
            "bitrate": 3000000,
            "playlist": "720p/index.m3u8",
        },
    ]
)
_BLURHASH = "LEHV6nWB2yk8pyo0adR*.7kCMdnj"


@dataclass(frozen=True)
class Reference:
    """What the database already holds: the cities (V003, loaded at API start) and the genres."""

    city_id: npt.NDArray[Any]
    city_name: list[str]
    city_lat: npt.NDArray[Any]
    city_lng: npt.NDArray[Any]
    city_population: npt.NDArray[Any]
    genre_ids: npt.NDArray[Any]


def _ids(seconds: npt.NDArray[Any], rng: np.random.Generator) -> pl.Series:
    return pl.Series(uuid7_strings((seconds * 1000).astype(np.int64), rng), dtype=pl.String)


def _ts(seconds: npt.NDArray[Any]) -> pl.Series:
    return pl.Series((seconds * 1_000_000).astype(np.int64)).cast(pl.Datetime("us"))


def _point(lat: npt.NDArray[Any], lng: npt.NDArray[Any]) -> pl.Series:
    frame = pl.DataFrame({"lat": lat, "lng": lng})
    return frame.select(
        pl.format("SRID=4326;POINT({} {})", pl.col("lng").round(6), pl.col("lat").round(6))
    ).to_series()


def _names(n: int, rng: np.random.Generator) -> list[str]:
    combos = len(_ADJ) * len(_NOUN) * len(_PLACE)
    if n > combos:
        raise ValueError("not enough distinct names")
    picks = rng.choice(combos, n, replace=False)
    out = []
    for c in picks.tolist():
        a, rest = divmod(c, len(_NOUN) * len(_PLACE))
        b, _ = divmod(rest, len(_PLACE))
        out.append(f"{_ADJ[a]} {_NOUN[b]}")
    return out


def _distinct_per_owner(
    owner: npt.NDArray[Any],
    choices: npt.NDArray[Any],
    n_choices: int,
    cumulative: npt.NDArray[Any],
    rng: np.random.Generator,
) -> npt.NDArray[Any]:
    """Redraws `choices[i]` until no owner has the same choice twice (an author posts once per
    gig)."""
    choice = choices.copy()
    while True:
        keys = owner * n_choices + choice
        _, first = np.unique(keys, return_index=True)
        dup = np.ones(len(keys), dtype=bool)
        dup[first] = False
        if not dup.any():
            return choice
        choice[dup] = d.pick(cumulative, int(dup.sum()), rng)


class Dataset:
    """The dataset of one profile and seed. Build the tables in the order of the methods."""

    def __init__(
        self, profile: Profile, seed: int, now: float, ref: Reference, password_hash: str
    ) -> None:
        self.p = profile
        self.now = now
        self.ref = ref
        self.password_hash = password_hash
        self.rng = np.random.default_rng(seed)

    # ── people ───────────────────────────────────────────────────────────────────────────────
    def users(self) -> pl.DataFrame:
        n, rng = self.p.users, self.rng
        # Everyone joined before the oldest gig with posts (18 months back): no post predates its
        # author.
        created = self.now - rng.uniform(19 * MONTH, 30 * MONTH, n)
        self.user_created = created
        self.user_id = _ids(created, rng)
        city, lat, lng = d.places_near_cities(
            n, self.ref.city_lat, self.ref.city_lng, self.ref.city_population.astype(float), rng
        )
        self.user_city, self.user_lat, self.user_lng = city, lat, lng
        self.user_private = rng.random(n) < PRIVATE_SHARE
        # How active a user is (likes, events, follows given): lognormal, a long tail of heavy
        # users.
        self.user_cdf = d.cdf(rng.lognormal(0.0, 1.0, n))
        idx = np.arange(n)
        return pl.DataFrame(
            {
                "id": self.user_id,
                "email": [f"perf-u{i}@{EMAIL_DOMAIN}" for i in idx],
                "handle": [f"perf_u{i}" for i in idx],
                "display_name": [f"Perf User {i}" for i in idx],
                "home_city_id": self.ref.city_id[city],
                "home_location": _point(lat, lng),
                "password_hash": pl.repeat(self.password_hash, n, eager=True),
                "role": pl.repeat("USER", n, eager=True),
                "onboarded_at": _ts(created + 300.0),
                "created_at": _ts(created),
                "updated_at": _ts(created),
                "profile_visibility": np.where(self.user_private, "PRIVATE", "PUBLIC"),
            }
        )

    def genre_preferences(self) -> pl.DataFrame:
        rng = self.rng
        self.genre_cdf = d.cdf(d.zipf_weights(len(self.ref.genre_ids), 1.0, rng))
        u, g = d.unique_pairs(
            3 * self.p.users, d.cdf(np.ones(self.p.users)), self.genre_cdf, rng, False
        )
        return pl.DataFrame(
            {
                "user_id": self.user_id.gather(u),
                "genre_id": self.ref.genre_ids[g].astype(np.int16),
                "weight": np.ones(len(u), dtype=np.float32),
                "created_at": _ts(self.user_created[u]),
            }
        )

    # ── catalogue ────────────────────────────────────────────────────────────────────────────
    def artists(self) -> pl.DataFrame:
        n, rng = self.p.artists, self.rng
        self.artist_popularity = d.power_law_popularity(n, 1.2, rng)
        created = self.now - rng.uniform(20 * MONTH, 36 * MONTH, n)
        self.artist_id = _ids(created, rng)
        self.artist_name = _names(n, rng)
        return pl.DataFrame(
            {
                "id": self.artist_id,
                "name": self.artist_name,
                "name_norm": [s.lower() for s in self.artist_name],
                "popularity": self.artist_popularity.astype(np.float32),
                "created_at": _ts(created),
                "updated_at": _ts(created),
            }
        )

    def artist_genres(self) -> pl.DataFrame:
        n, rng = self.p.artists, self.rng
        primary = d.pick(self.genre_cdf, n, rng)
        second = d.pick(self.genre_cdf, n, rng)
        has_second = (rng.random(n) < 0.5) & (second != primary)
        artist = np.concatenate([np.arange(n), np.arange(n)[has_second]])
        genre = np.concatenate([primary, second[has_second]])
        weight = np.concatenate([np.ones(n), np.full(int(has_second.sum()), 0.5)])
        self.artist_genre = (artist, genre, weight)
        return pl.DataFrame(
            {
                "artist_id": self.artist_id.gather(artist),
                "genre_id": self.ref.genre_ids[genre].astype(np.int16),
                "weight": weight.astype(np.float32),
            }
        )

    def venues(self) -> pl.DataFrame:
        n, rng = self.p.venues, self.rng
        city, lat, lng = d.places_near_cities(
            n,
            self.ref.city_lat,
            self.ref.city_lng,
            self.ref.city_population.astype(float),
            rng,
            scale_km=2.0,
            max_km=15.0,
        )
        self.venue_lat, self.venue_lng = lat, lng
        created = self.now - rng.uniform(20 * MONTH, 36 * MONTH, n)
        self.venue_id = _ids(created, rng)
        place = rng.integers(0, len(_PLACE), n)
        base = _names(n, rng)
        names = [f"The {b.split()[1][:-1]} {_PLACE[k]}" for b, k in zip(base, place, strict=True)]
        self.venue_name = names
        return pl.DataFrame(
            {
                "id": self.venue_id,
                "name": names,
                "name_norm": [s.lower() for s in names],
                "city": [self.ref.city_name[c] for c in city],
                "location": _point(lat, lng),
                "created_at": _ts(created),
                "updated_at": _ts(created),
            }
        )

    def gigs(self) -> pl.DataFrame:
        n, rng = self.p.gigs, self.rng
        venue = d.pick(d.cdf(d.zipf_weights(self.p.venues, 0.7, rng)), n, rng)
        starts = d.gig_starts(n, self.now, rng)
        created = np.minimum(starts - 90 * d.DAY, self.now - d.DAY)
        self.gig_starts, self.gig_venue = starts, venue
        self.gig_id = _ids(created, rng)

        # The bill: a headliner by popularity, then up to three support acts.
        size = d.pick(d.cdf(np.array([0.30, 0.35, 0.25, 0.10])), n, rng) + 1
        gig = np.repeat(np.arange(n), size)
        headliner_cdf = d.cdf(self.artist_popularity)
        support_cdf = d.cdf(np.sqrt(self.artist_popularity))
        first = np.r_[0, np.cumsum(size)[:-1]]
        is_head = np.zeros(len(gig), dtype=bool)
        is_head[first] = True
        artist = np.where(
            is_head, d.pick(headliner_cdf, len(gig), rng), d.pick(support_cdf, len(gig), rng)
        )
        artist = _distinct_per_owner(gig, artist, self.p.artists, support_cdf, rng)
        order = np.arange(len(gig)) - np.repeat(first, size)
        self.bill = (gig, artist, order, first, size)
        headliner = artist[first]
        self.gig_headliner = headliner
        titles = [
            self.artist_name[h] + (" + support" if s > 1 else "")
            for h, s in zip(headliner.tolist(), size.tolist(), strict=True)
        ]
        hashes = rng.integers(0, 256, size=(n, 16), dtype=np.uint8).tobytes().hex()
        return pl.DataFrame(
            {
                "id": self.gig_id,
                "title": titles,
                "venue_id": self.venue_id.gather(venue),
                "location": _point(self.venue_lat[venue], self.venue_lng[venue]),
                "starts_at": _ts(starts),
                "doors_at": _ts(starts - 3600.0),
                "status": pl.repeat("SCHEDULED", n, eager=True),
                "content_hash": [f"\\x{hashes[32 * i : 32 * i + 32]}" for i in range(n)],
                "created_at": _ts(created),
                "updated_at": _ts(created),
            }
        )

    def gig_artists(self) -> pl.DataFrame:
        gig, artist, order, _, _ = self.bill
        return pl.DataFrame(
            {
                "gig_id": self.gig_id.gather(gig),
                "artist_id": self.artist_id.gather(artist),
                "billing_order": order.astype(np.int16),
            }
        )

    # ── content ──────────────────────────────────────────────────────────────────────────────
    def plan_posts(self) -> None:
        """Who posted from which past gig, and when; and how popular each post is."""
        n, rng = self.p.posts, self.rng
        past = np.flatnonzero(self.gig_starts <= self.now - 2 * 3600.0)
        # Gigs of popular headliners get more clips.
        past_cdf = d.cdf(np.sqrt(self.artist_popularity[self.gig_headliner[past]]))
        # 40 % of users never post; the rest post in proportion to a long-tailed activity.
        poster = rng.random(self.p.users) < 0.6
        weights = np.where(poster, rng.lognormal(0.0, 1.2, self.p.users), 0.0)
        cap = min(400, len(past) // 4)
        per_author = d.allocate(n, weights, cap, rng)
        author = np.repeat(np.arange(self.p.users), per_author)
        choice = _distinct_per_owner(author, d.pick(past_cdf, n, rng), len(past), past_cdf, rng)
        gig = past[choice]
        published = d.post_times(self.gig_starts[gig], self.now, rng)
        order = np.argsort(published, kind="stable")
        self.post_author, self.post_gig, self.post_published = (
            author[order],
            gig[order],
            published[order],
        )
        self.post_created = self.post_published - rng.uniform(60.0, 600.0, n)
        self.post_id = _ids(self.post_created, rng)
        # Engagement: Zipf over posts, three times as likely for a post from the engagement window.
        start, _ = d.engagement_window(self.now)
        recent = np.where(self.post_published >= start, 3.0, 1.0)
        self.post_cdf = d.cdf(d.zipf_weights(n, 1.0, rng) * recent)

    def plan_content(self) -> None:
        """Posts, their clips and their likes, planned before any is written: a post's like_count is
        the number of its like rows."""
        self.plan_posts()
        self.plan_clips()
        self.like_frame = self._likes()

    def likes(self) -> pl.DataFrame:
        return self.like_frame

    def _likes(self) -> pl.DataFrame:
        rng = self.rng
        u, p = d.unique_pairs(self.p.likes, self.user_cdf, self.post_cdf, rng, False)
        start, end = d.engagement_window(self.now)
        at = d.engagement_times(self.post_published[p], start, end, rng)
        self.like_count = np.bincount(p, minlength=self.p.posts)
        return pl.DataFrame(
            {
                "user_id": self.user_id.gather(u),
                "post_id": self.post_id.gather(p),
                "created_at": _ts(at),
            }
        )

    def posts(self) -> pl.DataFrame:
        n = self.p.posts
        gig = self.post_gig
        day = (
            pl.Series((self.gig_starts[gig] * 1000).astype(np.int64))
            .cast(pl.Datetime("ms", "UTC"))
            .dt.convert_time_zone("Europe/London")
            .dt.date()
        )
        captions = [f"{self.artist_name[h]} live" for h in self.gig_headliner[gig].tolist()]
        return pl.DataFrame(
            {
                "id": self.post_id,
                "author_id": self.user_id.gather(self.post_author),
                "gig_id": self.gig_id.gather(gig),
                "caption": captions,
                "status": pl.repeat("PUBLISHED", n, eager=True),
                "like_count": self.like_count.astype(np.int32),
                "created_at": _ts(self.post_created),
                "updated_at": _ts(self.post_published),
                "published_at": _ts(self.post_published),
                "day": day,
            }
        )

    def plan_clips(self) -> None:
        """1–3 clips a post, each of an act on the gig's bill (the headliner more often)."""
        n, rng = self.p.posts, self.rng
        count = d.pick(d.cdf(np.array([0.80, 0.15, 0.05])), n, rng) + 1
        post = np.repeat(np.arange(n), count)
        position = np.arange(len(post)) - np.repeat(np.r_[0, np.cumsum(count)[:-1]], count)
        _, bill_artist, _, first, size = self.bill
        gig = self.post_gig[post]
        slot = np.where(
            rng.random(len(post)) < 0.6, 0, (rng.random(len(post)) * size[gig]).astype(np.int64)
        )
        self.clip_post, self.clip_position = post, position
        self.clip_artist = bill_artist[first[gig] + slot]
        created = self.post_created[post]
        self.clip_created = created
        self.media_id = _ids(created - 30.0, rng)
        self.clip_id = _ids(created, rng)

    def media(self) -> pl.DataFrame:
        """A READY asset per clip (post_clip.media_id is unique). Rows only: the keys follow the
        worker's layout, and no object exists behind them (the measured endpoints never fetch
        one)."""
        k, rng = len(self.clip_post), self.rng
        owner = self.user_id.gather(self.post_author[self.clip_post])
        size = rng.integers(4_000_000, 60_000_000, k)
        frame = pl.DataFrame({"id": self.media_id, "owner_id": owner, "bytes": size})
        created = self.clip_created - 30.0
        return frame.select(
            "id",
            "owner_id",
            pl.lit("VIDEO").alias("kind"),
            pl.lit("READY").alias("status"),
            pl.lit("video/mp4").alias("content_type"),
            pl.col("bytes").alias("declared_bytes"),
            pl.col("bytes").alias("actual_bytes"),
            pl.format("uploads/{}/{}/original", "owner_id", "id").alias("original_key"),
            pl.when(pl.col("bytes") > 16_000_000)
            .then(pl.lit("MULTIPART"))
            .otherwise(pl.lit("SINGLE"))
            .alias("upload_strategy"),
            pl.lit('{"hasAudio": true}').alias("probe"),
            pl.Series(rng.integers(8_000, 60_000, k).astype(np.int32)).alias("duration_ms"),
            pl.lit(1080).alias("width"),
            pl.lit(1920).alias("height"),
            pl.format("media/{}/v1/", "id").alias("output_prefix"),
            pl.lit(_RENDITIONS_PORTRAIT).alias("renditions"),
            pl.format("media/{}/v1/poster.jpg", "id").alias("poster_key"),
            pl.format("media/{}/v1/thumb.webp", "id").alias("thumb_key"),
            pl.lit(_BLURHASH).alias("blurhash"),
            pl.lit(1).alias("attempts"),
            _ts(created).alias("created_at"),
            _ts(created + 20.0).alias("updated_at"),
            _ts(created + 5.0).alias("uploaded_at"),
            _ts(created + 20.0).alias("ready_at"),
        )

    def clips(self) -> pl.DataFrame:
        return pl.DataFrame(
            {
                "id": self.clip_id,
                "post_id": self.post_id.gather(self.clip_post),
                "owner_id": self.user_id.gather(self.post_author[self.clip_post]),
                "position": self.clip_position.astype(np.int32),
                "artist_id": self.artist_id.gather(self.clip_artist),
                "media_id": self.media_id,
                "created_at": _ts(self.clip_created),
            }
        )

    def post_artists(self) -> pl.DataFrame:
        keys = np.unique(self.clip_post * self.p.artists + self.clip_artist)
        post, artist = keys // self.p.artists, keys % self.p.artists
        self.post_artist = (post, artist)
        return pl.DataFrame(
            {"post_id": self.post_id.gather(post), "artist_id": self.artist_id.gather(artist)}
        )

    def attendance(self) -> pl.DataFrame:
        """WENT for each post's author (as the app records it from a post), and INTERESTED or
        GOING for
        about two upcoming gigs per user."""
        rng = self.rng
        upcoming = np.flatnonzero(self.gig_starts > self.now)
        u, g = d.unique_pairs(
            2 * self.p.users, self.user_cdf, d.cdf(np.ones(len(upcoming))), rng, False
        )
        going = rng.random(len(u)) < 0.5
        at = self.now - rng.uniform(0, 60 * d.DAY, len(u))
        went_at = self.post_published
        return pl.concat(
            [
                pl.DataFrame(
                    {
                        "user_id": self.user_id.gather(self.post_author),
                        "gig_id": self.gig_id.gather(self.post_gig),
                        "status": pl.repeat("WENT", self.p.posts, eager=True),
                        "created_at": _ts(went_at),
                        "updated_at": _ts(went_at),
                        "source": pl.repeat("POST", self.p.posts, eager=True),
                    }
                ),
                pl.DataFrame(
                    {
                        "user_id": self.user_id.gather(u),
                        "gig_id": self.gig_id.gather(upcoming[g]),
                        "status": np.where(going, "GOING", "INTERESTED"),
                        "created_at": _ts(at),
                        "updated_at": _ts(at),
                        "source": pl.repeat("USER", len(u), eager=True),
                    }
                ),
            ]
        )

    # ── social ───────────────────────────────────────────────────────────────────────────────
    def user_follows(self) -> pl.DataFrame:
        """Followers by activity; who is followed by Zipf (s = 0.8): a few accounts hold most
        follows."""
        rng = self.rng
        followee_cdf = d.cdf(d.zipf_weights(self.p.users, 0.8, rng))
        src, dst = d.unique_pairs(self.p.user_follows, self.user_cdf, followee_cdf, rng, True)
        self.follow_src, self.follow_dst = src, dst
        at = self.now - rng.uniform(0, 18 * MONTH, len(src))
        return pl.DataFrame(
            {
                "follower_id": self.user_id.gather(src),
                "followee_id": self.user_id.gather(dst),
                "created_at": _ts(at),
            }
        )

    def artist_follows(self) -> pl.DataFrame:
        """Fans by activity, acts by their (power-law) popularity."""
        rng = self.rng
        u, a = d.unique_pairs(
            self.p.artist_follows, self.user_cdf, d.cdf(self.artist_popularity), rng, False
        )
        at = self.now - rng.uniform(0, 18 * MONTH, len(u))
        return pl.DataFrame(
            {
                "user_id": self.user_id.gather(u),
                "artist_id": self.artist_id.gather(a),
                "created_at": _ts(at),
                "notify": pl.repeat("GIGS", len(u), eager=True),
            }
        )

    def login_pool(self) -> list[str]:
        """The handles k6 signs in as: public users who follow at least five people."""
        out_degree = np.bincount(self.follow_src, minlength=self.p.users)
        eligible = np.flatnonzero((out_degree >= 5) & ~self.user_private)
        chosen = np.sort(self.rng.permutation(eligible)[:LOGIN_POOL])
        return [f"perf_u{i}" for i in chosen.tolist()]

    # ── signals ──────────────────────────────────────────────────────────────────────────────
    def events(self, chunk_rows: int = 500_000) -> Iterator[pl.DataFrame]:
        """The interaction events, day by day in time order (ids ascending with time), in chunks of
        about `chunk_rows`. Never more than one chunk in memory."""
        rng = self.rng
        start, end = d.engagement_window(self.now)
        bounds = np.append(np.arange(start, end, d.DAY), end)
        lengths = np.diff(bounds)
        per_day = d.allocate(self.p.events, lengths / lengths.sum(), self.p.events, rng)
        next_id = 1
        buffered: list[pl.DataFrame] = []
        rows = 0
        for i, n in enumerate(per_day.tolist()):
            if n == 0:
                continue
            u, p, t = d.day_events(
                bounds[i], bounds[i + 1], n, self.user_cdf, self.post_cdf, self.post_published, rng
            )
            kind, value = d.event_types(n, rng)
            surface = d.pick(d.cdf(np.array([0.60, 0.20, 0.08, 0.06, 0.03, 0.02, 0.01])), n, rng)
            feedish = surface < 2
            frame = pl.DataFrame(
                {
                    "id": np.arange(next_id, next_id + n, dtype=np.int64),
                    "occurred_at": _ts(t),
                    "user_id": self.user_id.gather(u),
                    "post_id": self.post_id.gather(p),
                    "surface": np.array(SURFACES)[surface],
                    "position": pl.Series(np.where(feedish, rng.integers(0, 20, n), -1)).replace(
                        -1, None
                    ),
                    "event_type": np.array(d.EVENT_TYPES)[kind],
                    "value": pl.Series(value).fill_nan(None).cast(pl.Float32),
                    "client_ts": _ts(t - rng.uniform(0.0, 2.0, n)),
                    "platform": np.where(rng.random(n) < 0.7, "android", "web"),
                }
            )
            next_id += n
            buffered.append(frame)
            rows += n
            if rows >= chunk_rows:
                yield pl.concat(buffered)
                buffered, rows = [], 0
        if buffered:
            yield pl.concat(buffered)
        self.last_event_id = next_id - 1


SURFACES = ("FOR_YOU", "FOLLOWING", "PROFILE", "GIG", "ARTIST", "POST", "SEARCH")

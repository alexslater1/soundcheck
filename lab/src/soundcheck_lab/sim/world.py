"""The simulator's ground truth, read from the database it simulates against (read-only).

The click model needs each post's acts and their genres, its author and its gig. The API doesn't
expose genre vectors, so this reads them straight from Postgres, in a read-only transaction. Each
post also gets a hidden quality, N(0, 1), seeded by (seed, post id): the same post has the same
quality in every run with the same seed, whatever else is in the database."""

from __future__ import annotations

import uuid
from collections import defaultdict
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import datetime

import numpy as np

from soundcheck_lab.sim.click import Item


@dataclass(frozen=True)
class Post:
    id: str
    author_id: str
    gig_id: str | None
    gig_starts_at: datetime | None
    city: str | None  # the gig's venue town
    artist_ids: tuple[str, ...]
    genres: Mapping[str, float]  # genre name → summed artist_genre weight over the post's acts


@dataclass(frozen=True)
class World:
    posts: Mapping[str, Post]  # by id, in id order
    artist_genres: Mapping[str, Mapping[str, float]]  # acts with published posts → their genres
    city_ids: Mapping[str, int]  # catalog.city id of each town with posts
    seed: int
    _quality: dict[str, float] = field(default_factory=dict, compare=False, repr=False)

    @property
    def genres(self) -> tuple[str, ...]:
        """Every genre the posts' acts have, by name."""
        return tuple(sorted({g for p in self.posts.values() for g in p.genres}))

    @property
    def artists(self) -> tuple[str, ...]:
        return tuple(sorted(self.artist_genres))

    @property
    def authors(self) -> tuple[str, ...]:
        return tuple(sorted({p.author_id for p in self.posts.values()}))

    def genre_posts(self) -> dict[str, int]:
        """How many posts carry each genre."""
        counts: dict[str, int] = defaultdict(int)
        for p in self.posts.values():
            for g in p.genres:
                counts[g] += 1
        return dict(counts)

    def city_posts(self) -> dict[str, int]:
        counts: dict[str, int] = defaultdict(int)
        for p in self.posts.values():
            if p.city is not None and p.city in self.city_ids:
                counts[p.city] += 1
        return dict(counts)

    def quality(self, post_id: str) -> float:
        """The post's hidden quality, N(0, 1), seeded by (seed, post id)."""
        if post_id not in self._quality:
            rng = np.random.default_rng([self.seed, uuid.UUID(post_id).int])
            self._quality[post_id] = float(rng.normal())
        return self._quality[post_id]

    def item(self, post_id: str) -> Item:
        post = self.posts[post_id]
        return Item(
            genres=post.genres,
            artist_ids=post.artist_ids,
            author_id=post.author_id,
            quality=self.quality(post_id),
            has_gig=post.gig_id is not None,
        )


POSTS_SQL = """
select p.id::text, p.author_id::text, p.gig_id::text, g.starts_at, v.city
  from content.post p
  join identity.app_user u on u.id = p.author_id
  left join catalog.gig g on g.id = p.gig_id
  left join catalog.venue v on v.id = g.venue_id
 where p.status = 'PUBLISHED' and u.deleted_at is null and u.profile_visibility = 'PUBLIC'
 order by p.id
"""
ACTS_SQL = """
select pa.post_id::text, pa.artist_id::text
  from content.post_artist pa join content.post p on p.id = pa.post_id
 where p.status = 'PUBLISHED'
 order by 1, 2
"""
GENRES_SQL = """
select ag.artist_id::text, g.name, ag.weight
  from catalog.artist_genre ag join catalog.genre g on g.id = ag.genre_id
 where ag.artist_id in (select pa.artist_id from content.post_artist pa
                          join content.post p on p.id = pa.post_id where p.status = 'PUBLISHED')
 order by 1, 2
"""
CITIES_SQL = """
select distinct on (name) name, id from catalog.city order by name, population desc nulls last, id
"""


def build(
    posts: list[tuple[str, str, str | None, datetime | None, str | None]],
    acts: list[tuple[str, str]],
    genres: list[tuple[str, str, float]],
    cities: list[tuple[str, int]],
    seed: int,
) -> World:
    """The world from the rows of the four queries above (split out so tests need no database).
    Private and deleted authors' posts are left out: For You never serves them to strangers."""
    artist_genres: dict[str, dict[str, float]] = defaultdict(dict)
    for artist_id, genre, weight in genres:
        artist_genres[artist_id][genre] = float(weight)
    post_acts: dict[str, list[str]] = defaultdict(list)
    for post_id, artist_id in acts:
        post_acts[post_id].append(artist_id)
    built: dict[str, Post] = {}
    for post_id, author_id, gig_id, starts_at, city in posts:
        acts_of = tuple(sorted(post_acts.get(post_id, [])))
        vector: dict[str, float] = defaultdict(float)
        for artist_id in acts_of:
            for genre, weight in artist_genres.get(artist_id, {}).items():
                vector[genre] += weight
        built[post_id] = Post(post_id, author_id, gig_id, starts_at, city, acts_of, dict(vector))
    used = {a for p in built.values() for a in p.artist_ids}
    towns = {p.city for p in built.values() if p.city}
    return World(
        posts=dict(sorted(built.items())),
        artist_genres={a: dict(artist_genres.get(a, {})) for a in sorted(used)},
        city_ids={name: city_id for name, city_id in cities if name in towns},
        seed=seed,
    )


def load(conninfo: str, seed: int) -> World:
    """Reads the world from {@code conninfo} in one read-only transaction."""
    import psycopg

    with (
        psycopg.connect(conninfo, options="-c default_transaction_read_only=on") as conn,
        conn.cursor() as cur,
    ):
        cur.execute(POSTS_SQL)
        posts = [(str(r[0]), str(r[1]), r[2], r[3], r[4]) for r in cur.fetchall()]
        cur.execute(ACTS_SQL)
        acts = [(str(r[0]), str(r[1])) for r in cur.fetchall()]
        cur.execute(GENRES_SQL)
        genres = [(str(r[0]), str(r[1]), float(r[2])) for r in cur.fetchall()]
        cur.execute(CITIES_SQL)
        cities = [(str(r[0]), int(r[1])) for r in cur.fetchall()]
    return build(posts, acts, genres, cities, seed)

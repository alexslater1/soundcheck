"""`seed-scale`'s phases, in order:

1. guard and prepare: refuse anything but a local perf database and API; empty the dataset's tables;
   get the shared password hash (from one user registered through the perf API);
2. create the event partitions for every day written;
3. save and drop the non-unique secondary indexes of the filled tables (primary keys and unique
   indexes stay: they enforce rules);
4. COPY the tables in foreign-key order;
5. recreate the indexes;
6. derive the rollups (post_user_event, post_stats, post_stats_hourly, affinities, the global prior)
   from the copied events, in SQL;
7. ANALYZE;
8. a row-count table and the time per phase.
"""

from __future__ import annotations

import time
from collections.abc import Callable, Iterable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from pathlib import Path
from urllib.parse import urlparse

import httpx
import numpy as np
import polars as pl
import psycopg

from soundcheck_lab.scale import distributions as d
from soundcheck_lab.scale.dataset import EMAIL_DOMAIN, Dataset, Reference
from soundcheck_lab.scale.profiles import Profile
from soundcheck_lab.scale.writer import copy_frames

DEFAULT_DSN = "postgresql://soundcheck:soundcheck@127.0.0.1:55500/soundcheck_perf"
DEFAULT_API = "http://localhost:8201"
LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1"}
# The one password every perf user shares: local perf stack only (like seed-demo's LOCAL_PASSWORD).
PERF_PASSWORD = "perf-scale-local-2026"
HASH_SOURCE_HANDLE = "perf_hash_source"

# What seed-scale fills, in foreign-key order. TRUNCATE … CASCADE empties them and whatever hangs
# off them.
TABLES = (
    "identity.app_user",
    "identity.user_genre_preference",
    "catalog.artist",
    "catalog.artist_genre",
    "catalog.venue",
    "catalog.gig",
    "catalog.gig_artist",
    "media.media_asset",
    "content.post",
    "content.post_clip",
    "content.post_artist",
    "catalog.gig_attendance",
    "social.user_follow",
    "social.artist_follow",
    "content.post_like",
    "signals.interaction_event",
)
DERIVED = (
    "signals.post_user_event",
    "signals.post_stats",
    "signals.post_stats_hourly",
    "signals.user_artist_affinity",
    "signals.user_author_affinity",
    "signals.user_genre_affinity",
)
EMPTIED = (
    *TABLES,
    *DERIVED,
    "signals.rollup_watermark",
    "signals.global_prior",
    "feed.served_item",
    "feed.feed_session",
    "shared.rate_limit_bucket",
)


@dataclass(frozen=True)
class Settings:
    profile: Profile
    seed: int
    dsn: str = DEFAULT_DSN
    api_url: str = DEFAULT_API
    now: float | None = None
    login_pool: Path | None = None
    hash_cache: Path | None = None


@dataclass
class Report:
    phases: list[tuple[str, float]] = field(default_factory=list)
    counts: dict[str, int] = field(default_factory=dict)
    database_bytes: int = 0
    now: float = 0.0


def check_local(dsn: str, api_url: str) -> None:
    """Refuses any target but the local perf stack: never the dev database, never AWS."""
    db = urlparse(dsn)
    api = urlparse(api_url)
    if db.hostname not in LOCAL_HOSTS or api.hostname not in LOCAL_HOSTS:
        raise ValueError("seed-scale only writes to a local perf stack (localhost)")
    if not (db.path or "").lstrip("/").startswith("soundcheck_perf"):
        raise ValueError("seed-scale only writes to a database named soundcheck_perf…")


def run(settings: Settings, echo: Callable[[str], None]) -> Report:
    check_local(settings.dsn, settings.api_url)
    now = settings.now if settings.now is not None else float(np.floor(time.time() / 3600) * 3600)
    report = Report(now=now)

    @contextmanager
    def phase(name: str) -> Iterator[None]:
        echo(f"… {name}")
        start = time.perf_counter()
        yield
        took = time.perf_counter() - start
        report.phases.append((name, took))
        echo(f"  {name}: {took:.1f} s")

    with psycopg.connect(settings.dsn) as conn:
        conn.execute("set synchronous_commit = off")
        conn.execute("set timezone = 'UTC'")
        conn.execute("set maintenance_work_mem = '512MB'")
        conn.commit()

        with phase("prepare"):
            conn.execute("truncate " + ", ".join(EMPTIED) + " cascade")
            conn.commit()
            password_hash = shared_hash(conn, settings, echo)
            conn.execute("truncate identity.app_user cascade")
            conn.commit()
            ref = reference(conn)

        with phase("partitions"):
            for table in ("signals.interaction_event", "feed.served_item"):
                create_partitions(conn, table, d.partition_days(now))

        with phase("drop indexes"):
            indexes = drop_secondary_indexes(conn, TABLES)
            echo(f"  dropped {len(indexes)} indexes")

        data = Dataset(settings.profile, settings.seed, now, ref, password_hash)
        with phase("copy"):
            for table, frames in steps(data):
                started = time.perf_counter()
                rows = copy_frames(conn, table, frames())
                echo(f"  {table:<34} {rows:>11,} rows  {time.perf_counter() - started:6.1f} s")
            conn.execute(
                "select setval('signals.interaction_event_id_seq', %s)",
                (max(data.last_event_id, 1),),
            )
            conn.commit()

        with phase("create indexes"):
            for definition in indexes:
                conn.execute(definition)
                conn.commit()

        with phase("rollups"):
            derive_rollups(conn, now)

        with phase("analyze"):
            conn.autocommit = True
            conn.execute("vacuum analyze")
            conn.autocommit = False

        report.counts = row_counts(conn)
        row = conn.execute("select pg_database_size(current_database())").fetchone()
        report.database_bytes = int(row[0]) if row else 0

    if settings.login_pool is not None:
        settings.login_pool.parent.mkdir(parents=True, exist_ok=True)
        settings.login_pool.write_text("\n".join(data.login_pool()) + "\n")
    return report


def steps(data: Dataset) -> list[tuple[str, Callable[[], Iterable[pl.DataFrame]]]]:
    """(table, frames) in foreign-key order. The posts, clips and likes are planned together before
    the media is written, so each post's like_count is known when the posts are written."""

    def one(build: Callable[[], pl.DataFrame]) -> Callable[[], list[pl.DataFrame]]:
        return lambda: [build()]

    def media() -> list[pl.DataFrame]:
        data.plan_content()
        return [data.media()]

    return [
        ("identity.app_user", one(data.users)),
        ("identity.user_genre_preference", one(data.genre_preferences)),
        ("catalog.artist", one(data.artists)),
        ("catalog.artist_genre", one(data.artist_genres)),
        ("catalog.venue", one(data.venues)),
        ("catalog.gig", one(data.gigs)),
        ("catalog.gig_artist", one(data.gig_artists)),
        ("media.media_asset", media),
        ("content.post", one(data.posts)),
        ("content.post_clip", one(data.clips)),
        ("content.post_artist", one(data.post_artists)),
        ("catalog.gig_attendance", one(data.attendance)),
        ("social.user_follow", one(data.user_follows)),
        ("social.artist_follow", one(data.artist_follows)),
        ("content.post_like", one(data.likes)),
        ("signals.interaction_event", data.events),
    ]


def shared_hash(conn: psycopg.Connection, settings: Settings, echo: Callable[[str], None]) -> str:
    """The Argon2id hash every perf user shares: one user registered through the perf API (so the
    lab
    needs no Argon2 library), remembered in `hash_cache` for runs without the API."""
    email = f"{HASH_SOURCE_HANDLE}@{EMAIL_DOMAIN}"
    try:
        response = httpx.post(
            f"{settings.api_url}/api/v1/auth/register",
            json={
                "email": email,
                "password": PERF_PASSWORD,
                "handle": HASH_SOURCE_HANDLE,
                "displayName": "Perf hash source",
            },
            timeout=30.0,
        )
        response.raise_for_status()
        row = conn.execute(
            "select password_hash from identity.app_user where email = %s", (email,)
        ).fetchone()
        if row is None:
            raise RuntimeError("the registered user is not in the perf database")
        hashed = str(row[0])
        if settings.hash_cache is not None:
            settings.hash_cache.parent.mkdir(parents=True, exist_ok=True)
            settings.hash_cache.write_text(hashed)
        return hashed
    except httpx.HTTPError as e:
        if settings.hash_cache is not None and settings.hash_cache.exists():
            echo(f"  perf API not reachable ({e.__class__.__name__}): using the cached hash")
            return settings.hash_cache.read_text().strip()
        raise RuntimeError(
            f"register a user through the perf API ({settings.api_url}) first: run make perf-api"
        ) from e


def reference(conn: psycopg.Connection) -> Reference:
    cities = conn.execute(
        "select id, name, st_y(location::geometry), st_x(location::geometry),"
        " coalesce(population, 15000) from catalog.city order by id"
    ).fetchall()
    if not cities:
        raise RuntimeError(
            "catalog.city is empty: start the perf API once (make perf-api) to load it"
        )
    genres = conn.execute("select id from catalog.genre order by id").fetchall()
    return Reference(
        city_id=np.array([c[0] for c in cities], dtype=np.int64),
        city_name=[str(c[1]) for c in cities],
        city_lat=np.array([c[2] for c in cities], dtype=np.float64),
        city_lng=np.array([c[3] for c in cities], dtype=np.float64),
        city_population=np.array([c[4] for c in cities], dtype=np.float64),
        genre_ids=np.array([g[0] for g in genres], dtype=np.int64),
    )


def partition_name(table: str, day: object) -> str:
    """As the API names them (shared/partitions/DailyPartitions.java):
    interaction_event_p20261002."""
    return f"{table.split('.')[1]}_p{str(day).replace('-', '')}"


def create_partitions(conn: psycopg.Connection, table: str, days: list[date]) -> None:
    schema = table.split(".")[0]
    for day in days:
        name = partition_name(table, day)
        nxt = np.datetime64(str(day)) + np.timedelta64(1, "D")
        conn.execute(
            f"create table if not exists {schema}.{name} partition of {table}"
            f" for values from ('{day} 00:00:00+00') to ('{nxt} 00:00:00+00')"
        )
    conn.commit()


def drop_secondary_indexes(conn: psycopg.Connection, tables: tuple[str, ...]) -> list[str]:
    """Saves the definition of every non-unique, non-primary index on the tables, then drops it.
    A partitioned table's index is defined `ON ONLY` the parent; recreating it without ONLY builds
    it
    on every partition again."""
    rows = conn.execute(
        """
        select format('%%I.%%I', n.nspname, ic.relname), pg_get_indexdef(i.indexrelid)
          from pg_index i
          join pg_class ic on ic.oid = i.indexrelid
          join pg_class t on t.oid = i.indrelid
          join pg_namespace n on n.oid = t.relnamespace
         where format('%%s.%%s', n.nspname, t.relname) = any(%s)
           and not i.indisunique and not i.indisprimary
         order by 1
        """,
        (list(tables),),
    ).fetchall()
    definitions = []
    for name, definition in rows:
        definitions.append(str(definition).replace(" ON ONLY ", " ON "))
        conn.execute(f"drop index {name}")
    conn.commit()
    return definitions


# The rollups, as the scheduler's jobs would have left them after folding in every event:
# PostStatsRollup counts each (post, user, kind) once, at its first event; AffinityRollup moves
# scores
# by AffinityMath's deltas (at most ±0.3 per post), halving every 14 days. One pass instead of
# 50,000-event batches, so a score is clamped once at the end rather than after every batch.
ROLLUP_SQL = """
insert into signals.post_user_event (post_id, user_id, kind, active, first_at)
select post_id, user_id, kind, true, min(occurred_at)
  from (select post_id, user_id, occurred_at,
               case when event_type = 'WATCH_PROGRESS' then
                      case when value >= 0.75 then 'WATCH_75' when value >= 0.5 then 'WATCH_50'
                           when value >= 0.25 then 'WATCH_25' end
                    else event_type end as kind
          from signals.interaction_event
         where event_type in ('IMPRESSION', 'VIEW_START', 'WATCH_PROGRESS', 'COMPLETE', 'LIKE',
                              'COMMENT', 'SHARE', 'HIDE')) e
 where kind is not null
 group by post_id, user_id, kind;

insert into signals.post_stats (post_id, impressions, views, completes, likes, comments, shares,
hides,
                                watch_fraction_sum, updated_at)
select post_id,
       count(*) filter (where kind = 'IMPRESSION'),
       count(*) filter (where kind = 'VIEW_START'),
       count(*) filter (where kind = 'COMPLETE'),
       count(*) filter (where kind = 'LIKE'),
       count(*) filter (where kind = 'COMMENT'),
       count(*) filter (where kind = 'SHARE'),
       count(*) filter (where kind = 'HIDE'),
       0.25 * count(*) filter (where kind in ('WATCH_25', 'WATCH_50', 'WATCH_75', 'COMPLETE')),
       %(now)s
  from signals.post_user_event
 group by post_id;

-- GlobalPriorRefresh.forgetOldHours keeps 14 days of hourly counters.
insert into signals.post_stats_hourly (post_id, hour, impressions, engagements)
select post_id, date_trunc('hour', first_at),
       count(*) filter (where kind = 'IMPRESSION'),
       count(*) filter (where kind in ('LIKE', 'COMMENT', 'SHARE', 'COMPLETE'))
  from signals.post_user_event
 where first_at >= %(now)s - interval '14 days'
 group by 1, 2;

create temporary table user_post_delta on commit drop as
select user_id, post_id,
       greatest(-0.30, least(0.30, sum(delta * n)))
         * power(0.5, extract(epoch from (%(now)s - max(last_at))) / (14 * 86400)) as delta
  from (select user_id, post_id, count(*) as n, max(occurred_at) as last_at,
               case event_type when 'LIKE' then 0.10 when 'UNLIKE' then -0.10 when 'COMMENT' then
               0.12
                               when 'SHARE' then 0.15 when 'COMPLETE' then 0.05
                               when 'WATCH_PROGRESS' then 0.03 when 'PROFILE_TAP' then 0.04
                               when 'GIG_TAP' then 0.04 when 'HIDE' then -0.20 end as delta
          from signals.interaction_event
         where event_type in ('LIKE', 'UNLIKE', 'COMMENT', 'SHARE', 'COMPLETE', 'PROFILE_TAP',
                              'GIG_TAP', 'HIDE')
            or (event_type = 'WATCH_PROGRESS' and value >= 0.75)
         group by user_id, post_id, event_type) per_type
 group by user_id, post_id;

insert into signals.user_author_affinity (user_id, author_id, score, updated_at)
select x.user_id, p.author_id, least(1, sum(x.delta)), %(now)s
  from user_post_delta x join content.post p on p.id = x.post_id
 where p.author_id <> x.user_id
 group by 1, 2 having sum(x.delta) > 0;

insert into signals.user_artist_affinity (user_id, artist_id, score, updated_at)
select x.user_id, pa.artist_id, least(1, sum(x.delta)), %(now)s
  from user_post_delta x join content.post_artist pa on pa.post_id = x.post_id
 group by 1, 2 having sum(x.delta) > 0;

insert into signals.user_genre_affinity (user_id, genre_id, score, updated_at)
select x.user_id, ag.genre_id, least(1, sum(x.delta * ag.weight)), %(now)s
  from user_post_delta x
  join content.post_artist pa on pa.post_id = x.post_id
  join catalog.artist_genre ag on ag.artist_id = pa.artist_id
 group by 1, 2 having sum(x.delta * ag.weight) > 0;

-- Both rollups have folded in every event: their watermark is the last one, in key order.
insert into signals.rollup_watermark (name, last_event_id, last_occurred_at)
select w.name, e.id, e.occurred_at
  from (values ('post_stats'), ('affinity')) w(name),
       (select id, occurred_at from signals.interaction_event
         order by occurred_at desc, id desc limit 1) e;

-- GlobalPriorRefresh: the mean engagement rate and the 99th percentile of the smoothed rate.
with s as (select (likes + 2 * comments + shares + completes)::float8 as e, impressions::float8 as i
             from signals.post_stats where impressions > 0),
     m as (select coalesce(sum(e) / nullif(sum(i), 0), 0) as mean, count(*) as posts from s)
insert into signals.global_prior (id, mean_rate, p99_rate, posts, updated_at)
select true, m.mean,
       coalesce((select percentile_cont(0.99) within group (order by (s.e + 20 * m.mean) / (s.i +
       20))
                   from s), 0),
       m.posts, %(now)s
  from m;
"""


def derive_rollups(conn: psycopg.Connection, now: float) -> None:
    at = datetime.fromtimestamp(now, UTC)
    with conn.cursor() as cur:
        for statement in [s for s in ROLLUP_SQL.split(";\n") if s.strip()]:
            cur.execute(statement, {"now": at})
    conn.commit()


def row_counts(conn: psycopg.Connection) -> dict[str, int]:
    counts: dict[str, int] = {}
    for table in (*TABLES, *DERIVED):
        row = conn.execute(f"select count(*) from {table}").fetchone()
        counts[table] = int(row[0]) if row else 0
    return counts


# The profile's names for the tables it counts.
SPEC_COUNTS = {
    "identity.app_user": "users",
    "catalog.artist": "artists",
    "catalog.venue": "venues",
    "catalog.gig": "gigs",
    "content.post": "posts",
    "social.user_follow": "user_follows",
    "social.artist_follow": "artist_follows",
    "content.post_like": "likes",
    "signals.interaction_event": "events",
}


def table_report(profile: Profile, report: Report) -> str:
    lines = [f"{'table':<34} {'rows':>12} {'profile':>12} {'diff':>7}"]
    for table, rows in report.counts.items():
        key = SPEC_COUNTS.get(table)
        if key is None:
            lines.append(f"{table:<34} {rows:>12,}")
            continue
        want = int(getattr(profile, key))
        diff = (rows - want) / want * 100
        lines.append(f"{table:<34} {rows:>12,} {want:>12,} {diff:>6.2f}%")
    lines.append("")
    lines.append(f"{'phase':<20} {'seconds':>8}")
    for name, took in report.phases:
        lines.append(f"{name:<20} {took:>8.1f}")
    lines.append(f"{'total':<20} {sum(t for _, t in report.phases):>8.1f}")
    lines.append(f"database size: {report.database_bytes / 1e9:.2f} GB")
    return "\n".join(lines)

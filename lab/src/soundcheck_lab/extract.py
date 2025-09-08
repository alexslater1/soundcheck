"""`soundcheck-lab extract`: the training table from what was served.

The query is ``contracts/ranking/extract.sql``, which the Java ``ExtractQueryIT`` also runs
against hand-made served items and events, so there is one definition of the label and
it is tested where the database fixtures live. This module only shapes the result:

- refuses rows whose feature schema version isn't the contract's (and says how many);
- splits the ``features`` array into one column per feature, named from the schema;
- with a simulator file, keeps the run's own feed sessions and gives each its round (for the time
  split);
- writes ``lab/data/extract-<ts>.parquet`` and a JSON sidecar with the counts.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import polars as pl

from soundcheck_lab.schema import DEFAULT_SCHEMA_PATH, FeatureSchema

EXTRACT_SQL_PATH = DEFAULT_SCHEMA_PATH.with_name("extract.sql")

# Synthetic accounts by email domain (`soundcheck.admin.synthetic-email-domains`).
SYNTHETIC_DOMAINS = ("seed.soundcheck.test", "sim.soundcheck.test")

ID_COLUMNS = ("session_id", "user_id", "post_id", "config_id")


class ExtractError(Exception):
    pass


def load_sql(path: Path = EXTRACT_SQL_PATH) -> str:
    return path.read_text(encoding="utf-8").strip().rstrip(";")


def windowed_sql(sql: str) -> str:
    """The shared query with a window. The filter on ``served_at`` reaches the partition key."""
    return (
        f"select * from (\n{sql}\n) x where served_at >= %(since)s and served_at < %(until)s"
        " order by served_at, session_id, position"
    )


def parse_since(text: str, now: datetime) -> datetime:
    """``14d``, ``36h`` or an ISO date/time (UTC when it has no zone)."""
    match = re.fullmatch(r"(\d+)([dh])", text.strip())
    if match:
        amount, unit = int(match.group(1)), match.group(2)
        return now - (timedelta(days=amount) if unit == "d" else timedelta(hours=amount))
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError as e:
        raise ExtractError(f"--since: expected 14d, 36h or an ISO date, got {text!r}") from e
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


@dataclass(frozen=True, slots=True)
class Shaped:
    frame: pl.DataFrame
    refused: int  # rows whose feature schema version (or feature count) isn't the contract's


def shape(raw: pl.DataFrame, schema: FeatureSchema) -> Shaped:
    """Refuses rows of another feature schema version and splits ``features`` into named columns."""
    ok = (pl.col("feature_schema_version") == schema.version) & (
        pl.col("features").list.len() == len(schema)
    )
    kept = raw.filter(ok)
    refused = raw.height - kept.height
    columns = [
        pl.col("features").list.get(f.index).cast(pl.Float64).alias(f.name) for f in schema.features
    ]
    frame = kept.with_columns(columns).drop("features")
    return Shaped(frame, refused)


@dataclass(frozen=True, slots=True)
class SimRun:
    path: Path
    rounds: dict[str, int]  # feed session id → round
    total_rounds: int
    started_at: str | None


def load_sim(path: Path) -> SimRun:
    raw: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    rounds: dict[str, int] = {}
    for session in raw["sessions"]:
        for feed_session in session["feedSessions"]:
            rounds[str(feed_session)] = int(session["round"])
    return SimRun(path, rounds, int(raw["parameters"]["rounds"]), raw.get("startedAt"))


def attach_rounds(frame: pl.DataFrame, sim: SimRun) -> tuple[pl.DataFrame, int]:
    """Keeps the simulator run's own feed sessions and adds each one's ``round``; returns the
    frame and how many rows outside the run were left out."""
    lookup = pl.DataFrame(
        {"session_id": list(sim.rounds), "round": list(sim.rounds.values())},
        schema={"session_id": pl.String, "round": pl.Int32},
    )
    joined = frame.join(lookup, on="session_id", how="inner")
    return joined, frame.height - joined.height


def sidecar(
    frame: pl.DataFrame,
    *,
    since: datetime,
    until: datetime,
    refused: int,
    synthetic_users: set[str],
    sim: SimRun | None,
    outside_sim: int,
    commit: str | None,
) -> dict[str, Any]:
    rows = frame.height
    synthetic_rows = frame.filter(pl.col("user_id").is_in(list(synthetic_users))).height
    sessions = frame["session_id"].n_unique() if rows else 0
    positives = int(frame["positive"].sum()) if rows else 0
    by_config = frame.group_by("config_version").len().sort("config_version").rows() if rows else []
    out: dict[str, Any] = {
        "window": {"since": since.isoformat(), "until": until.isoformat()},
        "commit": commit,
        "rows": rows,
        "sessions": sessions,
        "users": frame["user_id"].n_unique() if rows else 0,
        "positives": positives,
        "positiveRate": positives / rows if rows else None,
        "negatives": int(frame["negative"].sum()) if rows else 0,
        "explorationRows": int(frame["exploration"].sum()) if rows else 0,
        "syntheticShare": synthetic_rows / rows if rows else None,
        "refusedRows": refused,
        "rowsByConfigVersion": {str(v): n for v, n in by_config},
        "sim": None,
    }
    if sim is not None:
        kept = set(frame["session_id"].unique().to_list()) if rows else set()
        out["sim"] = {
            "file": str(sim.path),
            "startedAt": sim.started_at,
            "rounds": sim.total_rounds,
            "rowsOutsideRun": outside_sim,
            "sessionRounds": {s: r for s, r in sorted(sim.rounds.items()) if s in kept},
        }
    return out


def write(frame: pl.DataFrame, meta: dict[str, Any], out_dir: Path, stamp: str) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"extract-{stamp}.parquet"
    frame.write_parquet(path)
    path.with_suffix(".json").write_text(json.dumps(meta, indent=1) + "\n", encoding="utf-8")
    return path


# ── the database side (not unit-tested: lab-check runs offline; ExtractQueryIT tests the SQL) ─────
RAW_SCHEMA: dict[str, Any] = {
    "session_id": pl.String,
    "position": pl.Int32,
    "served_at": pl.Datetime("us", "UTC"),
    "user_id": pl.String,
    "post_id": pl.String,
    "config_id": pl.String,
    "config_version": pl.Int32,
    "feature_schema_version": pl.Int32,
    "score": pl.Float64,
    "exploration": pl.Boolean,
    "positive": pl.Boolean,
    "negative": pl.Boolean,
    "max_watch_fraction": pl.Float64,
    "features": pl.List(pl.Float64),
}


def fetch(db_url: str, since: datetime, until: datetime) -> pl.DataFrame:
    """Runs the shared query read-only; the frame's types are fixed (no inference from the first
    rows, where ``max_watch_fraction`` is often null)."""
    import psycopg

    with psycopg.connect(db_url, autocommit=True) as conn:
        conn.execute("set default_transaction_read_only = on")
        cursor = conn.execute(
            windowed_sql(load_sql()),
            {"since": since, "until": until},
        )
        columns = [d.name for d in cursor.description or []]
        if columns != list(RAW_SCHEMA):
            raise ExtractError(f"extract.sql returned {columns}, expected {list(RAW_SCHEMA)}")
        return pl.DataFrame(cursor.fetchall(), schema=RAW_SCHEMA, orient="row")


def fetch_synthetic_users(db_url: str) -> set[str]:
    import psycopg

    with psycopg.connect(db_url, autocommit=True) as conn:
        rows = conn.execute(
            "select id::text from identity.app_user where split_part(email, '@', 2) = any(%s)",
            (list(SYNTHETIC_DOMAINS),),
        ).fetchall()
    return {str(r[0]) for r in rows}

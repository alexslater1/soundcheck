"""`extract` without a database: the shaping, the schema-version refusal and the sidecar.

The SQL itself (contracts/ranking/extract.sql) is tested by the Java ExtractQueryIT."""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import polars as pl
import pytest

from soundcheck_lab import extract as ex
from soundcheck_lab.schema import load_schema

SCHEMA = load_schema()
NOW = datetime(2026, 10, 5, 1, 0, tzinfo=UTC)


def raw_frame(rows: list[dict[str, Any]]) -> pl.DataFrame:
    """Rows as the query returns them; anything left out takes a default."""
    full = []
    for i, row in enumerate(rows):
        base: dict[str, Any] = {
            "session_id": f"s{i // 3}",
            "position": i % 3,
            "served_at": NOW,
            "user_id": "u1",
            "post_id": f"p{i}",
            "config_id": "c1",
            "config_version": 1,
            "feature_schema_version": 1,
            "score": 0.5,
            "exploration": False,
            "positive": False,
            "negative": False,
            "max_watch_fraction": None,
            "features": [float(j) / 10 for j in range(12)],
        }
        base.update(row)
        full.append(base)
    return pl.DataFrame(full, schema=ex.RAW_SCHEMA, orient="row")


def test_the_features_become_columns_named_from_the_schema() -> None:
    shaped = ex.shape(raw_frame([{}]), SCHEMA)
    assert shaped.refused == 0
    assert "features" not in shaped.frame.columns
    for feature in SCHEMA.features:
        assert shaped.frame[feature.name][0] == pytest.approx(feature.index / 10)


def test_rows_of_another_schema_version_are_refused_and_counted() -> None:
    raw = raw_frame([{}, {"feature_schema_version": 2}, {"features": [0.0] * 11}])
    shaped = ex.shape(raw, SCHEMA)
    assert shaped.refused == 2
    assert shaped.frame.height == 1


def test_the_window_wraps_the_shared_query() -> None:
    sql = ex.windowed_sql(ex.load_sql())
    assert "from feed.served_outcome o" in sql
    assert "served_at >= %(since)s" in sql
    assert not ex.load_sql().endswith(";")


def test_the_query_file_returns_the_columns_the_frame_expects() -> None:
    text = ex.load_sql()
    for column in ex.RAW_SCHEMA:
        assert column in text


def test_since_takes_days_hours_or_a_date() -> None:
    assert ex.parse_since("14d", NOW) == NOW - timedelta(days=14)
    assert ex.parse_since("36h", NOW) == NOW - timedelta(hours=36)
    assert ex.parse_since("2026-10-01", NOW) == datetime(2026, 10, 1, tzinfo=UTC)
    with pytest.raises(ex.ExtractError):
        ex.parse_since("last week", NOW)


def sim_file(tmp_path: Path) -> Path:
    path = tmp_path / "sim-7-x.json"
    path.write_text(
        json.dumps(
            {
                "startedAt": "2026-10-05T00:43:00Z",
                "parameters": {"rounds": 2},
                "sessions": [
                    {"round": 0, "feedSessions": ["s0"]},
                    {"round": 1, "feedSessions": ["s1", "s9"]},
                ],
            }
        ),
        encoding="utf-8",
    )
    return path


def test_a_simulator_run_gives_each_session_its_round_and_drops_the_rest(tmp_path: Path) -> None:
    shaped = ex.shape(raw_frame([{}] * 9), SCHEMA)  # sessions s0, s1, s2
    sim = ex.load_sim(sim_file(tmp_path))
    frame, outside = ex.attach_rounds(shaped.frame, sim)
    assert outside == 3  # s2 isn't the run's
    assert dict(frame.group_by("session_id").agg(pl.col("round").first()).rows()) == {
        "s0": 0,
        "s1": 1,
    }


def test_the_sidecar_counts_rows_sessions_positives_and_the_synthetic_share(
    tmp_path: Path,
) -> None:
    raw = raw_frame(
        [
            {"positive": True},
            {"user_id": "alex"},
            {},
            {"positive": True, "exploration": True},
            {"negative": True},
            {},
        ]
    )
    shaped = ex.shape(raw, SCHEMA)
    sim = ex.load_sim(sim_file(tmp_path))
    frame, outside = ex.attach_rounds(shaped.frame, sim)
    meta = ex.sidecar(
        frame,
        since=NOW - timedelta(days=1),
        until=NOW,
        refused=shaped.refused,
        synthetic_users={"u1"},
        sim=sim,
        outside_sim=outside,
        commit="abc1234",
    )
    assert meta["rows"] == 6
    assert meta["sessions"] == 2
    assert meta["positives"] == 2
    assert meta["negatives"] == 1
    assert meta["explorationRows"] == 1
    assert meta["syntheticShare"] == pytest.approx(5 / 6)
    assert meta["sim"]["sessionRounds"] == {"s0": 0, "s1": 1}
    assert meta["rowsByConfigVersion"] == {"1": 6}

    path = ex.write(frame, meta, tmp_path, "20261005T010000Z")
    assert pl.read_parquet(path).height == 6
    assert json.loads(path.with_suffix(".json").read_text(encoding="utf-8"))["rows"] == 6

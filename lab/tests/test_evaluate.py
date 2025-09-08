"""`evaluate` on a hand-made test set: per-session NDCG, the exploration slice, the
paired bootstrap and the verdict's wording."""

from __future__ import annotations

import polars as pl
import pytest

from soundcheck_lab import evaluate as ev


def held_out() -> pl.DataFrame:
    # Two sessions of three items; s2's last item came from exploration.
    return pl.DataFrame(
        {
            "session_id": ["s1", "s1", "s1", "s2", "s2", "s2"],
            "position": [0, 1, 2, 0, 1, 2],
            "exploration": [False, False, True, False, False, True],
            "positive": [False, True, False, False, False, True],
        }
    )


def test_ndcg_is_per_session_and_follows_the_scores() -> None:
    f = held_out()
    perfect = ev.evaluate_one(f, ev.Scored("perfect", "config", [0.1, 0.9, 0.2, 0.1, 0.2, 0.9]))
    assert perfect.ndcg10 == pytest.approx(1.0)
    assert perfect.ndcg_sessions == 2
    assert perfect.auc == pytest.approx(1.0)
    shown = ev.evaluate_one(
        f, ev.Scored("as served", "reference", [-0.0, -1.0, -2.0] * 2, probabilities=False)
    )
    assert shown.log_loss is None
    assert shown.ndcg_by_session == pytest.approx([1 / 1.584962500721156, 0.5])
    assert shown.engagement[5] == pytest.approx(1 / 3)


def test_the_exploration_slice_uses_only_exploration_rows() -> None:
    result = ev.evaluate_one(held_out(), ev.Scored("x", "config", [0.1, 0.9, 0.2, 0.1, 0.2, 0.9]))
    assert result.exploration["rows"] == 2
    assert result.exploration["positives"] == 1
    assert result.exploration["ndcgSessions"] == 1  # s1's exploration item wasn't positive
    assert "_bySession" not in result.as_json()["explorationOnly"]


def test_the_paired_bootstrap_brackets_the_mean_difference() -> None:
    diff, lo, hi = ev.paired_bootstrap([0.6, 0.7, 0.8, 0.9], [0.5, 0.5, 0.5, 0.5]) or (0, 0, 0)
    assert diff == pytest.approx(0.25)
    assert 0 < lo <= diff <= hi
    assert ev.paired_bootstrap([0.5], [0.4]) is None
    with pytest.raises(ValueError):
        ev.paired_bootstrap([0.5, 0.6], [0.4])


def comparison(diff: float, lo: float, hi: float) -> dict[str, object]:
    return {
        "candidate": "Learned",
        "baseline": "Active v1",
        "all": {"diff": diff, "ci95": [lo, hi], "sessions": 200},
        "explorationOnly": None,
    }


def test_the_verdict_needs_the_whole_interval_to_call_a_win_or_a_loss() -> None:
    assert ev.verdict(comparison(0.07, 0.05, 0.09)).startswith("The learned config beats")
    assert "loses to" in ev.verdict(comparison(-0.07, -0.09, -0.05))
    assert "does not measurably differ" in ev.verdict(comparison(0.01, -0.02, 0.04))
    assert "no verdict" in ev.verdict({"baseline": "Active v1", "all": None})


def test_the_report_opens_with_the_simulated_data_caveat() -> None:
    f = held_out()
    results = [
        ev.evaluate_one(f, ev.Scored("Active v1", "config", [0.5] * 6)),
        ev.evaluate_one(f, ev.Scored("Learned", "config", [0.1, 0.9, 0.2, 0.1, 0.2, 0.9])),
    ]
    md = ev.report_markdown(
        results,
        split={
            "by": "round",
            "testFrom": 11,
            "units": 14,
            "testShare": 0.2,
            "trainRows": 10,
            "trainSessions": 2,
            "testRows": 6,
            "testSessions": 2,
        },
        data={"path": "x.parquet", "rows": 16},
        models={"Active v1": {"bias": -2.0}, "Learned": {"bias": -3.0}},
        verdict_line="v",
        why=["because"],
        commit="abc1234",
        images=[],
    )
    assert md.splitlines()[2] == ev.CAVEAT
    assert "**simulated**" in md
    assert "| Learned | 6 | 2 |" in md

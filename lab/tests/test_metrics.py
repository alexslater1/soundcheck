"""The Python ranking metrics against contracts/ranking/metric-cases.json.

Java asserts the same file in RankingMetricsTest, so the lab's evaluate and the console's replay
give the same numbers for the same inputs."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from soundcheck_lab import metrics
from soundcheck_lab.schema import DEFAULT_SCHEMA_PATH

CASES_PATH = DEFAULT_SCHEMA_PATH.with_name("metric-cases.json")


def _cases() -> dict[str, Any]:
    raw: dict[str, Any] = json.loads(Path(CASES_PATH).read_text(encoding="utf-8"))
    return raw


CASES = _cases()
TOLERANCE = float(CASES["tolerance"])


def _ids(section: str) -> list[str]:
    return [str(c["name"]) for c in CASES[section]]


def _check(actual: float | None, expected: float | None) -> None:
    if expected is None:
        assert actual is None
    else:
        assert actual is not None
        assert abs(actual - expected) < TOLERANCE


def test_every_metric_in_the_file_is_covered() -> None:
    sections = {k for k, v in CASES.items() if isinstance(v, list)}
    assert sections == {"overlapAt", "kendallTauB", "auc", "logLoss", "ndcgAt"}


@pytest.mark.parametrize("case", CASES["overlapAt"], ids=_ids("overlapAt"))
def test_overlap_at(case: dict[str, Any]) -> None:
    _check(metrics.overlap_at(case["k"], case["a"], case["b"]), case["expected"])


@pytest.mark.parametrize("case", CASES["kendallTauB"], ids=_ids("kendallTauB"))
def test_kendall_tau_b(case: dict[str, Any]) -> None:
    x = [float(v) for v in case["x"]]
    y = [float(v) for v in case["y"]]
    _check(metrics.kendall_tau_b(x, y), case["expected"])


@pytest.mark.parametrize("case", CASES["auc"], ids=_ids("auc"))
def test_auc(case: dict[str, Any]) -> None:
    _check(metrics.auc(case["scores"], case["positive"]), case["expected"])


@pytest.mark.parametrize("case", CASES["logLoss"], ids=_ids("logLoss"))
def test_log_loss(case: dict[str, Any]) -> None:
    _check(metrics.log_loss(case["probabilities"], case["positive"]), case["expected"])


@pytest.mark.parametrize("case", CASES["ndcgAt"], ids=_ids("ndcgAt"))
def test_ndcg_at(case: dict[str, Any]) -> None:
    _check(metrics.ndcg_at(case["k"], case["scores"], case["positive"]), case["expected"])


def test_kendall_tau_b_refuses_lists_of_different_length() -> None:
    with pytest.raises(ValueError):
        metrics.kendall_tau_b([1.0, 2.0], [1.0])


def test_engagement_at_counts_the_top_k_by_score() -> None:
    assert metrics.engagement_at(2, [0.1, 0.9, 0.5], [True, True, False]) == 0.5
    assert metrics.engagement_at(10, [0.1, 0.9], [True, True]) == 1.0
    assert metrics.engagement_at(3, [], []) is None


def test_calibration_bins_hold_mean_prediction_and_observed_rate() -> None:
    bins = metrics.calibration([0.05, 0.05, 0.95, 1.0], [False, True, True, True], bins=10)
    assert len(bins) == 10
    assert bins[0].count == 2
    assert bins[0].mean_predicted == pytest.approx(0.05)
    assert bins[0].observed_rate == 0.5
    assert bins[9].count == 2  # 1.0 falls in the last bin
    assert bins[9].observed_rate == 1.0
    assert bins[5].count == 0
    assert bins[5].mean_predicted is None

"""`train`: the position prior, the time split and the export, on synthetic frames."""

from __future__ import annotations

import math
from datetime import UTC, datetime, timedelta

import numpy as np
import polars as pl
import pytest

from soundcheck_lab import scorer
from soundcheck_lab import train as tr
from soundcheck_lab.schema import load_schema

SCHEMA = load_schema()
T0 = datetime(2026, 10, 5, tzinfo=UTC)


def frame(rounds: int, sessions_per_round: int, items: int, seed: int = 1) -> pl.DataFrame:
    """A training table whose positives follow ``artist_affinity`` (and position)."""
    rng = np.random.default_rng(seed)
    rows = []
    for r in range(rounds):
        for s in range(sessions_per_round):
            session = f"r{r}s{s}"
            for position in range(items):
                features = {f.name: float(rng.random()) for f in SCHEMA.features}
                features["position_prior"] = 0.0  # logged as 0, as the feed does
                logit = -2.0 + 3.0 * features["artist_affinity"] + 1.0 / math.log2(2 + position)
                rows.append(
                    {
                        "session_id": session,
                        "position": position,
                        "served_at": T0 + timedelta(hours=r, minutes=s),
                        "round": r,
                        "exploration": position % 10 == 9,
                        "positive": bool(rng.random() < 1 / (1 + math.exp(-logit))),
                        **features,
                    }
                )
    return pl.DataFrame(rows)


def test_the_position_prior_is_one_over_log2_of_two_plus_position() -> None:
    assert tr.position_prior(0) == 1.0
    assert tr.position_prior(2) == 0.5
    assert tr.position_prior(6) == pytest.approx(1 / 3)
    positions = pl.DataFrame({"position": [0, 1, 2, 14]})
    computed = positions.select(tr.prior_expr())[tr.PRIOR].to_list()
    assert computed == pytest.approx([tr.position_prior(p) for p in (0, 1, 2, 14)])


def test_design_puts_the_prior_in_training_and_zero_otherwise() -> None:
    f = frame(1, 1, 3)
    prior_column = SCHEMA.index_of("position_prior")
    with_prior = tr.design(f, SCHEMA, prior=True)
    without = tr.design(f, SCHEMA, prior=False)
    assert with_prior[:, prior_column].tolist() == pytest.approx([1.0, 1 / math.log2(3), 0.5])
    assert without[:, prior_column].tolist() == [0.0, 0.0, 0.0]
    assert with_prior.shape == (3, len(SCHEMA))


def test_the_time_split_takes_the_last_fifth_of_rounds_and_never_splits_one() -> None:
    f = frame(rounds=10, sessions_per_round=3, items=4)
    split = tr.time_split(f)
    train_rounds = set(split.train["round"].unique().to_list())
    test_rounds = set(split.test["round"].unique().to_list())
    assert test_rounds == {8, 9}
    assert train_rounds == set(range(8))
    assert not train_rounds & test_rounds
    assert split.train.height + split.test.height == f.height
    assert split.describe()["by"] == "round"


def test_six_rounds_keep_one_for_the_test() -> None:
    split = tr.time_split(frame(rounds=6, sessions_per_round=1, items=2))
    assert split.test["round"].unique().to_list() == [5]


def test_without_rounds_the_split_is_by_session_start_and_never_splits_a_session() -> None:
    f = frame(rounds=5, sessions_per_round=2, items=3).drop("round")
    split = tr.time_split(f)
    train_sessions = set(split.train["session_id"].to_list())
    test_sessions = set(split.test["session_id"].to_list())
    assert not train_sessions & test_sessions
    assert test_sessions == {"r4s0", "r4s1"}  # the latest two of ten
    assert split.by == "session start"


def test_the_split_needs_two_rounds() -> None:
    with pytest.raises(ValueError):
        tr.time_split(frame(rounds=1, sessions_per_round=2, items=2))


def test_the_export_has_position_prior_zero_and_scores_with_the_scorer() -> None:
    f = frame(rounds=5, sessions_per_round=10, items=10)
    x = tr.design(f, SCHEMA, prior=True)
    fit = tr.fit_logistic(
        x, tr.labels(f), f["session_id"].to_numpy(), SCHEMA, grid=(0.1, 1.0), folds=3
    )
    model = tr.export(fit, SCHEMA, with_prior=True, extra={"data": "test"})
    assert model["position_prior"] == 0.0
    assert model["weights"]["position_prior"] == 0.0
    assert set(model["weights"]) == set(SCHEMA.names)
    assert model["featureSchemaVersion"] == SCHEMA.version
    assert model["training"]["C"] in (0.1, 1.0)
    assert model["training"]["classWeight"] is None
    assert model["training"]["learnedPositionPriorWeight"] > 0  # it soaked up the position effect
    assert model["weights"]["artist_affinity"] > 1.0  # the signal planted in the data
    weights = tr.scorer_weights(model)
    assert set(weights) == {"bias", *SCHEMA.names}
    assert 0 < scorer.score(weights, [0.5] * len(SCHEMA), SCHEMA) < 1


def test_weights_beyond_the_api_limit_are_clipped_and_reported() -> None:
    # A perfectly separable feature drives its weight up without bound under weak regularisation.
    n = 200
    rng = np.random.default_rng(3)
    x = np.zeros((n, len(SCHEMA)))
    y = (rng.random(n) < 0.5).astype(np.int_)
    x[:, 0] = y
    fit = tr.fit_logistic(x, y, np.arange(n) % 20, SCHEMA, grid=(1e6,), folds=2)
    assert "follows_author" in fit.clipped
    assert fit.weights["follows_author"] == tr.WEIGHT_LIMIT

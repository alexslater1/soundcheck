"""`soundcheck-lab train`: learn ranking weights from the extract.

- **Time split:** the last 20 % of rounds (or, without a simulator file, of sessions by start time)
  is the test set. A round is never split, so no test round leaks into training.
- **Position bias:** items higher in the feed get more attention whatever they are. During training
  ``position_prior = 1 / log2(2 + position)`` soaks that up, so the other weights don't have to; the
  exported weight for it is 0, because serving logs and scores the feature as 0.
  A second model is trained without it, for the comparison.
- **Model:** L2 logistic regression with ``C`` chosen by grouped cross-validation on ``session_id``
  (log-loss), no class weights. The coefficients are the config's weights as they are: the features
  are already in [0, 1] and the scorer computes ``σ(bias + Σ wᵢ·fᵢ)``, which is what the model is.
- **Upper bound:** a gradient-boosted tree model on the same features, reported only (it can't be
  served: the feed scores with a linear model).
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
import numpy.typing as npt
import polars as pl

from soundcheck_lab.schema import FeatureSchema

TEST_SHARE = 0.2
C_GRID = (0.01, 0.03, 0.1, 0.3, 1.0, 3.0, 10.0, 100.0)
CV_FOLDS = 5
WEIGHT_LIMIT = 10.0  # the API's rule for every weight
PRIOR = "position_prior"
SEED = 20261005

Matrix = npt.NDArray[np.float64]


def position_prior(position: int) -> float:
    """The training-time prior for an item shown at ``position`` (from 0): 1, 0.63, 0.5, …"""
    return 1.0 / math.log2(2 + position)


def prior_expr() -> pl.Expr:
    return (1.0 / (pl.col("position").cast(pl.Float64) + 2.0).log(base=2)).alias(PRIOR)


@dataclass(frozen=True, slots=True)
class Split:
    by: str  # "round" or "session start"
    test_from: int  # the first test round, or the index of the first test session by start time
    units: int  # rounds, or sessions
    train: pl.DataFrame
    test: pl.DataFrame

    def describe(self) -> dict[str, Any]:
        return {
            "by": self.by,
            "testFrom": self.test_from,
            "units": self.units,
            "testShare": TEST_SHARE,
            "trainRows": self.train.height,
            "testRows": self.test.height,
            "trainSessions": self.train["session_id"].n_unique(),
            "testSessions": self.test["session_id"].n_unique(),
        }


def _test_units(units: int, share: float = TEST_SHARE) -> int:
    return min(units - 1, max(1, round(units * share))) if units > 1 else 0


def time_split(frame: pl.DataFrame, share: float = TEST_SHARE) -> Split:
    """The last ``share`` of rounds is the test set; without rounds, the last ``share`` of sessions
    by their first ``served_at``. Whole rounds (and whole sessions) go to one side."""
    if "round" in frame.columns and frame["round"].null_count() == 0:
        rounds = sorted(frame["round"].unique().to_list())
        n_test = _test_units(len(rounds), share)
        if n_test == 0:
            raise ValueError("the time split needs at least two rounds")
        first = rounds[len(rounds) - n_test]
        test = frame.filter(pl.col("round") >= first)
        return Split("round", first, len(rounds), frame.filter(pl.col("round") < first), test)
    starts = (
        frame.group_by("session_id")
        .agg(pl.col("served_at").min().alias("start"))
        .sort(["start", "session_id"])
    )
    n_test = _test_units(starts.height, share)
    if n_test == 0:
        raise ValueError("the time split needs at least two sessions")
    first = starts.height - n_test
    test_sessions = starts["session_id"][first:].to_list()
    is_test = pl.col("session_id").is_in(test_sessions)
    return Split(
        "session start", first, starts.height, frame.filter(~is_test), frame.filter(is_test)
    )


def design(frame: pl.DataFrame, schema: FeatureSchema, *, prior: bool) -> Matrix:
    """The feature matrix in schema order. ``prior``: the position prior replaces the logged 0."""
    frame = frame.with_columns(prior_expr() if prior else pl.lit(0.0).alias(PRIOR))
    return frame.select(list(schema.names)).to_numpy().astype(np.float64)


def labels(frame: pl.DataFrame) -> npt.NDArray[np.int_]:
    return frame["positive"].cast(pl.Int8).to_numpy().astype(np.int_)


@dataclass(frozen=True, slots=True)
class Fit:
    c: float
    cv_log_loss: dict[float, float]
    bias: float
    weights: dict[str, float]
    clipped: list[str]


def fit_logistic(
    x: Matrix,
    y: npt.NDArray[np.int_],
    groups: npt.NDArray[Any],
    schema: FeatureSchema,
    grid: tuple[float, ...] = C_GRID,
    folds: int = CV_FOLDS,
) -> Fit:
    from sklearn.linear_model import LogisticRegression
    from sklearn.metrics import log_loss
    from sklearn.model_selection import GroupKFold

    n_groups = len(np.unique(groups))
    splitter = GroupKFold(n_splits=min(folds, n_groups))
    scores: dict[float, float] = {}
    for c in grid:
        losses = []
        for train_idx, valid_idx in splitter.split(x, y, groups):
            model = LogisticRegression(C=c, max_iter=2000)  # L2 is the default penalty
            model.fit(x[train_idx], y[train_idx])
            p = model.predict_proba(x[valid_idx])[:, 1]
            losses.append(log_loss(y[valid_idx], p, labels=[0, 1]))
        scores[c] = float(np.mean(losses))
    best = min(grid, key=lambda c: (scores[c], c))
    model = LogisticRegression(C=best, max_iter=2000)
    model.fit(x, y)
    weights: dict[str, float] = {}
    clipped: list[str] = []
    for feature, w in zip(schema.features, model.coef_[0], strict=True):
        value = float(w)
        if abs(value) > WEIGHT_LIMIT:
            clipped.append(feature.name)
            value = math.copysign(WEIGHT_LIMIT, value)
        weights[feature.name] = value
    bias = float(model.intercept_[0])
    if abs(bias) > WEIGHT_LIMIT:
        clipped.append("bias")
        bias = math.copysign(WEIGHT_LIMIT, bias)
    return Fit(best, scores, bias, weights, clipped)


def export(
    fit: Fit, schema: FeatureSchema, *, with_prior: bool, extra: dict[str, Any]
) -> dict[str, Any]:
    """model.json: the weights for the scorer and the API, ``position_prior`` exported as 0."""
    weights = dict(fit.weights)
    learned_prior = weights[PRIOR]
    weights[PRIOR] = 0.0
    return {
        "featureSchemaVersion": schema.version,
        "bias": fit.bias,
        "weights": weights,
        "position_prior": 0.0,
        "training": {
            "model": "logistic",
            "penalty": "l2",
            "C": fit.c,
            "cGrid": list(C_GRID),
            "cvFolds": CV_FOLDS,
            "cvGroups": "session_id",
            "cvLogLoss": {str(c): v for c, v in fit.cv_log_loss.items()},
            "classWeight": None,
            "positionPrior": (
                "1/log2(2+position) during training; exported as 0"
                if with_prior
                else "not used (the column is 0 in training)"
            ),
            "learnedPositionPriorWeight": learned_prior if with_prior else None,
            "clippedToLimit": fit.clipped,
            **extra,
        },
    }


def scorer_weights(model: dict[str, Any]) -> dict[str, float]:
    """A model.json (or a config's weights) as the scorer's ``{bias, feature: w}`` mapping."""
    weights = {str(k): float(v) for k, v in model["weights"].items()}
    if "bias" in model:
        weights["bias"] = float(model["bias"])
    return weights


def fit_gbt(x: Matrix, y: npt.NDArray[np.int_]) -> Any:
    from sklearn.ensemble import HistGradientBoostingClassifier

    model = HistGradientBoostingClassifier(random_state=SEED)
    model.fit(x, y)
    return model


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.write_text(json.dumps(value, indent=1) + "\n", encoding="utf-8")

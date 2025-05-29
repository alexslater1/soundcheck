"""The ranking score: ``score = σ(bias + Σ wᵢ·fᵢ)``.

The Java feed module computes the same thing (``feed.internal.ranking.Scorer``); the parity cases in
``contracts/ranking/parity-cases.json`` hold both to the same numbers.
"""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence

from soundcheck_lab.schema import FeatureSchema

BIAS = "bias"


def sigmoid(z: float) -> float:
    """The logistic function, written so that a large |z| cannot overflow ``exp``."""
    if z >= 0:
        return 1.0 / (1.0 + math.exp(-z))
    e = math.exp(z)
    return e / (1.0 + e)


def contributions(
    weights: Mapping[str, float], features: Sequence[float], schema: FeatureSchema
) -> list[float]:
    """``wᵢ·fᵢ`` per feature, in schema order (what "Why this post?" is built from)."""
    if len(features) != len(schema):
        raise ValueError(f"expected {len(schema)} features, got {len(features)}")
    expected = {BIAS, *schema.names}
    if set(weights) != expected:
        raise ValueError(f"weights must name exactly {sorted(expected)}")
    return [weights[feature.name] * features[feature.index] for feature in schema.features]


def logit(weights: Mapping[str, float], features: Sequence[float], schema: FeatureSchema) -> float:
    """``bias + Σ wᵢ·fᵢ``, summed in schema order."""
    total = weights[BIAS]
    for contribution in contributions(weights, features, schema):
        total += contribution
    return total


def score(weights: Mapping[str, float], features: Sequence[float], schema: FeatureSchema) -> float:
    """The predicted probability of a positive engagement."""
    return sigmoid(logit(weights, features, schema))

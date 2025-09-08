"""Ranking metrics: the Python twin of the feed module's ``RankingMetrics``.

The hand-computed cases in ``contracts/ranking/metric-cases.json`` hold both to the same answers
(tolerance 1e-9): pytest asserts them here, ``RankingMetricsTest`` in Java. Pure functions; ``None``
where a metric is undefined (no pairs, no items, no positive), as Java returns null.
"""

from __future__ import annotations

import math
from collections.abc import Hashable, Sequence
from dataclasses import dataclass

LOG_LOSS_EPSILON = 1e-15


def overlap_at[T: Hashable](k: int, a: Sequence[T], b: Sequence[T]) -> float | None:
    """The share of ``a``'s top k that is also in ``b``'s top k, over the longer cut list."""
    top_a, top_b = list(a[:k]), list(b[:k])
    denominator = max(len(top_a), len(top_b))
    if denominator == 0:
        return None
    in_b = set(top_b)
    return sum(1 for item in top_a if item in in_b) / denominator


def kendall_tau_b(x: Sequence[float], y: Sequence[float]) -> float | None:
    """Kendall's τ-b, (C − D) / √((n₀ − n₁)(n₀ − n₂)); ties in either count as neither."""
    if len(x) != len(y):
        raise ValueError("x and y must score the same items")
    n = len(x)
    concordant = discordant = tied_x = tied_y = 0
    for i in range(n):
        for j in range(i + 1, n):
            dx = (x[i] > x[j]) - (x[i] < x[j])
            dy = (y[i] > y[j]) - (y[i] < y[j])
            tied_x += dx == 0
            tied_y += dy == 0
            if dx != 0 and dy != 0:
                if dx == dy:
                    concordant += 1
                else:
                    discordant += 1
    pairs = n * (n - 1) // 2
    denominator = math.sqrt((pairs - tied_x) * (pairs - tied_y))
    if n < 2 or denominator == 0:
        return None
    return (concordant - discordant) / denominator


def auc(scores: Sequence[float], positive: Sequence[bool]) -> float | None:
    """ROC-AUC from ranks (Mann–Whitney U): tied scores share their average rank."""
    n = len(scores)
    order = sorted(range(n), key=lambda i: scores[i])
    positive_rank_sum = 0.0
    positives = 0
    i = 0
    while i < n:
        j = i
        while j + 1 < n and scores[order[j + 1]] == scores[order[i]]:
            j += 1
        average_rank = (i + j) / 2 + 1  # ranks count from 1
        for k in range(i, j + 1):
            if positive[order[k]]:
                positive_rank_sum += average_rank
                positives += 1
        i = j + 1
    negatives = n - positives
    if positives == 0 or negatives == 0:
        return None
    u = positive_rank_sum - positives * (positives + 1) / 2
    return u / (positives * negatives)


def log_loss(probabilities: Sequence[float], positive: Sequence[bool]) -> float | None:
    """Mean binary cross-entropy (natural log), each probability clipped to [ε, 1 − ε]."""
    if not probabilities:
        return None
    total = 0.0
    for p, y in zip(probabilities, positive, strict=True):
        p = min(1 - LOG_LOSS_EPSILON, max(LOG_LOSS_EPSILON, p))
        total -= math.log(p) if y else math.log(1 - p)
    return total / len(probabilities)


def _gain(rank: int) -> float:
    return 1 / math.log2(rank + 2)


def ndcg_at(k: int, scores: Sequence[float], positive: Sequence[bool]) -> float | None:
    """NDCG@k of one session, binary relevance. Highest score first; ``sorted`` is stable, so equal
    scores keep the order shown. ``None`` when the session has no positive."""
    positives = sum(1 for y in positive if y)
    if positives == 0:
        return None
    order = sorted(range(len(scores)), key=lambda i: -scores[i])
    dcg = sum(_gain(r) for r, i in enumerate(order[:k]) if positive[i])
    ideal = sum(_gain(r) for r in range(min(k, positives)))
    return dcg / ideal


def engagement_at(k: int, scores: Sequence[float], positive: Sequence[bool]) -> float | None:
    """The share of the session's top k (by score, ties in the order shown) that was positive."""
    if not scores:
        return None
    order = sorted(range(len(scores)), key=lambda i: -scores[i])[:k]
    return sum(1 for i in order if positive[i]) / len(order)


@dataclass(frozen=True, slots=True)
class CalibrationBin:
    lower: float
    upper: float
    count: int
    mean_predicted: float | None
    observed_rate: float | None


def calibration(
    probabilities: Sequence[float], positive: Sequence[bool], bins: int = 10
) -> list[CalibrationBin]:
    """Equal-width bins over [0, 1]: each bin's mean prediction against its observed positive rate.
    A well-calibrated model sits on the diagonal. 1.0 falls in the last bin."""
    counts = [0] * bins
    predicted = [0.0] * bins
    observed = [0] * bins
    for p, y in zip(probabilities, positive, strict=True):
        b = min(bins - 1, max(0, int(p * bins)))
        counts[b] += 1
        predicted[b] += p
        observed[b] += 1 if y else 0
    return [
        CalibrationBin(
            lower=b / bins,
            upper=(b + 1) / bins,
            count=counts[b],
            mean_predicted=predicted[b] / counts[b] if counts[b] else None,
            observed_rate=observed[b] / counts[b] if counts[b] else None,
        )
        for b in range(bins)
    ]

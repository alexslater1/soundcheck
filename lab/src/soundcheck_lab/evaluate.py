"""`soundcheck-lab evaluate`: offline metrics on the held-out rounds.

Every config scores the **test** rows with ``scorer.score`` (held to Java by the parity cases), on
the features as logged (``position_prior`` = 0, as at serving). The metrics come from
``soundcheck_lab.metrics`` (held to Java by the metric cases).

Two caveats the report states first:
- the data is simulated (the simulator's click model);
- replaying logged data favours the config that served it: a candidate only re-orders the items v1
  chose. The exploration-only slice (items exploration put in at random) is less biased.
"""

from __future__ import annotations

import json
from collections.abc import Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np
import polars as pl

from soundcheck_lab import metrics, scorer
from soundcheck_lab.schema import FeatureSchema
from soundcheck_lab.train import design

NDCG_K = 10
ENGAGEMENT_K = (5, 10)

CAVEAT = (
    "Trained and evaluated on **simulated** data (the click model); replay on logged data "
    "is biased towards the serving config."
)


@dataclass(frozen=True, slots=True)
class Scored:
    name: str
    kind: str  # "config" (servable weights), "reference" (not a probability or not servable)
    scores: list[float]
    probabilities: bool = True  # False: scores only order items (AUC and NDCG, no log-loss)
    weights: dict[str, float] | None = None


@dataclass(slots=True)
class Result:
    name: str
    kind: str
    rows: int
    sessions: int
    auc: float | None
    log_loss: float | None
    ndcg10: float | None
    ndcg_sessions: int
    ndcg_by_session: list[float]  # in session order, the same sessions for every config
    engagement: dict[int, float | None]
    calibration: list[metrics.CalibrationBin]
    exploration: dict[str, Any] = field(default_factory=dict)

    def as_json(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "kind": self.kind,
            "rows": self.rows,
            "sessions": self.sessions,
            "auc": self.auc,
            "logLoss": self.log_loss,
            "ndcg10": self.ndcg10,
            "ndcgSessions": self.ndcg_sessions,
            "engagementAt": {str(k): v for k, v in self.engagement.items()},
            "calibration": [
                {
                    "lower": b.lower,
                    "upper": b.upper,
                    "count": b.count,
                    "meanPredicted": b.mean_predicted,
                    "observedRate": b.observed_rate,
                }
                for b in self.calibration
            ],
            "explorationOnly": {k: v for k, v in self.exploration.items() if not k.startswith("_")},
        }


def score_config(
    frame: pl.DataFrame, weights: dict[str, float], schema: FeatureSchema
) -> list[float]:
    """The scorer over each row's logged features (``position_prior`` as logged: 0)."""
    x = design(frame, schema, prior=False)
    return [scorer.score(weights, row.tolist(), schema) for row in x]


def _sessions(frame: pl.DataFrame) -> list[list[int]]:
    """Row indexes per session, each in the order shown (by position)."""
    indexed = frame.with_row_index("_i").sort(["session_id", "position"])
    return [list(g["_i"].to_list()) for _, g in indexed.group_by("session_id", maintain_order=True)]


def _ndcg(
    groups: list[list[int]], scores: Sequence[float], positive: Sequence[bool]
) -> list[float]:
    """NDCG@10 of each session with a positive (which sessions those are doesn't depend on the
    scores, so every config's list covers the same sessions in the same order)."""
    values = []
    for rows in groups:
        v = metrics.ndcg_at(NDCG_K, [scores[i] for i in rows], [positive[i] for i in rows])
        if v is not None:
            values.append(v)
    return values


def _mean(values: Sequence[float]) -> float | None:
    return float(np.mean(values)) if values else None


def paired_bootstrap(
    a: Sequence[float], b: Sequence[float], resamples: int = 2000, seed: int = 20261005
) -> tuple[float, float, float] | None:
    """Mean of ``a − b`` over paired sessions and its 95 % percentile interval, resampling
    sessions with replacement. ``None`` for fewer than two sessions."""
    if len(a) != len(b):
        raise ValueError("a and b must cover the same sessions")
    if len(a) < 2:
        return None
    diff = np.asarray(a, dtype=np.float64) - np.asarray(b, dtype=np.float64)
    rng = np.random.default_rng(seed)
    means = diff[rng.integers(0, len(diff), size=(resamples, len(diff)))].mean(axis=1)
    return float(diff.mean()), float(np.quantile(means, 0.025)), float(np.quantile(means, 0.975))


def _engagement(
    groups: list[list[int]], scores: Sequence[float], positive: Sequence[bool], k: int
) -> float | None:
    values = [
        v
        for rows in groups
        if (v := metrics.engagement_at(k, [scores[i] for i in rows], [positive[i] for i in rows]))
        is not None
    ]
    return float(np.mean(values)) if values else None


def evaluate_one(frame: pl.DataFrame, scored: Scored) -> Result:
    positive = [bool(v) for v in frame["positive"].to_list()]
    groups = _sessions(frame)
    per_session = _ndcg(groups, scored.scores, positive)
    result = Result(
        name=scored.name,
        kind=scored.kind,
        rows=frame.height,
        sessions=len(groups),
        auc=metrics.auc(scored.scores, positive),
        log_loss=metrics.log_loss(scored.scores, positive) if scored.probabilities else None,
        ndcg10=_mean(per_session),
        ndcg_sessions=len(per_session),
        ndcg_by_session=per_session,
        engagement={k: _engagement(groups, scored.scores, positive, k) for k in ENGAGEMENT_K},
        calibration=metrics.calibration(scored.scores, positive) if scored.probabilities else [],
    )
    # The exploration-only slice: the rows exploration put in at random (less biased).
    mask = frame["exploration"].to_list()
    idx = [i for i, m in enumerate(mask) if m]
    if idx:
        sub = frame[idx]
        sub_scores = [scored.scores[i] for i in idx]
        sub_positive = [positive[i] for i in idx]
        sub_per_session = _ndcg(_sessions(sub), sub_scores, sub_positive)
        result.exploration = {
            "rows": len(idx),
            "positives": sum(sub_positive),
            "auc": metrics.auc(sub_scores, sub_positive),
            "logLoss": metrics.log_loss(sub_scores, sub_positive) if scored.probabilities else None,
            "ndcg10": _mean(sub_per_session),
            "ndcgSessions": len(sub_per_session),
            "_bySession": sub_per_session,
        }
    else:
        result.exploration = {"rows": 0}
    return result


# ── the report ───────────────────────────────────────────────────────────────────────────────────
def fmt(v: float | None, digits: int = 4) -> str:
    return "—" if v is None else f"{v:.{digits}f}"


def compare(results: list[Result], candidate: str, baseline: str) -> dict[str, Any]:
    """Candidate − baseline NDCG@10 with a paired bootstrap interval, on all test rows and on the
    exploration-only slice."""
    by = {r.name: r for r in results}
    c, b = by[candidate], by[baseline]
    out: dict[str, Any] = {"candidate": candidate, "baseline": baseline}
    for key, (x, y) in {
        "all": (c.ndcg_by_session, b.ndcg_by_session),
        "explorationOnly": (
            c.exploration.get("_bySession", []),
            b.exploration.get("_bySession", []),
        ),
    }.items():
        boot = paired_bootstrap(x, y)
        out[key] = (
            None
            if boot is None
            else {"diff": boot[0], "ci95": [boot[1], boot[2]], "sessions": len(x)}
        )
    return out


def verdict(comparison: dict[str, Any]) -> str:
    """One honest sentence: did the candidate beat the baseline on held-out NDCG@10? A "win" needs
    the whole 95 % interval above 0."""
    baseline = comparison["baseline"]
    whole = comparison.get("all")
    if whole is None:
        return "NDCG@10 is undefined (too few test sessions with a positive): no verdict."
    lo, hi = whole["ci95"]
    if lo > 0:
        word = "beats"
    elif hi < 0:
        word = "loses to"
    else:
        word = "does not measurably differ from"
    line = (
        f"The learned config {word} {baseline} on held-out NDCG@10 (all test rows): "
        f"{whole['diff']:+.4f} (95 % CI {lo:+.4f} to {hi:+.4f}, {whole['sessions']} sessions)."
    )
    expl = comparison.get("explorationOnly")
    if expl is not None:
        e_lo, e_hi = expl["ci95"]
        line += (
            f" Exploration-only slice: {expl['diff']:+.4f} (95 % CI {e_lo:+.4f} to {e_hi:+.4f}, "
            f"{expl['sessions']} sessions)."
        )
    return line


def explain(results: list[Result], baseline: str) -> list[str]:
    """Facts that explain the verdict, from the numbers themselves (not a judgement)."""
    by = {r.name: r for r in results}
    lines: list[str] = []
    base = by.get(baseline)
    learned = by.get("Learned")
    gbt = by.get("GBT (upper bound)")
    if gbt and learned:
        lines.append(
            f"Headroom: gradient-boosted trees on the same features reach AUC {fmt(gbt.auc)} and "
            f"NDCG@10 {fmt(gbt.ndcg10)}, against the learned linear config's {fmt(learned.auc)} "
            f"and {fmt(learned.ndcg10)}: "
            + (
                "a gap this small says the features, not the linear model, limit what can be "
                "learned from these rows."
                if gbt.auc is not None and learned.auc is not None and gbt.auc - learned.auc < 0.01
                else "a non-linear model finds more in the same features than a linear score can."
            )
        )
    if learned and base:
        e, b = learned.exploration, base.exploration
        lines.append(
            f"Exploration-only slice ({e.get('rows', 0)} rows, {e.get('ndcgSessions', 0)} sessions "
            f"with a positive): Learned {fmt(e.get('ndcg10'))} against {baseline} "
            f"{fmt(b.get('ndcg10'))}; AUC {fmt(e.get('auc'))} against {fmt(b.get('auc'))}."
        )
        lines.append(
            f"Over every test row, AUC {fmt(learned.auc)} against {fmt(base.auc)} and log-loss "
            f"{fmt(learned.log_loss)} against {fmt(base.log_loss)} (lower is better)."
        )
    no_prior = by.get("Learned (no prior)")
    if no_prior and learned:
        lines.append(
            f"Trained without the position prior: NDCG@10 {fmt(no_prior.ndcg10)} "
            f"(with it: {fmt(learned.ndcg10)})."
        )
    served = by.get("As served")
    if served:
        lines.append(
            f"The order the feed actually showed (v1 plus diversity and exploration) scores "
            f"{fmt(served.ndcg10)}."
        )
    lines.append(
        "The click model decides from hidden taste and a hidden per-post quality; the "
        "features see taste only through follows, affinities and genre similarity, so part of "
        "what makes an item positive is invisible to any weights."
    )
    return lines


def report_markdown(
    results: list[Result],
    *,
    split: dict[str, Any],
    data: dict[str, Any],
    models: dict[str, dict[str, Any]],
    verdict_line: str,
    why: list[str],
    commit: str | None,
    images: list[str],
) -> str:
    lines = [
        "# Ranking lab report",
        "",
        CAVEAT,
        "",
        f"**Result:** {verdict_line}",
        "",
    ]
    lines += [f"- {w}" for w in why]
    lines += [
        "",
        "## Data",
        "",
        f"- Extract: `{data.get('path')}` (commit `{commit or 'unknown'}`)",
        f"- Rows {data.get('rows')}, sessions {data.get('sessions')}, positives "
        f"{data.get('positives')} ({fmt(data.get('positiveRate'), 3)}), synthetic share "
        f"{fmt(data.get('syntheticShare'), 3)}",
        f"- Time split by {split['by']}: test from {split['testFrom']} of {split['units']} "
        f"(last {int(split['testShare'] * 100)} %); train {split['trainRows']} rows / "
        f"{split['trainSessions']} sessions, test {split['testRows']} rows / "
        f"{split['testSessions']} sessions",
        "",
        "## Held-out metrics (all test rows: replay, biased towards the serving config)",
        "",
        "| Config | Rows | Sessions | AUC | Log-loss | NDCG@10 (sessions) | Eng@5 | Eng@10 |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for r in results:
        lines.append(
            f"| {r.name} | {r.rows} | {r.sessions} | {fmt(r.auc)} | {fmt(r.log_loss)} | "
            f"{fmt(r.ndcg10)} ({r.ndcg_sessions}) | {fmt(r.engagement[5])} | "
            f"{fmt(r.engagement[10])} |"
        )
    lines += [
        "",
        "## Exploration-only slice (items exploration put in at random: the less biased number)",
        "",
        "| Config | Rows | Positives | AUC | Log-loss | NDCG@10 (sessions) |",
        "|---|---|---|---|---|---|",
    ]
    for r in results:
        e = r.exploration
        lines.append(
            f"| {r.name} | {e.get('rows', 0)} | {e.get('positives', '—')} | {fmt(e.get('auc'))} | "
            f"{fmt(e.get('logLoss'))} | {fmt(e.get('ndcg10'))} ({e.get('ndcgSessions', 0)}) |"
        )
    lines += ["", "## Weights", "", "| Feature | " + " | ".join(models) + " |"]
    lines.append("|---|" + "---|" * len(models))
    names = list(next(iter(models.values())).keys()) if models else []
    for name in names:
        lines.append(
            f"| {name} | " + " | ".join(fmt(m.get(name), 3) for m in models.values()) + " |"
        )
    lines += ["", "## Charts", ""]
    lines += [f"![{img}]({img})" for img in images]
    lines += [
        "",
        "## Notes",
        "",
        "- AUC and log-loss are over every test row; NDCG@10 and engagement@k re-rank each test "
        "session's served items and average over sessions (NDCG over sessions with a positive).",
        "- *As served* is the order the feed showed (after diversity and exploration), not a "
        "probability: it has NDCG and engagement only.",
        "- *GBT* (gradient-boosted trees on the same features) is an upper bound for these "
        "features; the feed scores with a linear model, so it can't be published.",
        "- `position_prior` is exported as 0: the feed logs and scores it as 0, so "
        "the published config's log-loss is not what training saw.",
        "",
    ]
    return "\n".join(lines)


def charts(
    out: Path,
    results: list[Result],
    models: dict[str, dict[str, float]],
    position_curve: list[tuple[int, float, int]],
) -> list[str]:
    """calibration.png, ndcg.png, weights.png, position.png. Returns the file names written."""
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    from soundcheck_lab.train import position_prior

    written: list[str] = []

    fig, ax = plt.subplots(figsize=(6, 5))
    ax.plot([0, 1], [0, 1], linestyle=":", color="grey", label="perfect")
    for r in results:
        pts = [
            (b.mean_predicted, b.observed_rate)
            for b in r.calibration
            if b.mean_predicted is not None and b.observed_rate is not None
        ]
        if pts:
            ax.plot([p[0] for p in pts], [p[1] for p in pts], marker="o", label=r.name)
    ax.set(xlabel="mean predicted", ylabel="observed positive rate", title="Calibration (10 bins)")
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(out / "calibration.png", dpi=110)
    plt.close(fig)
    written.append("calibration.png")

    fig, ax = plt.subplots(figsize=(7, 4))
    names = [r.name for r in results]
    allv = [r.ndcg10 or 0.0 for r in results]
    expl = [r.exploration.get("ndcg10") or 0.0 for r in results]
    xs = np.arange(len(names))
    ax.bar(xs - 0.2, allv, 0.4, label="all test rows (replay)")
    ax.bar(xs + 0.2, expl, 0.4, label="exploration only")
    ax.set_xticks(xs, names, rotation=20, ha="right", fontsize=8)
    ax.set(ylabel="mean NDCG@10", title="NDCG@10 by config (held-out rounds, simulated)")
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(out / "ndcg.png", dpi=110)
    plt.close(fig)
    written.append("ndcg.png")

    if models:
        fig, ax = plt.subplots(figsize=(8, 5))
        features = list(next(iter(models.values())).keys())
        ys = np.arange(len(features))
        width = 0.8 / len(models)
        for i, (name, w) in enumerate(models.items()):
            ax.barh(ys + i * width, [w.get(f, 0.0) for f in features], width, label=name)
        ax.set_yticks(ys + 0.4 - width / 2, features, fontsize=8)
        ax.axvline(0, color="grey", linewidth=0.5)
        ax.set(xlabel="weight", title="Weights")
        ax.legend(fontsize=8)
        fig.tight_layout()
        fig.savefig(out / "weights.png", dpi=110)
        plt.close(fig)
        written.append("weights.png")

    if position_curve:
        fig, ax = plt.subplots(figsize=(7, 4))
        xs_p = [p for p, _, _ in position_curve]
        ax.plot(xs_p, [rate for _, rate, _ in position_curve], marker=".", label="positive rate")
        top = position_curve[0][1] or 1.0
        ax.plot(
            xs_p,
            [top * position_prior(p) for p in xs_p],
            linestyle="--",
            label="1/log2(2+position), scaled",
        )
        ax.set(
            xlabel="position", ylabel="share positive", title="Positive rate by position (all rows)"
        )
        ax.legend(fontsize=8)
        fig.tight_layout()
        fig.savefig(out / "position.png", dpi=110)
        plt.close(fig)
        written.append("position.png")
    return written


def position_curve(frame: pl.DataFrame, max_position: int = 40) -> list[tuple[int, float, int]]:
    rows = (
        frame.filter(pl.col("position") < max_position)
        .group_by("position")
        .agg(pl.col("positive").mean().alias("rate"), pl.len().alias("n"))
        .sort("position")
        .rows()
    )
    return [(int(p), float(r), int(n)) for p, r, n in rows]


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.write_text(json.dumps(value, indent=1) + "\n", encoding="utf-8")


# ── the database side (offline tests don't reach it) ─────────────────────────────────────────────
def fetch_active(db_url: str) -> tuple[int, dict[str, float]]:
    import psycopg

    with psycopg.connect(db_url, autocommit=True) as conn:
        row = conn.execute(
            "select version, weights::text from feed.ranking_config where status = 'ACTIVE'"
        ).fetchone()
    if row is None:
        raise ValueError("no ACTIVE ranking config")
    weights = {str(k): float(v) for k, v in json.loads(row[1]).items()}
    return int(row[0]), weights

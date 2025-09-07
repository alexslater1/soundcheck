"""After a run: what the database recorded, and whether engagement follows the hidden taste.

Reads `feed.served_outcome` (the one definition of an engaged item) for the run's feed
sessions, read-only, and joins each row in Python with the persona's hidden trueAffinity for that
post (which only the simulator knows). The statistic is Spearman's ρ between trueAffinity and the
view's `positive` label, plus the positive rate by affinity quintile."""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any

import numpy as np

from soundcheck_lab.sim import click
from soundcheck_lab.sim.personas import SimPersona
from soundcheck_lab.sim.world import World

# Expected index: served_item_user (user_id, served_at) for the outer filter; the view's lateral
# join uses interaction_event's (feed_session_id, user_id, post_id) index (V130).
OUTCOME_SQL = """
select o.user_id::text, o.post_id::text, o.session_id::text, o.positive, o.negative
  from feed.served_outcome o
 where o.user_id = any(%s::uuid[]) and o.served_at >= %s::timestamptz
"""


def summarise(
    rows: Sequence[tuple[str, str, str, bool, bool]],
    sessions: set[str],
    world: World,
    personas: Sequence[SimPersona],
    user_ids: dict[int, str],
    params: click.Params = click.DEFAULT,
) -> dict[str, Any]:
    """Counts and the affinity statistic over the rows that belong to the run's sessions."""
    by_user = {user_ids[p.n]: p for p in personas if p.n in user_ids}
    affinities: list[float] = []
    positives: list[float] = []
    negatives = 0
    for user_id, post_id, session_id, positive, negative in rows:
        persona = by_user.get(user_id)
        if session_id not in sessions or persona is None or post_id not in world.posts:
            continue
        affinities.append(click.true_affinity(persona.taste, world.item(post_id), params))
        positives.append(1.0 if positive else 0.0)
        negatives += bool(negative)
    served = len(affinities)
    result: dict[str, Any] = {
        "servedRows": served,
        "positive": int(sum(positives)),
        "negative": negatives,
        "positiveRate": round(sum(positives) / served, 4) if served else None,
        "spearmanAffinityPositive": round(click.spearman(affinities, positives), 4)
        if served
        else None,
    }
    if served >= 5:
        order = np.argsort(np.asarray(affinities), kind="stable")
        rates = []
        for chunk in np.array_split(order, 5):
            rates.append(
                {
                    "affinityFrom": round(float(affinities[int(chunk[0])]), 3),
                    "affinityTo": round(float(affinities[int(chunk[-1])]), 3),
                    "rows": len(chunk),
                    "positiveRate": round(float(np.mean([positives[int(i)] for i in chunk])), 4),
                }
            )
        result["byAffinityQuintile"] = rates
    return result


def run(
    conninfo: str,
    started_at: str,
    sessions: set[str],
    world: World,
    personas: Sequence[SimPersona],
    user_ids: dict[int, str],
    params: click.Params = click.DEFAULT,
) -> dict[str, Any]:
    import psycopg

    with (
        psycopg.connect(conninfo, options="-c default_transaction_read_only=on") as conn,
        conn.cursor() as cur,
    ):
        cur.execute(OUTCOME_SQL, (list(user_ids.values()), started_at))
        rows = [(str(r[0]), str(r[1]), str(r[2]), bool(r[3]), bool(r[4])) for r in cur.fetchall()]
    return summarise(rows, sessions, world, personas, user_ids, params)

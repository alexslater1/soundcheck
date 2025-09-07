"""Interaction events as the app sends them (`watchTracker.ts`, `track()` in the app).

Shared by the engagement pass (`seed/engage.py`) and the simulator (`sim/`), so both send
the same shape: `POST /api/v1/events` with 1 to 100 events, each naming the post, the surface, the
feed session it was served in and its position there."""

from __future__ import annotations

from collections.abc import Iterator, Sequence
from typing import Any, Protocol

Event = dict[str, Any]
MAX_BATCH = 100  # POST /events takes 1 to 100 events
PROGRESS_MARKS = (0.25, 0.5, 0.75)  # WATCH_PROGRESS is sent as each of these is crossed
COMPLETE_AT = 0.95  # watched this far counts as COMPLETE (the app's watchTracker)


def event(
    post_id: str,
    kind: str,
    position: int,
    session_id: str,
    client_ts: str,
    value: float | None = None,
    surface: str = "FOR_YOU",
) -> Event:
    """One event, in the body shape of `EventIn` (contracts/openapi.json)."""
    return {
        "postId": post_id,
        "surface": surface,
        "type": kind,
        "position": position,
        "sessionId": session_id,
        "clientTs": client_ts,
        "value": value,
    }


def watch_kinds(fraction: float) -> list[tuple[str, float | None]]:
    """What watching {@code fraction} of a clip sends after VIEW_START: a WATCH_PROGRESS for each
    mark crossed, then COMPLETE at 95 %."""
    kinds: list[tuple[str, float | None]] = [
        ("WATCH_PROGRESS", mark) for mark in PROGRESS_MARKS if fraction >= mark
    ]
    if fraction >= COMPLETE_AT:
        kinds.append(("COMPLETE", None))
    return kinds


def batches(events: Sequence[Event], size: int = MAX_BATCH) -> Iterator[list[Event]]:
    """{@code events} in order, at most {@code size} at a time."""
    for i in range(0, len(events), size):
        yield list(events[i : i + size])


class Poster(Protocol):
    """What sending needs from an API client (`seed.api.Api.post`)."""

    def post(self, persona: Any, url: str, body: Any = None) -> Any: ...


def send(api: Poster, persona: Any, events: Sequence[Event]) -> int:
    """Posts {@code events} as {@code persona} in batches of at most 100. Returns how many."""
    for batch in batches(events):
        api.post(persona, "/api/v1/events", {"events": batch})
    return len(events)

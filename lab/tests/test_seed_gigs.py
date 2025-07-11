"""A seed run never puts a gig back at a venue it was moved away from."""

import json
from datetime import date
from pathlib import Path
from typing import Any

import httpx

from soundcheck_lab.seed import run
from soundcheck_lab.seed.api import Api
from soundcheck_lab.seed.personas import PERSONAS
from soundcheck_lab.seed.realclips import Act, Gig

THE_O2 = {"id": "o2", "name": "The O2", "city": "London", "location": {"lat": 51.503, "lng": 0.003}}
ISLINGTON = {
    "id": "islington",
    "name": "O2 Academy2 Islington",
    "city": "London",
    "location": {"lat": 51.5343, "lng": -0.1058},
}
LIAM = Gig(
    key="2025-06-14|liam",
    night=date(2025, 6, 14),
    venue="The O2",
    city="London",
    acts=(Act("Liam Gallagher"),),
)
INDIE = [p for p in PERSONAS if p.indie]


class Catalog:
    """GET /venues, GET and POST /gigs, attendance: just enough of the API for add_gigs."""

    def __init__(self, venues: list[dict[str, Any]], gigs: dict[str, dict[str, Any]]) -> None:
        self.venues = venues
        self.gigs = gigs
        self.posted: list[dict[str, Any]] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.startswith("/api/v1/auth/"):
            user = {"id": "u", "handle": "h"}
            return httpx.Response(200, json={"accessToken": "t", "expiresIn": 900, "user": user})
        if path == "/api/v1/venues":
            q = request.url.params["q"].lower().removeprefix("the ")
            return httpx.Response(200, json=[v for v in self.venues if q in v["name"].lower()])
        if path == "/api/v1/gigs" and request.method == "POST":
            body = json.loads(request.content)
            self.posted.append(body)
            venue = next(v for v in self.venues if v["id"] == body.get("venueId"))
            gig = {"id": f"g{len(self.gigs) + 1}", "title": body["artistNames"][0], "venue": venue}
            self.gigs[gig["id"]] = gig
            return httpx.Response(201, json={"item": gig, "created": True})
        if path.startswith("/api/v1/gigs/") and path.endswith("/attendance"):
            return httpx.Response(200, json={})
        if path.startswith("/api/v1/gigs/"):
            gig_id = path.rsplit("/", 1)[-1]
            if gig_id in self.gigs:
                return httpx.Response(200, json={"gig": self.gigs[gig_id]})
            return httpx.Response(404, json={"code": "GIG_NOT_FOUND", "detail": gig_id})
        return httpx.Response(404, json={"code": "NOT_FOUND", "detail": path})


def client(server: Catalog) -> Api:
    return Api(
        "http://api.test",
        transport=httpx.MockTransport(server),
        sleep=lambda _: None,
        auth_spacing_s=0,
    )


def first_run_state(tmp_path: Path) -> run.State:
    """What the first small run left: Liam Gallagher added at O2 Academy2 Islington, and that venue
    remembered for "The O2" (fixed by a695a98; the gig was then moved on the database)."""
    state = run.State.load(tmp_path / "state.json")
    state.section("gigs")[LIAM.key] = {"id": "g-liam", "attendees": []}
    state.section("venues")["o2|London"] = "islington"
    return state


def test_a_gig_moved_since_it_was_added_is_kept_where_it_is_now(tmp_path: Path) -> None:
    moved = {"id": "g-liam", "title": "Liam Gallagher", "venue": THE_O2}
    catalog = Catalog([THE_O2, ISLINGTON], {"g-liam": moved})

    summaries = run.add_gigs(
        client(catalog), [LIAM], INDIE, {}, first_run_state(tmp_path), lambda _: None
    )

    assert catalog.posted == []  # not added a second time, at the old venue
    assert summaries[LIAM.key]["venue"]["name"] == "The O2"


def test_a_new_gig_takes_the_catalogs_venue_over_a_wrong_remembered_one(tmp_path: Path) -> None:
    catalog = Catalog([THE_O2, ISLINGTON], {})  # a reset database: the remembered gig is gone

    summaries = run.add_gigs(
        client(catalog), [LIAM], INDIE, {}, first_run_state(tmp_path), lambda _: None
    )

    assert [body["venueId"] for body in catalog.posted] == ["o2"]
    assert summaries[LIAM.key]["venue"]["name"] == "The O2"


def test_a_remembered_venue_still_counts_when_the_name_alone_cannot_decide() -> None:
    """An old-phone gig (no GPS) at "Dome": a short name only matches "The Dome London" when the
    clips were filmed next to it, so the venue a filmed gig there found is reused."""
    dome = {"id": "dome", "name": "The Dome London", "city": "London", "location": None}
    gig = Gig(
        key="2024-05-01|dome",
        night=date(2024, 5, 1),
        venue="Dome",
        city="London",
        acts=(Act("Keo"),),
    )
    api = client(Catalog([dome], {}))

    chosen = run.venue_for(api, INDIE[0], gig, [], {"dome|London": "dome"})

    assert chosen == {"venueId": "dome"}

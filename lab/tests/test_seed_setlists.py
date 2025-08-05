import json
from datetime import date
from pathlib import Path
from typing import Any

import httpx
import pytest

from soundcheck_lab.seed.setlists import (
    BudgetSpentError,
    Client,
    Found,
    State,
    acts_seen,
    backfill,
    merge,
    own_gig,
    parse,
)

FIXTURES = Path(__file__).parent / "fixtures" / "setlistfm"
MBID = "aaaaaaaa-0000-0000-0000-000000000001"


def page(name: str) -> dict[str, Any]:
    return dict(json.loads((FIXTURES / name).read_text()))


def bill(**kw: Any) -> dict[str, Any]:
    base: dict[str, Any] = {
        "key": "2026-09-20|Paper Lanterns",
        "date": "2026-09-20",
        "title": None,
        "festival": False,
        "venue": "The Lantern Rooms",
        "city": "London",
        "acts": [{"name": "Paper Lanterns", "mbid": MBID, "songs": 3}],
        "foundOn": None,
        "has_clips": True,
        "note": "",
    }
    return base | kw


class Fake:
    """setlist.fm as recorded pages: by artist (two pages) and by venue and day."""

    def __init__(self) -> None:
        self.requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        q = request.url.params
        if "venueId" in q:
            return httpx.Response(200, json=page("venue-day.json"))
        if q.get("artistMbid") == MBID:
            return httpx.Response(200, json=page(f"artist-p{q['p']}.json"))
        return httpx.Response(404, json={"code": 404, "message": "not found"})


def client(fake: Fake, state: State, budget: int = 100) -> Client:
    return Client(
        "test-key",
        state,
        budget=budget,
        transport=httpx.MockTransport(fake),
        sleep=lambda s: None,
        today=lambda: date(2026, 10, 3),
    )


def test_parse_reads_a_search_page_and_counts_songs() -> None:
    found = parse(page("venue-day.json"))
    assert [f.artist for f in found] == ["Paper Lanterns", "Tin Foxes", "Quiet Pylons"]
    assert found[1].songs == 6 and found[1].venue_id == "v1" and found[2].mbid is None
    assert found[0].day == date(2026, 9, 20)


def test_acts_seen_are_unique_by_mbid_and_narrowed_by_name() -> None:
    bills = [bill(), bill(key="b", acts=[{"name": "paper lanterns", "mbid": MBID}, {"name": "X"}])]
    assert [a.name for a in acts_seen(bills)] == ["Paper Lanterns", "X"]
    assert [a.name for a in acts_seen(bills, ["x"])] == ["X"]


def test_own_gig_needs_the_same_day_and_the_act_or_the_venue() -> None:
    found = parse(page("artist-p1.json"))
    bills = [bill()]
    assert own_gig(found[0], bills) is bills[0]
    assert own_gig(found[1], bills) is None  # another date: a candidate


def test_merge_adds_the_supports_before_the_headliner_and_lists_the_rest_as_candidates() -> None:
    bills = [bill()]
    by_artist = parse(page("artist-p1.json")) + parse(page("artist-p2.json"))
    result = merge(bills, by_artist, {bills[0]["key"]: parse(page("venue-day.json"))})
    assert [a["name"] for a in result.bills[0]["acts"]] == [
        "Quiet Pylons",
        "Tin Foxes",
        "Paper Lanterns",
    ]
    assert result.bills[0]["foundOn"]["externalId"] == "s1"
    assert [c.setlist_id for c in result.candidates] == ["s4", "s3", "s2"]
    assert bills[0]["acts"] == [{"name": "Paper Lanterns", "mbid": MBID, "songs": 3}]  # untouched


def test_merge_never_goes_past_ten_acts() -> None:
    full = [{"name": f"Act {i}", "mbid": None} for i in range(9)]
    bills = [bill(acts=[*full, {"name": "Paper Lanterns", "mbid": MBID}])]
    result = merge(bills, [], {bills[0]["key"]: parse(page("venue-day.json"))})
    assert len(result.bills[0]["acts"]) == 10
    assert any("not added" in c and "Tin Foxes" in c for c in result.changes)


def test_a_run_pages_back_to_the_month_writes_files_and_answers_are_kept(
    tmp_path: Path,
) -> None:
    bills_path = tmp_path / "bills.json"
    bills_path.write_text(json.dumps([bill()]))
    state = State.load(tmp_path / "state.json")
    fake = Fake()
    result = backfill(
        client(fake, state), state, bills_path, tmp_path, since=date(2025, 1, 1), log=lambda s: None
    )
    assert result is not None
    # page 2 holds a 2024 setlist, older than `since`: no page 3; then one venue-and-day lookup
    assert [r.url.params.get("p") for r in fake.requests] == ["1", "2", None]
    written = json.loads(bills_path.read_text())
    assert [a["name"] for a in written[0]["acts"]][-1] == "Paper Lanterns"
    assert len(written[0]["acts"]) == 3
    candidates = (tmp_path / "candidates.csv").read_text().splitlines()
    assert candidates[0].startswith("date,artist") and len(candidates) == 3  # s3, s2 (s4 too old)
    assert list(tmp_path.glob("bills.before-*.json"))

    def rerun() -> list[httpx.Request]:
        fake = Fake()
        state = State.load(tmp_path / "state.json")
        backfill(
            client(fake, state),
            state,
            bills_path,
            tmp_path,
            since=date(2025, 1, 1),
            log=lambda s: None,
        )
        return fake.requests

    # the acts just added are acts I have seen: the next run looks them up, and only them
    assert sorted(str(r.url.params) for r in rerun()) == [
        "countryCode=GB&artistMbid=bbbbbbbb-0000-0000-0000-000000000002&p=1",  # Tin Foxes
        "countryCode=GB&artistName=Quiet+Pylons&p=1",
    ]
    assert rerun() == []


def test_a_dry_run_leaves_bills_json_alone(tmp_path: Path) -> None:
    bills_path = tmp_path / "bills.json"
    bills_path.write_text(json.dumps([bill()]))
    state = State.load(tmp_path / "state.json")
    lines: list[str] = []
    backfill(
        client(Fake(), state),
        state,
        bills_path,
        tmp_path,
        since=date(2025, 1, 1),
        dry_run=True,
        log=lines.append,
    )
    assert json.loads(bills_path.read_text()) == [bill()]
    assert any("+ Quiet Pylons, Tin Foxes" in line for line in lines)
    assert "dry run: bills.json not changed" in lines


def test_the_budget_stops_the_run_and_the_next_day_resumes(tmp_path: Path) -> None:
    bills_path = tmp_path / "bills.json"
    bills_path.write_text(json.dumps([bill()]))
    state = State.load(tmp_path / "state.json")
    result = backfill(
        client(Fake(), state, budget=1),
        state,
        bills_path,
        tmp_path,
        since=date(2025, 1, 1),
        log=lambda s: None,
    )
    assert result is None
    assert json.loads((tmp_path / "state.json").read_text())["calls"] == 1
    with pytest.raises(BudgetSpentError):
        client(Fake(), State.load(tmp_path / "state.json"), budget=1).search(artistName="x")


def test_the_key_travels_in_its_header_and_404_means_no_results() -> None:
    fake = Fake()
    c = client(fake, State(Path("/nonexistent/state.json"), {"artists": {}, "venue_days": {}}))
    assert c.search(artistName="Nobody")["setlist"] == []
    assert fake.requests[0].headers["x-api-key"] == "test-key"
    assert fake.requests[0].url.params["countryCode"] == "GB"


def test_found_round_trips_through_the_state() -> None:
    f = parse(page("artist-p1.json"))[0]
    assert Found.of(json.loads(json.dumps(f.row()))) == f


def test_setlist_fms_various_artists_placeholder_is_not_an_act() -> None:
    various = Found(
        "s9",
        "u",
        date(2026, 9, 20),
        "Various Artists",
        "89ad4ac3-39f7-470e-963a-56509c546377",
        "v1",
        "The Lantern Rooms",
        "London",
        0,
    )
    result = merge([bill()], [], {bill()["key"]: [various]})
    assert [a["name"] for a in result.bills[0]["acts"]] == ["Paper Lanterns"]

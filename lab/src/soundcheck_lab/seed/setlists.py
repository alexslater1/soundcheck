"""`soundcheck-lab backfill-setlists`: my gigs with their full bills.

For every act on my bills (`bills.json`) it pages through the act's UK setlists back to a month
(`--from`), then, for each of my own gigs, asks setlist.fm which acts played that venue that
day: the support acts my gig app never recorded. It writes only files:

- `bills.json`, my own gigs with the acts added (a dry run prints the changes instead). The
  seed then puts these gigs into the catalog through the API, as a fan's confirmed contribution.
- `.localdata/setlistfm/candidates.csv`, every other date found, for me to review. Never the
  catalog: setlist.fm's terms forbid a bulk copy.

Within setlist.fm's limits: 2 calls a second, and a daily budget below the key's 1,440 (the app's
own lookups share the key). Every answer is kept in `.localdata/setlistfm/state.json`, so a run
stopped by the budget resumes the next day where it stopped, and a second run costs no calls."""

from __future__ import annotations

import csv
import json
import time
from collections.abc import Callable, Iterable, Sequence
from dataclasses import asdict, dataclass, field
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Any

import httpx

from soundcheck_lab.seed.run import norm

BASE_URL = "https://api.setlist.fm/rest/1.0/"
SPACING_S = 0.55  # setlist.fm allows 2 calls a second
DAILY_BUDGET = 1000  # of the key's 1,440: the rest is left for the app's lookups
MAX_ACTS = 10  # POST /gigs and PATCH /gigs take at most ten acts
# setlist.fm's placeholder for a night of many acts (MusicBrainz's "Various Artists"): not an act
VARIOUS = "89ad4ac3-39f7-470e-963a-56509c546377"
Log = Callable[[str], None]


class BudgetSpentError(Exception):
    """Today's calls are used up; the run resumes tomorrow from the saved state."""


@dataclass(frozen=True)
class Found:
    """One setlist as setlist.fm lists it: one act at one venue on one day."""

    setlist_id: str
    url: str
    day: date
    artist: str
    mbid: str | None
    venue_id: str
    venue: str
    city: str
    songs: int

    def row(self) -> dict[str, Any]:
        return asdict(self) | {"day": self.day.isoformat()}

    @classmethod
    def of(cls, row: dict[str, Any]) -> Found:
        return cls(**(row | {"day": date.fromisoformat(row["day"])}))


def parse(body: dict[str, Any]) -> list[Found]:
    """A search page's setlists; entries without a date, artist or venue are skipped."""
    found = []
    for s in body.get("setlist", []):
        venue = s.get("venue") or {}
        artist = (s.get("artist") or {}).get("name", "").strip()
        try:
            day = datetime.strptime(s.get("eventDate", ""), "%d-%m-%Y").date()
        except ValueError:
            continue
        if not artist or not venue.get("id") or not venue.get("name"):
            continue
        songs = sum(len(part.get("song", [])) for part in (s.get("sets") or {}).get("set", []))
        found.append(
            Found(
                setlist_id=s.get("id", ""),
                url=s.get("url", ""),
                day=day,
                artist=artist,
                mbid=(s.get("artist") or {}).get("mbid") or None,
                venue_id=venue["id"],
                venue=venue["name"].strip(),
                city=((venue.get("city") or {}).get("name") or "").strip(),
                songs=songs,
            )
        )
    return found


@dataclass
class State:
    """Every answer so far and today's call count: `.localdata/setlistfm/state.json`."""

    path: Path
    data: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def load(cls, path: Path) -> State:
        data = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        data.setdefault("artists", {})
        data.setdefault("venue_days", {})
        return cls(path, data)

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data, indent=1, sort_keys=True), encoding="utf-8")
        tmp.replace(self.path)


class Client:
    """setlist.fm search at 2 calls a second, counting each call against the day's budget (UTC)."""

    def __init__(
        self,
        api_key: str,
        state: State,
        *,
        budget: int = DAILY_BUDGET,
        transport: httpx.BaseTransport | None = None,
        sleep: Callable[[float], None] = time.sleep,
        clock: Callable[[], float] = time.monotonic,
        today: Callable[[], date] = lambda: datetime.now(UTC).date(),
    ) -> None:
        self._http = httpx.Client(
            base_url=BASE_URL,
            transport=transport,
            timeout=15,
            headers={"x-api-key": api_key, "Accept": "application/json"},
        )
        self._state = state
        self._budget = budget
        self._sleep = sleep
        self._clock = clock
        self._today = today
        self._last = -1e9

    def close(self) -> None:
        self._http.close()

    def calls_today(self) -> int:
        day = self._state.data.get("day")
        return int(self._state.data.get("calls", 0)) if day == self._today().isoformat() else 0

    def search(self, **query: str | int) -> dict[str, Any]:
        """One `search/setlists` page; an empty page when setlist.fm answers 404 (no results)."""
        for attempt in range(5):
            if self.calls_today() >= self._budget:
                raise BudgetSpentError(f"{self._budget} setlist.fm calls used today")
            wait = self._last + SPACING_S - self._clock()
            if wait > 0:
                self._sleep(wait)
            self._last = self._clock()
            self._state.data.update(day=self._today().isoformat(), calls=self.calls_today() + 1)
            response = self._http.get("search/setlists", params={"countryCode": "GB", **query})
            if response.status_code == 404:
                return {"setlist": [], "total": 0, "page": query.get("p", 1), "itemsPerPage": 20}
            if response.status_code == 429 or response.status_code >= 500:
                self._sleep(2.0 * (attempt + 1))
                continue
            response.raise_for_status()
            return dict(response.json())
        raise RuntimeError(f"setlist.fm kept answering {response.status_code}")


# ── what to look up ──────────────────────────────────────────────────────────────────────────────
@dataclass(frozen=True)
class Act:
    name: str
    mbid: str | None

    @property
    def key(self) -> str:
        return self.mbid or f"name:{norm(self.name)}"


def acts_seen(bills: Sequence[dict[str, Any]], only: Iterable[str] = ()) -> list[Act]:
    """Every act on my bills, once (by MusicBrainz id, else by name); `only` narrows by name."""
    wanted = {norm(n) for n in only}
    acts: dict[str, Act] = {}
    for bill in bills:
        for a in bill["acts"]:
            act = Act(a["name"], a.get("mbid"))
            if not wanted or norm(act.name) in wanted:
                acts.setdefault(act.key, act)
    return sorted(acts.values(), key=lambda a: a.name.casefold())


def artist_setlists(client: Client, state: State, act: Act, since: date, log: Log) -> list[Found]:
    """The act's UK setlists from `since` on (newest first, so paging stops at the first older one).
    Kept in the state once complete: a re-run costs nothing."""
    saved = state.data["artists"].get(act.key)
    if saved and saved.get("since") == since.isoformat() and saved.get("done"):
        return [Found.of(r) for r in saved["setlists"]]
    query: dict[str, str | int] = {"artistMbid": act.mbid} if act.mbid else {"artistName": act.name}
    found: list[Found] = []
    page = 1
    while True:
        body = client.search(**query, p=page)
        items = parse(body)
        found.extend(f for f in items if f.day >= since)
        per_page = int(body.get("itemsPerPage") or 20)
        last_page = page * per_page >= int(body.get("total") or 0)
        if not items or last_page or min(f.day for f in items) < since:
            break
        page += 1
    if not act.mbid:  # a name search also finds other acts with the name in theirs
        found = [f for f in found if norm(f.artist) == norm(act.name)]
    state.data["artists"][act.key] = {
        "name": act.name,
        "since": since.isoformat(),
        "done": True,
        "setlists": [f.row() for f in found],
    }
    state.save()
    log(f"  {act.name}: {len(found)} UK setlists since {since:%b %Y} ({page} page(s))")
    return found


def venue_day(client: Client, state: State, venue_id: str, day: date) -> list[Found]:
    """Every act setlist.fm lists at one venue on one day: the night's full bill."""
    key = f"{venue_id}|{day.isoformat()}"
    if key not in state.data["venue_days"]:
        body = client.search(venueId=venue_id, date=day.strftime("%d-%m-%Y"))
        state.data["venue_days"][key] = [f.row() for f in parse(body)]
        state.save()
    return [Found.of(r) for r in state.data["venue_days"][key]]


# ── matching to my gigs ──────────────────────────────────────────────────────────────────────────
def own_gig(found: Found, bills: Sequence[dict[str, Any]]) -> dict[str, Any] | None:
    """Which of my bills this setlist is from: same day, and the act is on the bill or the venue
    is the bill's (two gigs one night: NewDad, then Cardinals' late show at another venue)."""
    tonight = [b for b in bills if b["date"] == found.day.isoformat()]
    for bill in tonight:
        on_bill = any(
            (a.get("mbid") and a["mbid"] == found.mbid) or norm(a["name"]) == norm(found.artist)
            for a in bill["acts"]
        )
        if on_bill or norm(bill["venue"]) == norm(found.venue):
            return bill
    return None


def gig_venues(bills: Sequence[dict[str, Any]], by_artist: Iterable[Found]) -> dict[str, str]:
    """Each of my gigs → setlist.fm's venue id, from the setlists of its acts that night. A
    setlist at a venue of the bill's name wins over one that only shares an act (setlist.fm may
    name the venue differently: "O2 Forum Kentish Town" / "Kentish Town Forum")."""
    venues: dict[str, str] = {}
    exact: set[str] = set()
    for f in by_artist:
        bill = own_gig(f, bills)
        if bill is None or bill["key"] in exact:
            continue
        venues[bill["key"]] = f.venue_id
        if norm(f.venue) == norm(bill["venue"]):
            exact.add(bill["key"])
    return venues


@dataclass
class Result:
    bills: list[dict[str, Any]]
    changes: list[str]
    candidates: list[Found]
    added_acts: int


def merge(
    bills: Sequence[dict[str, Any]],
    by_artist: Sequence[Found],
    full_bills: dict[str, list[Found]],
) -> Result:
    """My bills with the acts setlist.fm lists at the same venue that night added (supports go
    before the existing acts: the headliner stays last), the setlist link filled in where missing,
    and every setlist not from one of my gigs as a candidate."""
    out = json.loads(json.dumps(list(bills)))  # a deep copy
    changes: list[str] = []
    added_acts = 0
    for bill in out:
        night = full_bills.get(bill["key"], [])
        if not night:
            continue
        have = {norm(a["name"]) for a in bill["acts"]} | {
            a["mbid"] for a in bill["acts"] if a.get("mbid")
        }
        new: list[dict[str, Any]] = []
        for f in sorted(night, key=lambda f: f.artist.casefold()):
            if norm(f.artist) in have or (f.mbid and f.mbid in have):
                continue
            if f.mbid == VARIOUS or norm(f.artist) == "variousartists":
                continue
            new.append({"name": f.artist, "mbid": f.mbid, "songs": f.songs})
            have |= {norm(f.artist), *([f.mbid] if f.mbid else [])}
        room = MAX_ACTS - len(bill["acts"])
        if new and room > 0:
            bill["acts"] = [*new[:room], *bill["acts"]]
            added_acts += len(new[:room])
            names = ", ".join(a["name"] for a in new[:room])
            changes.append(f"{bill['key']}: + {names}")
        if len(new) > room:
            dropped = ", ".join(a["name"] for a in new[max(room, 0) :])
            changes.append(f"{bill['key']}: not added (ten acts at most): {dropped}")
        if not bill.get("foundOn"):
            headliner = bill["acts"][-1]
            match = next((f for f in night if norm(f.artist) == norm(headliner["name"])), None)
            if match:
                bill["foundOn"] = {
                    "externalId": match.setlist_id,
                    "url": match.url,
                    "artistMbid": match.mbid,
                }
                changes.append(f"{bill['key']}: setlist link {match.url}")
    candidates = sorted(
        {f.setlist_id: f for f in by_artist if own_gig(f, bills) is None}.values(),
        key=lambda f: (f.day, f.artist.casefold()),
    )
    return Result(out, changes, candidates, added_acts)


def write_candidates(path: Path, candidates: Sequence[Found]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow(["date", "artist", "venue", "city", "songs", "setlist"])
        for f in candidates:
            writer.writerow([f.day.isoformat(), f.artist, f.venue, f.city, f.songs, f.url])


# ── the whole run ────────────────────────────────────────────────────────────────────────────────
def backfill(
    client: Client,
    state: State,
    bills_path: Path,
    out_dir: Path,
    *,
    since: date,
    only: Sequence[str] = (),
    dry_run: bool = False,
    log: Log = print,
) -> Result | None:
    """Looks everything up (resuming from the state), then writes bills.json and the candidates.
    Returns None when the day's budget ran out first: run it again tomorrow."""
    bills: list[dict[str, Any]] = json.loads(bills_path.read_text(encoding="utf-8"))
    acts = acts_seen(bills, only)
    log(f"{len(acts)} acts from {len(bills)} gigs; {client.calls_today()} calls used today")
    by_artist: list[Found] = []
    full_bills: dict[str, list[Found]] = {}
    try:
        for act in acts:
            by_artist.extend(artist_setlists(client, state, act, since, log))
        venues = gig_venues(bills, by_artist)
        for bill in bills:
            if bill.get("festival") or bill["key"] not in venues:
                continue  # a festival's day lists every stage: my own picks stand
            if date.fromisoformat(bill["date"]) < since:
                continue
            full_bills[bill["key"]] = venue_day(
                client, state, venues[bill["key"]], date.fromisoformat(bill["date"])
            )
    except BudgetSpentError as spent:
        state.save()
        log(f"stopped: {spent}. Run it again tomorrow (UTC); it resumes where it stopped.")
        return None
    finally:
        state.save()
    result = merge(bills, by_artist, full_bills)
    missing = [b["key"] for b in bills if b["key"] not in venues and not b.get("festival")]
    log(
        f"{len(full_bills)} of my gigs found on setlist.fm; "
        f"{result.added_acts} acts to add; {len(result.candidates)} other dates for review"
    )
    if missing:
        log(f"not found on setlist.fm ({len(missing)}): {'; '.join(missing)}")
    for line in result.changes:
        log(f"  {line}")
    write_candidates(out_dir / "candidates.csv", result.candidates)
    log(f"candidates: {out_dir / 'candidates.csv'} (review only; never the catalog)")
    if dry_run:
        log("dry run: bills.json not changed")
    elif result.changes:
        stamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
        backup = out_dir / f"bills.before-{stamp}.json"
        backup.write_text(bills_path.read_text(encoding="utf-8"), encoding="utf-8")
        bills_path.write_text(
            json.dumps(result.bills, indent=1, ensure_ascii=False) + "\n", encoding="utf-8"
        )
        log(f"bills.json updated (the old one: {backup})")
    return result

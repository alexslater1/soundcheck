"""`soundcheck-lab seed-demo`: the seeding steps in order. Every step can run again without
duplicating.

1. accounts   — the 30 personas and the demo fan: profile, home city, genres, avatar, onboarding
   done; the demo fan's gig stats shared
2. follows    — personas follow each other and popular artists of their genres
3. gigs       — my real gigs (bills.json) added through POST /gigs; their attendees marked
   "Went"; the demo fan "Went" to all of them (my real history), then follows the acts it has
   seen most
4. real posts — my clips matched to those gigs, a few per gig, posted by the gig's attendees
5. filler     — synthetic clips (clearly labelled) for personas whose genres my gigs don't cover
6. social     — likes and comments; engagement events are in `engage.py`"""

from __future__ import annotations

import csv
import hashlib
import json
import os
import re
import subprocess
import time
from collections import Counter
from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any

from soundcheck_lab.seed import encode, realclips
from soundcheck_lab.seed.api import Api, ApiError
from soundcheck_lab.seed.avatar import avatar_png
from soundcheck_lab.seed.personas import ARCHIVE, DEMO, PERSONAS, Persona, check_password_for
from soundcheck_lab.seed.realclips import Clip, Gig

Log = Callable[[str], None]
GIGS_PER_DAY = 20  # the API's limit on gigs one account adds per day (UserSubmissionService)
DEMO_TOP_ACTS = 15  # the demo fan follows the acts it has seen most
COMMENTS = (
    "What a night",
    "Unreal set",
    "This one gave me chills",
    "Wish I'd been there",
    "Best support act I've seen in ages",
    "The crowd for this was something else",
    "Need this on repeat",
    "Was stood right behind you I think",
    "Sound was perfect in there",
    "Going next time, no excuses",
)


@dataclass
class Settings:
    root: Path
    api_url: str = "http://localhost:8088"
    personas: int = len(PERSONAS)
    clips_per_gig: int = 4
    max_clip_s: float = 45.0
    synthetic_per_persona: int = 3
    real: bool = True
    gig_limit: int | None = None  # only the first N gigs (a small run)
    month: str | None = None  # only my gigs of this month (YYYY-MM): one batch
    max_gb: float | None = None  # at most this much of my footage (source files) a run
    archive: bool = True  # the private @alex_archive account with all of my clips
    archive_followers: tuple[str, ...] = ()  # my real handles: their follow requests accepted
    archive_max_clip_s: float = 180.0  # the API's limit is 3 min

    @property
    def clips_dir(self) -> Path:
        return self.root / "fixtures" / "clips" / "mine"

    @property
    def demo_clips_dir(self) -> Path:
        return self.root / "fixtures" / "clips" / "demo"

    @property
    def work_dir(self) -> Path:
        return self.root / ".localdata" / "seed"


@dataclass
class State:
    """What has been done, so a re-run skips it: .localdata/seed/state.json (gitignored)."""

    path: Path
    data: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def load(cls, path: Path) -> State:
        data = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        return cls(path, data)

    def section(self, name: str) -> dict[str, Any]:
        return self.data.setdefault(name, {})  # type: ignore[no-any-return]

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data, indent=1, sort_keys=True), encoding="utf-8")
        tmp.replace(self.path)


def stable(text: str) -> int:
    """A hash that is the same on every run (Python's hash() is salted per process)."""
    return int.from_bytes(hashlib.sha256(text.encode()).digest()[:8], "big")


def norm(name: str) -> str:
    name = name.lower().replace("&", "and")
    name = re.sub(r"^the ", "", name)
    return re.sub(r"[^a-z0-9]", "", name)


def words(name: str) -> list[str]:
    """The name's words in lower case, without a leading "the" or apostrophes."""
    text = re.sub(r"['’.]", "", name.lower().replace("&", " and "))
    found = re.findall(r"[a-z0-9]+", text)
    return found[1:] if found[:1] == ["the"] else found


# ── 1. accounts ──────────────────────────────────────────────────────────────────────────────────
def accounts(
    api: Api, people: Sequence[Persona], state: State, work: Path, log: Log
) -> dict[str, int]:
    genres = {g["name"]: g["id"] for g in api.get(people[0], "/api/v1/genres")}
    cities: dict[str, int] = {}
    done = state.section("profiles")
    for persona in people:
        me = api.get(persona, "/api/v1/me")
        if persona is DEMO and me.get("profileVisibility") == "PRIVATE":
            # The demo profile shows its stats. Profiles are public by default,
            # so this only undoes a private switch made by hand; checked on every run.
            api.patch(persona, "/api/v1/me", {"profileVisibility": "PUBLIC"})
            log("demo fan: profile made public again")
        if done.get(persona.handle) == me["id"] and me["onboarded"] and me.get("avatarUrl"):
            continue
        if persona.city not in cities:
            hits = api.get(persona, "/api/v1/cities", q=persona.city)
            cities[persona.city] = next(c["id"] for c in hits if c["name"] == persona.city)
        api.patch(
            persona,
            "/api/v1/me",
            {
                "homeCityId": cities[persona.city],
                "bio": persona.bio,
                "displayName": persona.display_name,
            },
        )
        weights = (1.0, 0.7, 0.5, 0.4)
        api.put(
            persona,
            "/api/v1/me/genres",
            {
                "genres": [
                    {"genreId": genres[g], "weight": w}
                    for g, w in zip(persona.genres, weights, strict=False)
                ]
            },
        )
        if not me.get("avatarUrl"):
            png = work / "avatars" / f"{persona.handle}.png"
            png.parent.mkdir(parents=True, exist_ok=True)
            png.write_bytes(avatar_png(persona.handle))
            media = api.upload(persona, png, "image/png", kind="IMAGE")
            if wait_ready(api, persona, media.media_id, timeout_s=120):
                api.patch(persona, "/api/v1/me", {"avatarMediaId": media.media_id})
        if not me["onboarded"]:
            api.post(persona, "/api/v1/me/onboarding/complete")
        done[persona.handle] = me["id"]
        state.save()
        log(f"account ready: @{persona.handle} ({persona.city}; {', '.join(persona.genres)})")
    return genres


def wait_ready(api: Api, persona: Persona, media_id: str, timeout_s: float) -> bool:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        status = api.get(persona, f"/api/v1/media/{media_id}")["status"]
        if status == "READY":
            return True
        if status in ("FAILED", "REJECTED", "DELETED"):
            return False
        time.sleep(2)
    return False


# ── 2. follows ───────────────────────────────────────────────────────────────────────────────────
def follows(
    api: Api, people: Sequence[Persona], genres: dict[str, int], state: State, log: Log
) -> None:
    done = state.section("follows")
    for persona in people:
        if done.get(persona.handle) is True:  # marked True before 304c402 kept ids
            done[persona.handle] = api.ids[persona.handle]
        if done.get(persona.handle) == api.ids.get(persona.handle):
            continue
        others = [p for p in people if p is not persona]
        same = sorted(
            (p for p in others if set(p.genres) & set(persona.genres)),
            key=lambda p: stable(persona.handle + p.handle),
        )
        rest = sorted(
            (p for p in others if p not in same), key=lambda p: stable(p.handle + persona.handle)
        )
        for other in [*same[:7], *rest[:2]]:
            api.put(persona, f"/api/v1/users/{api.ids[other.handle]}/follow")
        if persona is not DEMO:  # the demo fan follows the acts it has seen most (demo_follows)
            for artist in popular_artists(api, persona, genres):
                api.put(persona, f"/api/v1/artists/{artist['id']}/follow")
        done[persona.handle] = api.ids[persona.handle]  # by id: a reset database is seeded again
        state.save()
    log(f"follows: {len(people)} accounts follow 9 people; the personas 5 artists each")


def popular_artists(
    api: Api, persona: Persona, genres: dict[str, int], limit: int = 5
) -> list[dict[str, Any]]:
    """The most popular artists of the persona's first two genres (GET /artists?popular=true)."""
    top = ",".join(str(genres[g]) for g in persona.genres[:2])
    items = api.get(persona, "/api/v1/artists", popular="true", genreIds=top, limit=limit)["items"]
    return list(items)


def most_seen(acts: Sequence[dict[str, Any]], top: int = DEMO_TOP_ACTS) -> list[dict[str, Any]]:
    """The acts seen at the most gigs (GigStats.acts: one row per act, `times`), ties by name."""
    ranked = sorted(acts, key=lambda a: (-int(a["times"]), str(a["name"]).casefold(), str(a["id"])))
    return ranked[:top]


def demo_follows(api: Api, genres: dict[str, int], state: State, log: Log) -> None:
    """The demo fan follows the acts it has seen most, read from its own stats (the gigs it
    marked Went: my real history), instead of popular artists of its genres. Without any gigs
    (`--no-real`) it follows those popular artists, as the personas do.

    Runs again when the number of its gigs changes. On a database seeded earlier (the demo fan
    followed five popular artists in the follows step) those follows are undone once, best effort:
    the popular list may have moved since, and an artist that is also among the most seen stays."""
    done = state.section("demo_follows")
    user = str(api.get(DEMO, "/api/v1/me")["id"])
    stats = api.get(DEMO, f"/api/v1/users/{user}/stats", top=100)
    gigs = int(stats["totals"]["gigs"])
    if done.get("user") == user and done.get("gigs") == gigs:
        return
    acts = most_seen(stats["acts"])
    chosen = acts or popular_artists(api, DEMO, genres)
    keep = {str(a["id"]) for a in chosen}
    undone: list[str] = []
    if acts and done.get("user") != user and state.section("follows").get(DEMO.handle) == user:
        for artist in popular_artists(api, DEMO, genres):
            if artist.get("isFollowing") and str(artist["id"]) not in keep:
                api.request(DEMO, "DELETE", f"/api/v1/artists/{artist['id']}/follow")
                undone.append(str(artist["name"]))
    for artist in chosen:
        api.put(DEMO, f"/api/v1/artists/{artist['id']}/follow")
    done.update(user=user, gigs=gigs, artists=sorted(keep))
    state.save()
    what = "acts it has seen most" if acts else "popular artists of its genres (no gigs yet)"
    log(
        f"demo fan follows {len(chosen)} {what}"
        + (f"; unfollowed the earlier {', '.join(undone)}" if undone else "")
    )


# ── 3. real gigs ─────────────────────────────────────────────────────────────────────────────────
def attendees(gig: Gig, indie: Sequence[Persona]) -> list[Persona]:
    """3–5 personas who were at this gig: locals first, then Londoners (Brighton gigs: the two
    Brighton personas, then London); a festival draws from everyone."""

    def by_gig(group: Iterable[Persona]) -> list[Persona]:
        return sorted(group, key=lambda p: stable(gig.key + p.handle))

    if gig.festival:
        ranked = by_gig(indie)
    else:
        local = [p for p in indie if p.city == gig.city]
        ranked = by_gig(local) + by_gig(p for p in indie if p.city == "London" and p not in local)
    return ranked[: 3 + stable(gig.key) % 3]


def centroid(clips: Sequence[Clip]) -> tuple[float, float] | None:
    """Where the clips were filmed (the mean of their GPS points), if any has one."""
    located = [(c.lat, c.lng) for c in clips if c.lat is not None and c.lng is not None]
    if not located:
        return None
    return (sum(p[0] for p in located) / len(located), sum(p[1] for p in located) / len(located))


def choose_venue(
    hits: Sequence[dict[str, Any]], name: str, city: str, filmed_at: tuple[float, float] | None
) -> str | None:
    """Which of GET /venues' hits is the gig's venue, or None for a new one.

    The name decides; GPS only confirms, rules out and ranks. A hit is the venue when its name is
    ours ("The Old Blue Last" / "Old Blue Last"), or starts with all of our words ("EartH" / "EartH
    Theatre", "The Dome" / "The Dome London"), never the reverse ("Finsbury Park" is not the pub
    "The Finsbury" next to it). A short name (under five letters: "O2", "Dome") only counts that
    way when the clips were filmed within 150 m of it ("O2" must not become "O2 Academy2
    Islington"). With GPS, a hit more than 2 km away is not it. Exact names win, then the
    nearest."""
    want = norm(name)
    want_words = words(name)

    def metres(hit: dict[str, Any]) -> float | None:
        loc = hit.get("location")
        if not loc or filmed_at is None:
            return None  # unknown: neither near nor far
        return 1000 * realclips.km(filmed_at[0], filmed_at[1], loc["lat"], loc["lng"])

    def related(hit: dict[str, Any]) -> bool:
        if norm(hit["name"]) == want:
            return True
        starts = words(hit["name"])[: len(want_words)] == want_words
        distance = metres(hit)
        return starts and (len(want) >= 5 or (distance is not None and distance <= 150))

    candidates = [
        h
        for h in hits
        if (h.get("city") or city) == city and related(h) and (metres(h) or 0) <= 2000
    ]
    if not candidates:
        return None
    best = min(
        candidates, key=lambda h: (norm(h["name"]) != want, metres(h) is None, metres(h) or 0)
    )
    return str(best["id"])


def venue_for(
    api: Api, who: Persona, gig: Gig, clips: Sequence[Clip], known: dict[str, Any]
) -> dict[str, Any]:
    """The catalog's venue when its name decides (choose_venue), else the one a filmed gig at the
    same venue found ({@code known}, by name and city: old-phone gigs have no GPS, and a short name
    like "Dome" needs GPS to match), else a new one placed where the clips were filmed.

    The catalog comes first because a remembered choice can be wrong and outlive its fix: the first
    rules sent "The O2" to O2 Academy2 Islington, and the cache kept sending gigs there after the
    rules were fixed and the gigs were moved."""
    filmed_at = centroid(clips)
    venue_id = choose_venue(
        api.get(who, "/api/v1/venues", q=gig.venue), gig.venue, gig.city, filmed_at
    )
    if venue_id:
        return {"venueId": venue_id}
    key = f"{norm(gig.venue)}|{gig.city}"
    if key in known:
        return {"venueId": known[key]}
    if filmed_at:
        lat, lng = filmed_at
    elif gig.lat is not None and gig.lng is not None:
        lat, lng = gig.lat, gig.lng
    else:
        city = api.get(who, "/api/v1/cities", q=gig.city)[0]
        lat, lng = city["lat"], city["lng"]
    return {"newVenue": {"name": gig.venue[:120], "cityName": gig.city, "lat": lat, "lng": lng}}


def add_gigs(
    api: Api,
    gigs: Sequence[Gig],
    indie: Sequence[Persona],
    matched: dict[str, list[Clip]],
    state: State,
    log: Log,
) -> dict[str, dict[str, Any]]:
    added = state.section("gigs")
    quota = state.section("gig_quota")  # handle → {"date": …, "count": …}
    venues = state.section("venues")  # "name|city" → venue id
    today = date.today().isoformat()
    summaries: dict[str, dict[str, Any]] = {}
    # gigs with GPS-tagged clips first: their venues are found by location, then reused by name
    for gig in sorted(gigs, key=lambda g: (centroid(matched.get(g.key, [])) is None, g.night)):
        remembered = added.get(gig.key)
        existing = _gig_if_exists(api, remembered["id"]) if remembered else None
        if existing is not None:
            # Added by an earlier run: kept as the catalog has it now, wherever it was moved since
            # (six were moved). Adding it again could put a second copy at the old venue.
            summaries[gig.key] = existing
            continue
        people = attendees(gig, indie)
        body: dict[str, Any] = {"artistNames": gig.billing[:10], "date": gig.night.isoformat()}
        if gig.festival:
            body |= {"title": gig.title, "time": "13:00"}
        if gig.found_on and (gig.found_on.get("url") or "").startswith("https://www.setlist.fm/"):
            body["foundOn"] = {
                "source": "SETLISTFM",
                "externalId": gig.found_on["externalId"],
                "url": gig.found_on["url"],
                "artistMbid": gig.found_on.get("artistMbid"),
            }
        creator = next((p for p in people if _quota_left(quota, p.handle, today)), None) or next(
            p for p in indie if _quota_left(quota, p.handle, today)
        )
        body |= venue_for(api, creator, gig, matched.get(gig.key, []), venues)
        result = api.post(creator, "/api/v1/gigs", body)
        summary = result["item"]
        if result["created"]:
            q = quota.setdefault(creator.handle, {"date": today, "count": 0})
            q.update(date=today, count=(q["count"] if q["date"] == today else 0) + 1)
            # remembered only for gigs added here: a gig found already may sit at a venue someone
            # else chose (or got wrong), and that must not spread to the other gigs there
            venues.setdefault(f"{norm(gig.venue)}|{gig.city}", summary["venue"]["id"])
        summaries[gig.key] = summary
        for persona in [*people, DEMO]:
            api.put(persona, f"/api/v1/gigs/{summary['id']}/attendance", {"status": "WENT"})
        added[gig.key] = {"id": summary["id"], "attendees": [p.handle for p in people]}
        state.save()
        log(
            f"gig {'added' if result['created'] else 'found'}: {gig.night} {summary['title']} @ "
            f"{summary['venue']['name']} ({len(people)} personas + demo went)"
        )
    return summaries


def _gig_if_exists(api: Api, gig_id: str) -> dict[str, Any] | None:
    """A gig an earlier run added, as the catalog has it now, or None when it is gone (a reset
    database)."""
    try:
        return dict(api.get(DEMO, f"/api/v1/gigs/{gig_id}")["gig"])
    except ApiError as error:
        if error.status == 404:
            return None
        raise


def _quota_left(quota: dict[str, Any], handle: str, today: str) -> bool:
    q = quota.get(handle)
    return q is None or q["date"] != today or q["count"] < GIGS_PER_DAY - 1


# ── 4. real clips ────────────────────────────────────────────────────────────────────────────────
def load_clips(settings: Settings, state: State, log: Log) -> list[Clip]:
    probes = state.section("probes")
    phone = []
    for path in sorted(settings.clips_dir.glob("PXL_*.mp4")):
        if path.name not in probes:
            probes[path.name] = realclips.probe(path)
        phone.append(realclips.phone_clip(path, probes[path.name]))
    state.save()
    old = realclips.old_phone_clips(settings.clips_dir / "transferred")
    clips = realclips.latest_edits([*phone, *old])
    log(
        f"clips: {len(phone)} from the phone, {len(old)} from the old phone (Snapchat ignored); "
        f"{len(clips)} after keeping only the latest edit of each"
    )
    return clips


def caption(gig: Gig, acts: Sequence[str], clip: Clip) -> str:
    venue, when = gig.venue, gig.night.strftime("%-d %b %Y")
    if gig.festival:
        options = [f"{gig.title}. What a day", f"{gig.title} 🎪", f"Festival season: {gig.title}"]
    elif len(acts) == 1:
        act = acts[0]
        options = [
            f"{act} at {venue}",
            f"{act} live, {when}",
            f"{act}. Unreal.",
            f"Front row for {act}",
            f"{venue}, {when}. Still buzzing.",
            f"{act} 🔥 {venue}",
        ]
    else:
        options = [
            f"{gig.headliner.name} at {venue}, {when}",
            f"{venue}, {when}. What a bill.",
            f"{gig.headliner.name} night at {venue}",
        ]
    return options[stable(clip.name) % len(options)]


def post_groups(chosen: Sequence[Clip]) -> list[list[Clip]]:
    """J5: a persona's post of a gig carries all its clips of the gig. Up to three chosen clips
    are one persona's post; four or more are two personas' posts (the first and second half of
    the night), so a gig still has more than one voice."""
    if len(chosen) < 4:
        return [list(chosen)] if chosen else []
    half = len(chosen) // 2
    return [list(chosen[:half]), list(chosen[half:])]


@dataclass
class Batch:
    """How much of my footage one run may upload (a month, or at most N GB, at a time):
    the source files' bytes, each clip counted once. The first clip always goes, even if larger."""

    limit_bytes: int | None = None
    used: int = 0
    seen: set[str] = field(default_factory=set)
    full: bool = False

    def admit(self, clip: Clip) -> bool:
        if clip.name in self.seen:
            return True
        size = clip.path.stat().st_size
        if self.limit_bytes is not None and self.seen and self.used + size > self.limit_bytes:
            self.full = True
            return False
        self.seen.add(clip.name)
        self.used += size
        return True


def acts_by_clip(
    gig: Gig, clips: Sequence[Clip], overrides: dict[str, str | None]
) -> tuple[dict[str, str | None], str]:
    chosen, rule = realclips.clip_acts(gig, clips)
    fixed = realclips.override_acts(gig, chosen, overrides)
    return fixed, rule if fixed == chosen else f"{rule}; corrected in clip_acts.csv"


def lineup_ids(api: Api, who: Persona, gig_id: str) -> dict[str, str]:
    """The gig's bill as the catalog has it now: normalised act name → artist id."""
    detail = api.get(who, f"/api/v1/gigs/{gig_id}")
    return {norm(a["name"]): str(a["id"]) for a in detail["lineup"]}


def upload_clips(
    api: Api,
    who: Persona,
    clips: Sequence[Clip],
    acts: dict[str, str | None],
    ids: dict[str, str],
    out_dir: Path,
    max_s: float,
    log: Log,
) -> list[dict[str, Any]]:
    """Encodes (cached in {@code out_dir}) and uploads each clip; the clips as POST /posts wants
    them, each with its act when the catalog's bill has it."""
    out = []
    for clip in clips:
        start, length = realclips.segment(clip.duration_s, max_s)
        encoded = out_dir / f"{clip.path.stem}.mp4"
        encode.encode(clip.path, encoded, start, length, log)
        media = api.upload(who, encoded, "video/mp4", meta={"durationMs": int(length * 1000)})
        act = acts.get(clip.name)
        out.append({"mediaId": media.media_id, "artistId": ids.get(norm(act)) if act else None})
    return out


def real_posts(
    api: Api,
    settings: Settings,
    gigs: Sequence[Gig],
    indie: Sequence[Persona],
    summaries: dict[str, dict[str, Any]],
    matched: dict[str, list[Clip]],
    skipped: Sequence[realclips.Skipped],
    state: State,
    batch: Batch,
    log: Log,
) -> None:
    """The personas' posts of my clips: up to {@code clips_per_gig} a gig, one post per persona
    with all its clips of the gig (J5), each clip tagged with its act (J17). A post from before J5
    (one clip each, the "posts" section) is deleted once its clip is in a J5 post."""
    old = state.section("posts")
    done = state.section("gig_posts")
    by_handle = {p.handle: p for p in [*indie, DEMO]}
    overrides = realclips.load_overrides(settings.clips_dir / "clip_acts.csv")
    manifest: list[dict[str, str]] = [
        {"file": s.clip.name, "night": str(s.clip.night), "status": "skipped", "reason": s.reason}
        for s in skipped
    ]
    posted = retired = 0
    for gig in gigs:
        clips = matched.get(gig.key, [])
        if not clips or gig.key not in summaries:
            continue
        acts, rule = acts_by_clip(gig, clips, overrides)
        people = attendees(gig, indie)
        chosen = realclips.pick(clips, settings.clips_per_gig)
        for clip in clips:
            if clip not in chosen:
                manifest.append(
                    {
                        "file": clip.name,
                        "night": str(clip.night),
                        "gig": gig.key,
                        "status": "not posted",
                        "reason": f"{settings.clips_per_gig} clips per gig",
                    }
                )
        gig_id = summaries[gig.key]["id"]
        for i, group in enumerate(post_groups(chosen)):
            poster = DEMO if i == 0 and stable(gig.key) % 3 == 0 else people[i % len(people)]
            key = f"{gig.key}#{i}"
            names = [c.name for c in group]
            rows = [
                {
                    "file": c.name,
                    "night": str(c.night),
                    "gig": gig.key,
                    "gig_id": gig_id,
                    "acts": acts.get(c.name) or "",
                    "tag_rule": rule,
                    "persona": poster.handle,
                }
                for c in group
            ]
            mine = done.get(key)
            author = by_handle.get(mine["persona"], poster) if mine else poster
            if mine and mine["clips"] == names and _post_exists(api, author, mine["post_id"]):
                manifest.extend(r | {"status": "posted", "post_id": mine["post_id"]} for r in rows)
                continue
            if not all(batch.admit(c) for c in group):
                manifest.extend(r | {"status": "next batch"} for r in rows)
                continue
            try:
                ids = lineup_ids(api, poster, gig_id)
                body: dict[str, Any] = {
                    "clips": upload_clips(
                        api,
                        poster,
                        group,
                        acts,
                        ids,
                        settings.work_dir / "encoded",
                        settings.max_clip_s,
                        log,
                    ),
                    "gigId": gig_id,
                    "caption": caption(gig, sorted({a for a in acts.values() if a}), group[0]),
                }
                tagged = sorted(
                    {ids[norm(a)] for c in group if (a := acts.get(c.name)) and norm(a) in ids}
                )
                if tagged:
                    body["artistIds"] = tagged
                post = api.post(poster, "/api/v1/posts", body)
            except (ApiError, OSError, subprocess.CalledProcessError) as error:
                log(f"  failed {gig.key} #{i}: {error}")
                manifest.extend(r | {"status": "failed", "reason": str(error)[:200]} for r in rows)
                continue
            done[key] = {"post_id": post["id"], "persona": poster.handle, "clips": names}
            state.save()
            posted += 1
            manifest.extend(r | {"status": "posted", "post_id": post["id"]} for r in rows)
            log(f"  @{poster.handle}: {gig.night} {gig.headliner.name}, {len(group)} clip(s)")
            for name in names:  # the one-clip posts these clips had before multi-clip posts
                before = old.pop(name, None)
                if before and before["post_id"] != post["id"]:
                    who = by_handle.get(before["persona"])
                    if who is not None:
                        _delete_post(api, who, before["post_id"])
                        retired += 1
            state.save()
    log(f"real clips: {posted} persona posts made; {retired} one-clip posts from before J5 removed")
    merge_manifest(settings.clips_dir / "manifest.csv", manifest, MANIFEST_FIELDS)


def _delete_post(api: Api, persona: Persona, post_id: str) -> None:
    try:
        api.request(persona, "DELETE", f"/api/v1/posts/{post_id}")
    except ApiError as error:
        if error.status != 404:
            raise


def _post_exists(api: Api, persona: Persona, post_id: str) -> bool:
    """Whether a remembered post still stands. Gone (a reset database) or deleted: post again.
    FAILED (its clip could not be transcoded, e.g. LocalStack refusing writes): delete it and post
    again, since a failed post never recovers on its own."""
    try:
        status = str(api.get(persona, f"/api/v1/posts/{post_id}")["status"])
    except ApiError as error:
        if error.status == 404:
            return False
        raise
    if status == "FAILED":
        api.request(persona, "DELETE", f"/api/v1/posts/{post_id}")
        return False
    return status != "DELETED"


MANIFEST_FIELDS = [
    "file",
    "night",
    "gig",
    "gig_id",
    "acts",
    "tag_rule",
    "persona",
    "status",
    "post_id",
    "reason",
]
ARCHIVE_FIELDS = ["file", "night", "gig", "gig_id", "act", "tag_rule", "status", "post_id"]


def merge_manifest(path: Path, rows: Iterable[dict[str, str]], fields: Sequence[str]) -> None:
    """Writes this run's rows over the earlier ones (by file), keeping the rows of clips this run
    did not touch: a run of one month leaves the other months' progress in place."""
    merged: dict[str, dict[str, str]] = {}
    if path.exists():
        with path.open(encoding="utf-8") as fh:
            merged = {r["file"]: r for r in csv.DictReader(fh)}
    merged |= {r["file"]: r for r in rows}
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(fields))
        writer.writeheader()
        for row in sorted(merged.values(), key=lambda r: (r.get("night", ""), r["file"])):
            writer.writerow({k: row.get(k, "") for k in fields})


# ── 4b. the archive account ──────────────────────────────────────────────────────────────────────
def archive(
    api: Api,
    settings: Settings,
    gigs: Sequence[Gig],
    summaries: dict[str, dict[str, Any]],
    matched: dict[str, list[Clip]],
    state: State,
    batch: Batch,
    log: Log,
) -> None:
    """@alex_archive, private, Went to every one of my gigs with its full bill (acts
    added to the catalog's bill where missing; acts seen default to the whole bill), and
    one post per gig with all of the gig's clips, each tagged with its act."""
    section = state.section("archive")
    me = api.get(ARCHIVE, "/api/v1/me")
    if section.get("user") != me["id"] or isinstance(
        next(iter(section.get("posts", {}).values()), []), dict
    ):
        # a new account, a reset database, or the first shape (one post a gig) that never posted
        section.clear()
        section.update(user=me["id"], gigs={}, posts={})
    if me.get("profileVisibility") != "PRIVATE":
        api.patch(ARCHIVE, "/api/v1/me", {"profileVisibility": "PRIVATE", "bio": ARCHIVE.bio})
    if not me["onboarded"]:
        api.post(ARCHIVE, "/api/v1/me/onboarding/complete")
    went, synced = 0, 0
    for gig in gigs:
        summary = summaries.get(gig.key)
        if summary is None or section["gigs"].get(gig.key) == summary["id"]:
            continue
        api.put(ARCHIVE, f"/api/v1/gigs/{summary['id']}/attendance", {"status": "WENT"})
        have = lineup_ids(api, ARCHIVE, summary["id"])
        missing = [a.name for a in gig.acts if norm(a.name) not in have]
        if missing:  # anyone who went may add acts; the bill comes from setlist.fm
            try:
                api.patch(
                    ARCHIVE, f"/api/v1/gigs/{summary['id']}", {"addArtistNames": missing[:10]}
                )
                synced += 1
            except ApiError as error:
                log(f"  {gig.key}: could not add {', '.join(missing)}: {error}")
        section["gigs"][gig.key] = summary["id"]
        state.save()
        went += 1
    log(
        f"archive: Went to {went} more gigs ({synced} bills completed); "
        f"{len(section['gigs'])} in all"
    )
    archive_posts(api, settings, gigs, summaries, matched, state, batch, log)
    archive_followers(api, settings.archive_followers, log)


MAX_CLIPS_PER_ACT = 10  # a post holds at most ten of one person's clips of one act (PostRules)
MAX_CLIPS = 60  # and sixty clips in all


def place(
    posts: list[dict[str, Any]], clips: Sequence[Clip], acts: dict[str, str | None]
) -> list[tuple[int, Clip]]:
    """Which of a gig's archive posts each new clip goes into (an index; one past the end means a
    new post): the first post with room for its act, in filming order. A night with more than ten
    clips of an act becomes "part 2" and on (J5 within the API's limits). Updates the counts."""
    out = []
    for clip in clips:
        act = acts.get(clip.name) or ""
        i = next(
            (
                n
                for n, post in enumerate(posts)
                if post.setdefault("acts", {}).get(act, 0) < MAX_CLIPS_PER_ACT
                and len(post["clips"]) < MAX_CLIPS
            ),
            len(posts),
        )
        if i == len(posts):
            posts.append({"post_id": None, "clips": [], "acts": {}})
        posts[i]["acts"][act] = posts[i]["acts"].get(act, 0) + 1
        posts[i]["clips"].append(clip.name)
        out.append((i, clip))
    return out


def archive_posts(
    api: Api,
    settings: Settings,
    gigs: Sequence[Gig],
    summaries: dict[str, dict[str, Any]],
    matched: dict[str, list[Clip]],
    state: State,
    batch: Batch,
    log: Log,
) -> None:
    """One post per gig with all its clips (J5), or as few as the API's limits allow, filled
    across batches: a later batch adds its clips to the gig's last post (POST /posts/{id}/clips)."""
    section = state.section("archive")["posts"]
    overrides = realclips.load_overrides(settings.clips_dir / "clip_acts.csv")
    manifest: list[dict[str, str]] = []
    added = 0
    for gig in gigs:
        clips = sorted(matched.get(gig.key, []), key=lambda c: c.taken)
        if not clips or gig.key not in summaries:
            continue
        gig_id = summaries[gig.key]["id"]
        acts, rule = acts_by_clip(gig, clips, overrides)
        posts: list[dict[str, Any]] = [
            p for p in section.get(gig.key, []) if _post_exists(api, ARCHIVE, p["post_id"])
        ]
        section[gig.key] = posts
        have = {name for p in posts for name in p["clips"]}
        todo = [c for c in clips if c.name not in have and batch.admit(c)]
        if todo:
            try:
                ids = lineup_ids(api, ARCHIVE, gig_id)
                trial = json.loads(json.dumps(posts))
                slots = place(trial, todo, acts)
                for i, post in enumerate(trial):
                    mine = [c for j, c in slots if j == i]
                    if not mine:
                        continue
                    new = upload_clips(
                        api,
                        ARCHIVE,
                        mine,
                        acts,
                        ids,
                        settings.work_dir / "encoded-full",
                        settings.archive_max_clip_s,
                        log,
                    )
                    if post["post_id"] is None:
                        part = f" (part {i + 1})" if i else ""
                        made = api.post(
                            ARCHIVE,
                            "/api/v1/posts",
                            {"clips": new, "gigId": gig_id, "caption": archive_caption(gig) + part},
                        )
                        post["post_id"] = made["id"]
                    else:
                        api.post(ARCHIVE, f"/api/v1/posts/{post['post_id']}/clips", {"clips": new})
                    if i < len(posts):
                        posts[i] = post
                    else:
                        posts.append(post)
                    state.save()
                    added += len(mine)
                    what = f"{gig.night} {gig.headliner.name}"
                    log(f"  archive: {what}, +{len(mine)} clip(s) in post {i + 1}")
            except (ApiError, OSError, subprocess.CalledProcessError) as error:
                log(f"  archive failed {gig.key}: {error}")
        where = {name: p["post_id"] for p in posts for name in p["clips"]}
        for c in clips:
            manifest.append(
                {
                    "file": c.name,
                    "night": str(c.night),
                    "gig": gig.key,
                    "gig_id": gig_id,
                    "act": acts.get(c.name) or "",
                    "tag_rule": rule,
                    "status": "posted" if c.name in where else "next batch",
                    "post_id": where.get(c.name, ""),
                }
            )
    state.save()
    log(f"archive: {added} clips posted this run")
    merge_manifest(settings.clips_dir / "archive.csv", manifest, ARCHIVE_FIELDS)


def archive_caption(gig: Gig) -> str:
    when = gig.night.strftime("%-d %b %Y")
    return (
        f"{gig.title}, {when}" if gig.festival else f"{gig.headliner.name} at {gig.venue}, {when}"
    )


def archive_followers(api: Api, handles: Sequence[str], log: Log) -> None:
    """Accepts the follow requests my real accounts sent the archive (it is private). A handle
    with no request yet is reported: send one from the app, then run the seed again."""
    for handle in handles:
        hits = api.get(ARCHIVE, "/api/v1/search/people", q=handle)
        items = hits["items"] if isinstance(hits, dict) else hits
        user = next((u for u in items if u["handle"].lower() == handle.lower()), None)
        if user is None:
            log(f"archive: no account @{handle}")
            continue
        try:
            api.post(ARCHIVE, f"/api/v1/me/follow-requests/{user['id']}/accept")
            log(f"archive: @{handle} follows it")
        except ApiError as error:
            if error.status != 404:
                raise
            log(f"archive: no follow request from @{handle} yet: send one in the app, run again")


# ── 5. synthetic filler ──────────────────────────────────────────────────────────────────────────
def filler(
    api: Api,
    settings: Settings,
    people: Sequence[Persona],
    genres: dict[str, int],
    state: State,
    log: Log,
) -> None:
    """Synthetic clips (each says "Soundcheck synthetic clip" on screen) for the personas my
    gigs don't cover, tagged with a popular artist of their genre and no gig (a clip needs a past
    gig)."""
    posts = state.section("synthetic")
    real_by = Counter(v["persona"] for v in state.section("posts").values())
    real_by.update(v["persona"] for v in state.section("gig_posts").values())
    clips = sorted(settings.demo_clips_dir.glob("demo-*.mp4"))
    if not clips:
        log("filler: no synthetic clips (run `python3 ops/scripts/make_clips.py --demo`)")
        return
    made = removed = 0
    for k, persona in enumerate(people):
        want = max(settings.synthetic_per_persona - real_by[persona.handle], 0)
        for i in range(want, settings.synthetic_per_persona):  # real clips cover these now
            key = f"{persona.handle}#{i}"
            if key in posts:
                _delete_post(api, persona, posts.pop(key))
                state.save()
                removed += 1
        if want == 0:
            continue
        artists = api.get(
            persona,
            "/api/v1/artists",
            popular="true",
            genreIds=genres[persona.genres[0]],
            limit=want,
        )["items"]
        for i in range(want):
            key = f"{persona.handle}#{i}"
            if key in posts and _post_exists(api, persona, posts[key]):
                continue
            clip = clips[(k * settings.synthetic_per_persona + i) % len(clips)]
            media = api.upload(persona, clip, "video/mp4")
            artist = artists[i % len(artists)] if artists else None
            body: dict[str, Any] = {
                "clips": [
                    {"mediaId": media.media_id, "artistId": artist["id"] if artist else None}
                ],
                "caption": f"{persona.genres[0]} on repeat (synthetic demo clip)",
            }
            if artist:
                body["artistIds"] = [artist["id"]]
            posts[key] = api.post(persona, "/api/v1/posts", body)["id"]
            state.save()
            made += 1
    log(f"filler: {made} synthetic clips posted, {removed} taken away (real clips cover them)")


# ── 6. likes and comments ────────────────────────────────────────────────────────────────────────
def social(api: Api, people: Sequence[Persona], state: State, log: Log) -> None:
    """Personas like the clips from gigs they went to and some from people they follow; a few
    comment."""
    done = state.section("social")
    gigs = state.section("gigs")
    likes = comments = 0
    for persona in people:
        if done.get(persona.handle) is True:  # marked True before 304c402 kept ids
            done[persona.handle] = api.ids[persona.handle]
        if done.get(persona.handle) == api.ids.get(persona.handle):
            continue  # comments are not idempotent: never twice
        went = [
            g["id"] for g in gigs.values() if persona.handle in g["attendees"] or persona is DEMO
        ]
        for gig_id in went:
            for post in api.get(persona, f"/api/v1/gigs/{gig_id}/posts", limit=20)["items"]:
                if post["author"]["handle"] == persona.handle:
                    continue
                roll = stable(persona.handle + post["id"]) % 100
                if roll < 45:
                    api.put(persona, f"/api/v1/posts/{post['id']}/like")
                    likes += 1
                if roll < 8:
                    api.post(
                        persona,
                        f"/api/v1/posts/{post['id']}/comments",
                        {"body": COMMENTS[roll % len(COMMENTS)]},
                    )
                    comments += 1
        done[persona.handle] = api.ids[persona.handle]
        state.save()
    log(f"social: {likes} likes, {comments} comments")


# ── the whole run ────────────────────────────────────────────────────────────────────────────────
def run(settings: Settings, log: Log) -> None:
    check_password_for(settings.api_url)
    state = State.load(settings.work_dir / "state.json")
    api = Api(settings.api_url)
    people = [*PERSONAS[: settings.personas], DEMO]
    indie = [p for p in people if p.indie and p is not DEMO]
    try:
        genres = accounts(api, people, state, settings.work_dir, log)
        follows(api, people, genres, state, log)
        if settings.real:
            gigs = realclips.load_bills(settings.clips_dir / "bills.json")
            gigs = sorted(gigs, key=lambda g: g.night)[: settings.gig_limit]
            clips = load_clips(settings, state, log)
            skips = realclips.load_skips(settings.clips_dir / "skip.csv")
            matched, skipped = realclips.match(clips, gigs, skips)
            reasons = Counter(
                "far from the gig's city" if " km from " in s.reason else s.reason for s in skipped
            )
            log(
                f"matched {sum(len(v) for v in matched.values())} clips to {len(matched)} gigs; "
                f"skipped {len(skipped)}: "
                + ", ".join(f"{n} {r}" for r, n in reasons.most_common())
            )
            if settings.month:
                gigs = [g for g in gigs if g.night.strftime("%Y-%m") == settings.month]
                skipped = [s for s in skipped if s.clip.night.strftime("%Y-%m") == settings.month]
                log(f"batch: the {len(gigs)} gigs of {settings.month}")
            batch = Batch(int(settings.max_gb * 1e9) if settings.max_gb else None)
            summaries = add_gigs(api, gigs, indie, matched, state, log)
            demo_follows(api, genres, state, log)
            real_posts(api, settings, gigs, indie, summaries, matched, skipped, state, batch, log)
            if settings.archive:
                archive(api, settings, gigs, summaries, matched, state, batch, log)
            log(
                f"batch: {batch.used / 1e9:.2f} GB of footage in {len(batch.seen)} clips"
                + (
                    "; the cap was reached: run the same batch again to continue"
                    if batch.full
                    else ""
                )
            )
        else:
            demo_follows(api, genres, state, log)
        filler(api, settings, people, genres, state, log)
        social(api, people, state, log)
    finally:
        api.close()
        log(f"api calls: {api.calls}")


def default_root() -> Path:
    """The repository root (lab/src/soundcheck_lab/seed/run.py → four levels up), or
    SOUNDCHECK_ROOT."""
    return Path(os.environ.get("SOUNDCHECK_ROOT", Path(__file__).resolve().parents[4]))

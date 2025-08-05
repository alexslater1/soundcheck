"""My real concert clips: which gig each one is from, which act is on stage, and which to post.

Inputs (local, gitignored, in fixtures/clips/mine/): `bills.json` (my gigs with their full bills,
prepared from my gig list and setlist.fm), `skip.csv` (nights that were not gigs), the phone's
clips (`PXL_*.mp4`, exact time and GPS inside) and the old phone's (`transferred/`, no GPS; dates I
corrected by hand, in `transferred/index.csv`). Everything here is a pure function except `probe`,
which runs ffprobe."""

from __future__ import annotations

import csv
import json
import math
import re
import subprocess
from collections import defaultdict
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

LONDON = ZoneInfo("Europe/London")
NIGHT_SHIFT = timedelta(hours=6)  # a clip at 01:30 belongs to the evening before
SET_GAP = timedelta(minutes=15)  # a longer pause between clips starts a new group of clips
CHANGEOVER = timedelta(minutes=25)  # sets read as different acts only with a pause this long
SUPPORT_STARTS_BEFORE = 21 * 60  # minutes: a support slot starts before 21:00
HEADLINER_STARTS_FROM = 20 * 60 + 30  # minutes: a headliner starts at 20:30 or later
MAX_KM = 60.0  # a phone clip this far from the gig's city is not from that gig (trips abroad)
MIN_CLIP_S = 4.0
EDIT = re.compile(r"^(?P<base>.+?)~(?P<n>\d+)\.mp4$")  # PXL_…~2.mp4: my edited copy (a trim)


@dataclass(frozen=True)
class Act:
    name: str
    mbid: str | None = None


@dataclass(frozen=True)
class Gig:
    key: str
    night: date
    venue: str
    city: str
    acts: tuple[Act, ...]  # in stage order: first support … headliner last
    title: str | None = None
    festival: bool = False
    lat: float | None = None
    lng: float | None = None
    found_on: dict[str, str | None] | None = None  # setlist.fm id, link and the headliner's MBID
    note: str = ""

    @property
    def headliner(self) -> Act:
        return self.acts[-1]

    @property
    def billing(self) -> list[str]:
        """Act names as POST /gigs wants them: the headliner first."""
        return [self.headliner.name, *(a.name for a in self.acts[:-1])]


@dataclass(frozen=True)
class Clip:
    path: Path
    taken: datetime  # aware
    duration_s: float
    lat: float | None = None
    lng: float | None = None
    old_phone: bool = False

    @property
    def night(self) -> date:
        return (self.taken.astimezone(LONDON) - NIGHT_SHIFT).date()

    @property
    def name(self) -> str:
        return self.path.name


@dataclass(frozen=True)
class Skipped:
    clip: Clip
    reason: str


# ── loading ──────────────────────────────────────────────────────────────────────────────────────
def load_bills(path: Path) -> list[Gig]:
    gigs = []
    for b in json.loads(path.read_text(encoding="utf-8")):
        if not b["acts"]:
            continue
        gigs.append(
            Gig(
                key=b["key"],
                night=date.fromisoformat(b["date"]),
                venue=b["venue"],
                city=b["city"],
                acts=tuple(Act(a["name"], a.get("mbid")) for a in b["acts"]),
                title=b.get("title"),
                festival=bool(b.get("festival")),
                lat=b.get("lat"),
                lng=b.get("lng"),
                found_on=b.get("foundOn"),
                note=b.get("note") or "",
            )
        )
    return gigs


def load_skips(path: Path) -> set[date]:
    """Nights I marked as not gigs (the "ALL" row is the Snapchat rule, applied in
    `old_phone_clips`)."""
    with path.open(encoding="utf-8") as fh:
        return {
            date.fromisoformat(r["night_date"])
            for r in csv.DictReader(fh)
            if r["night_date"] != "ALL"
        }


def probe(path: Path) -> dict[str, str | float | None]:
    """Recording time, duration and GPS from the file (ffprobe reads only the headers)."""
    out = subprocess.run(
        [
            "ffprobe",
            "-v",
            "quiet",
            "-of",
            "json",
            "-show_entries",
            "format=duration:format_tags=creation_time,location",
            str(path),
        ],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    fmt = json.loads(out).get("format", {})
    tags = fmt.get("tags", {})
    return {
        "duration": float(fmt.get("duration", 0)),
        "creation_time": tags.get("creation_time"),
        "location": tags.get("location"),
    }


def phone_clip(path: Path, probed: dict[str, str | float | None]) -> Clip:
    created = probed.get("creation_time")
    if isinstance(created, str):
        taken = datetime.fromisoformat(created.replace("Z", "+00:00"))
    else:  # PXL_20260325_200420521.mp4: the name is the UTC time
        taken = datetime.strptime(path.name[4:19], "%Y%m%d_%H%M%S").replace(tzinfo=UTC)
    lat = lng = None
    match = re.match(r"([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)", str(probed.get("location") or ""))
    if match:
        lat, lng = float(match[1]), float(match[2])
    duration = probed.get("duration")
    return Clip(
        path, taken, float(duration) if isinstance(duration, int | float) else 0.0, lat, lng
    )


def old_phone_clips(folder: Path) -> list[Clip]:
    """The migrated clips, dated by `index.csv` (the phone's media index, my corrections in)."""
    clips = []
    with (folder / "index.csv").open(encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            path = folder / r["file"]
            if r["file"].startswith("Snapchat") or not r["taken_local"] or not path.exists():
                continue  # ignore every Snapchat video
            clips.append(
                Clip(
                    path,
                    datetime.fromisoformat(r["taken_local"]),
                    float(r["duration_s"] or 0),
                    old_phone=True,
                )
            )
    return clips


def latest_edits(clips: Iterable[Clip]) -> list[Clip]:
    """One version of each recording: my latest edit (`~3` over `~2` over the original)."""
    best: dict[str, tuple[int, Clip]] = {}
    for clip in clips:
        m = EDIT.match(clip.name)
        base, n = (m["base"], int(m["n"])) if m else (clip.name.removesuffix(".mp4"), 0)
        if base not in best or n > best[base][0]:
            best[base] = (n, clip)
    return sorted((c for _, c in best.values()), key=lambda c: c.taken)


# ── matching ─────────────────────────────────────────────────────────────────────────────────────
def km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    p = math.pi / 180
    h = (
        math.sin((lat2 - lat1) * p / 2) ** 2
        + math.cos(lat1 * p) * math.cos(lat2 * p) * math.sin((lng2 - lng1) * p / 2) ** 2
    )
    return 12742 * math.asin(math.sqrt(h))


def distance_km(clip: Clip, gig: Gig) -> float | None:
    """How far the clip was filmed from the gig's city; None when either has no coordinates."""
    if clip.lat is None or clip.lng is None or gig.lat is None or gig.lng is None:
        return None
    return km(clip.lat, clip.lng, gig.lat, gig.lng)


def split_at_largest_gap(clips: Sequence[Clip]) -> tuple[list[Clip], list[Clip]]:
    ordered = sorted(clips, key=lambda c: c.taken)
    if len(ordered) < 2:
        return list(ordered), []
    gaps = [(ordered[i + 1].taken - ordered[i].taken, i + 1) for i in range(len(ordered) - 1)]
    cut = max(gaps)[1]
    return ordered[:cut], ordered[cut:]


def match(
    clips: Iterable[Clip], gigs: Iterable[Gig], skip_nights: set[date], max_km: float = MAX_KM
) -> tuple[dict[str, list[Clip]], list[Skipped]]:
    """Each clip to the gig of its night. Phone clips must also be near the gig's city."""
    by_night: dict[date, list[Gig]] = defaultdict(list)
    for gig in gigs:
        by_night[gig.night].append(gig)
    matched: dict[str, list[Clip]] = defaultdict(list)
    skipped: list[Skipped] = []
    pending: dict[date, list[Clip]] = defaultdict(list)
    for clip in clips:
        if clip.night in skip_nights:
            skipped.append(Skipped(clip, "not a gig (skip list)"))
        elif clip.night not in by_night:
            skipped.append(Skipped(clip, "no gig in my list that night"))
        else:
            gig = by_night[clip.night][0]
            distance = distance_km(clip, gig)
            if distance is not None and distance > max_km:
                skipped.append(Skipped(clip, f"{distance:.0f} km from {gig.city}"))
            else:
                pending[clip.night].append(clip)
    for night, night_clips in pending.items():
        tonight = by_night[night]
        if len(tonight) == 1:
            matched[tonight[0].key].extend(night_clips)
        else:  # two gigs one night (NewDad, then Cardinals' late show): split at the longest pause
            ordered = sorted(tonight, key=lambda g: "late" in g.note.lower())
            early, late = split_at_largest_gap(night_clips)
            matched[ordered[0].key].extend(early)
            matched[ordered[-1].key].extend(late)
    return dict(matched), skipped


def sets(clips: Sequence[Clip], gap: timedelta = SET_GAP) -> list[list[Clip]]:
    """Groups a gig's clips into sets: a pause longer than {@code gap} starts the next act's set."""
    groups: list[list[Clip]] = []
    for clip in sorted(clips, key=lambda c: c.taken):
        if groups and clip.taken - groups[-1][-1].taken <= gap:
            groups[-1].append(clip)
        else:
            groups.append([clip])
    return groups


def evening_minutes(t: datetime) -> int:
    """Minutes since midnight of the night, local time: 21:30 is 1290, 00:30 after it is 1470."""
    local = t.astimezone(LONDON)
    minutes = local.hour * 60 + local.minute
    return minutes + 1440 if local.hour < 6 else minutes


def sets_fit_the_bill(gig: Gig, groups: Sequence[Sequence[Clip]]) -> bool:
    """Whether the sets of clips look like the bill's acts one after another: as many sets as acts,
    a real changeover between them (25 min or more), the first starting before 21:00 (a support
    slot) and the last at 20:30 or later (a headliner's). Without these, two clips half an hour
    apart in one long headline set would be read as support and headliner."""
    if len(groups) < 2 or len(groups) != len(gig.acts):
        return False
    changeovers = all(
        b[0].taken - a[-1].taken >= CHANGEOVER for a, b in zip(groups, groups[1:], strict=False)
    )
    return (
        changeovers
        and evening_minutes(groups[0][0].taken) < SUPPORT_STARTS_BEFORE
        and evening_minutes(groups[-1][0].taken) >= HEADLINER_STARTS_FROM
    )


def tag_rule(gig: Gig, clips: Sequence[Clip]) -> str:
    """How `act_tags` decided, for the manifest I review."""
    if gig.festival:
        return "festival: no act"
    if len(gig.acts) == 1:
        return "one act on the bill"
    if sets_fit_the_bill(gig, sets(clips)):
        return "sets matched to the bill (a guess)"
    return "whole bill"


def act_tags(gig: Gig, clips: Sequence[Clip]) -> dict[str, tuple[str, ...]]:
    """Which acts to tag on each clip. One act only on a one-act bill, or when the sets of clips
    fit the bill (`sets_fit_the_bill`; still a guess: there are no set times); otherwise the whole
    bill, since I often went for a support act. Festival clips get no act: there is no telling
    which stage."""
    if gig.festival:
        return {c.name: () for c in clips}
    if len(gig.acts) == 1:
        return {c.name: (gig.headliner.name,) for c in clips}
    groups = sets(clips)
    if sets_fit_the_bill(gig, groups):
        return {
            c.name: (act.name,) for act, group in zip(gig.acts, groups, strict=True) for c in group
        }
    return {c.name: tuple(gig.billing) for c in clips}


def pick(clips: Sequence[Clip], n: int, min_s: float = MIN_CLIP_S) -> list[Clip]:
    """Up to {@code n} clips spread over the night (so different sets are covered), longest-first
    per slot."""
    usable = sorted((c for c in clips if c.duration_s >= min_s), key=lambda c: c.taken)
    if len(usable) <= n:
        return usable
    slots = [
        usable[round(i * len(usable) / n) : round((i + 1) * len(usable) / n)] for i in range(n)
    ]
    return [max(slot, key=lambda c: min(c.duration_s, 60)) for slot in slots if slot]


def segment(duration_s: float, max_s: float) -> tuple[float, float]:
    """(start, length) of the part to post: the whole clip if short, else {@code max_s} from a third
    in."""
    if duration_s <= max_s:
        return 0.0, duration_s
    return round((duration_s - max_s) / 3, 2), max_s


HEADLINER_FROM = 21 * 60  # minutes: a set starting at 21:00 or later is the headliner's


def clip_acts(gig: Gig, clips: Sequence[Clip]) -> tuple[dict[str, str | None], str]:
    """One act per clip (a clip shows on its act's page only), and the rule that chose it.

    Festivals: no act (no telling which stage). One act on the bill: that act. Sets that fit the
    bill (`sets_fit_the_bill`): act by act. Otherwise by the time each set of clips starts: the
    night's last set and any set from 21:00 is the headliner's, an earlier one the main support's
    (the act before the headliner: bills list the stage order). A guess I can correct in
    `clip_acts.csv` (`override_acts`)."""
    if gig.festival:
        return {c.name: None for c in clips}, "festival: no act"
    if len(gig.acts) == 1:
        only: dict[str, str | None] = {c.name: gig.headliner.name for c in clips}
        return only, "one act on the bill"
    groups = sets(clips)
    if sets_fit_the_bill(gig, groups):
        acts: dict[str, str | None] = {
            c.name: act.name for act, group in zip(gig.acts, groups, strict=True) for c in group
        }
        return acts, "sets matched to the bill (a guess)"
    support = gig.acts[-2].name
    chosen: dict[str, str | None] = {}
    for i, group in enumerate(groups):
        late = i == len(groups) - 1 or evening_minutes(group[0].taken) >= HEADLINER_FROM
        for clip in group:
            chosen[clip.name] = gig.headliner.name if late else support
    return chosen, "by start time: from 21:00 the headliner, before it the main support (a guess)"


def load_overrides(path: Path) -> dict[str, str | None]:
    """My corrections, `clip_acts.csv` (file,act): an empty act means no act."""
    if not path.exists():
        return {}
    with path.open(encoding="utf-8") as fh:
        return {r["file"]: (r["act"].strip() or None) for r in csv.DictReader(fh)}


def override_acts(
    gig: Gig, chosen: dict[str, str | None], overrides: dict[str, str | None]
) -> dict[str, str | None]:
    """The chosen acts with my corrections applied; a correction naming an act not on the
    bill is ignored (the API would refuse it)."""
    names = {a.name for a in gig.acts}
    return {
        clip: (
            overrides[clip]
            if clip in overrides and (overrides[clip] in names or overrides[clip] is None)
            else act
        )
        for clip, act in chosen.items()
    }

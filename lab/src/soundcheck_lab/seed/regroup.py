"""Regroups the personas' posts of my clips into the seed's current split ({@code post_split})
without uploading anything again.

Every clip is already uploaded and transcoded. Moving one to another persona's post goes through the
API (remove the clip, delete an emptied post, add clips, make a post) except for two steps the API
has no call for, done in SQL on the local database: a deleted post's clip rows still hold their
media, so they are removed; and a clip that changes persona has its media (and preview frame) handed
to the new owner, since a post takes only its poster's own uploads. Afterwards the seed's state
records the new posts, so `make seed-demo` sees them as done.

Local demo data only: the personas and their posts are made up, and nothing here runs in CI.
"""

from __future__ import annotations

import subprocess
import uuid
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from soundcheck_lab.seed import realclips
from soundcheck_lab.seed.api import Api
from soundcheck_lab.seed.personas import DEMO, PERSONAS, Persona
from soundcheck_lab.seed.realclips import Clip, Gig
from soundcheck_lab.seed.run import (
    Log,
    Settings,
    State,
    acts_by_clip,
    attendees,
    caption,
    load_clips,
    post_groups,
    post_split,
    poster_for,
)


@dataclass(frozen=True)
class Slot:
    """One clip as it is posted now: its post, the persona that posted it and its rows."""

    post_id: str
    persona: str
    clip_id: str
    media_id: str
    preview_id: str | None
    artist_id: str | None


@dataclass(frozen=True)
class Wanted:
    """One post of the new split: who posts it and which clips, in filmed order."""

    persona: Persona
    clips: list[Clip]


def plan(gig: Gig, clips: Sequence[Clip], indie: Sequence[Persona], per_gig: int) -> list[Wanted]:
    """The posts a gig should have now, exactly as {@code real_posts} would make them."""
    chosen = realclips.pick(clips, per_gig)
    people = attendees(gig, indie)
    groups = post_groups(chosen, post_split(len(chosen), gig.key))
    return [Wanted(poster_for(gig, people, i), group) for i, group in enumerate(groups)]


def same(old: dict[str, dict[str, Any]], wanted: Sequence[Wanted]) -> bool:
    have = sorted((v["persona"], v["clips"]) for v in old.values())
    want = sorted((w.persona.handle, [c.name for c in w.clips]) for w in wanted)
    return have == want


class Db:
    """psql in the compose stack's database container (as the Makefile's `seed-demo` check does)."""

    def __init__(self, root: Path) -> None:
        self.root = root

    def rows(self, query: str) -> list[list[str]]:
        command = ["docker", "compose", "exec", "-T", "db", "psql", "-U", "soundcheck"]
        command += ["-d", "soundcheck", "-v", "ON_ERROR_STOP=1", "-At", "-F", "\t", "-c", query]
        out = subprocess.run(command, cwd=self.root, check=True, capture_output=True, text=True)
        return [line.split("\t") for line in out.stdout.splitlines() if line]


def ids(values: Sequence[str]) -> str:
    """A SQL list of UUIDs, each checked to be one (they come from the seed's own state)."""
    return ", ".join(f"'{uuid.UUID(v)}'" for v in values)


def slots(db: Db, old: dict[str, dict[str, Any]]) -> tuple[dict[str, Slot], list[Slot]] | None:
    """Clip name → where it is posted now, and the duplicates to remove. A post's clips were
    uploaded in the order the state lists them, and media ids are UUIDv7 (time-ordered), so sorting
    by media id lines them up. 33 posts also hold an older upload of one of their clips: the
    earlier one-clip post that the API joined into the persona's new post of the same gig. Those
    come first in media-id order and match a listed clip's length; they are returned as
    duplicates. None when a post's clips do not match the state otherwise: that gig is left."""
    found: dict[str, Slot] = {}
    duplicates: list[Slot] = []
    for entry in old.values():
        raw = db.rows(
            "select pc.id, pc.media_id, coalesce(pc.preview_media_id::text, ''),"
            " coalesce(pc.artist_id::text, ''), coalesce(m.duration_ms, 0)"
            " from content.post_clip pc join media.media_asset m on m.id = pc.media_id"
            f" where pc.post_id = {ids([entry['post_id']])} order by pc.media_id"
        )
        rows = [
            Slot(entry["post_id"], entry["persona"], r[0], r[1], r[2] or None, r[3] or None)
            for r in raw
        ]
        lengths = [r[4] for r in raw]
        names = entry["clips"]
        extra = len(rows) - len(names)
        if extra < 0 or any(lengths[i] not in lengths[extra:] for i in range(extra)):
            return None
        duplicates += rows[:extra]
        found.update(zip(names, rows[extra:], strict=True))
    return found, duplicates


def regroup(
    settings: Settings, log: Log, apply: bool, followers: Sequence[str] = (), only: str = ""
) -> None:
    state = State.load(settings.work_dir / "state.json")
    done = state.section("gig_posts")
    gig_ids = state.section("gigs")
    people = [*PERSONAS[: settings.personas], DEMO]
    indie = [p for p in people if p.indie and p is not DEMO]
    by_handle = {p.handle: p for p in people}
    gigs = realclips.load_bills(settings.clips_dir / "bills.json")
    clips = load_clips(settings, state, log)
    skips = realclips.load_skips(settings.clips_dir / "skip.csv")
    matched, _ = realclips.match(clips, gigs, skips)
    overrides = realclips.load_overrides(settings.clips_dir / "clip_acts.csv")
    db = Db(settings.root)
    api = Api(settings.api_url)
    changed = left = moved = dropped = 0
    try:
        for gig in sorted(gigs, key=lambda g: g.night):
            prefix = f"{gig.key}#"
            old = {k: v for k, v in done.items() if k.startswith(prefix)}
            if not old or gig.key not in gig_ids or only not in gig.key:
                continue
            wanted = plan(gig, matched.get(gig.key, []), indie, settings.clips_per_gig)
            found = slots(db, old)
            if found is None:
                log(f"  {gig.key}: its posts were edited; left as they are")
                left += 1
                continue
            where, duplicates = found
            if same(old, wanted):
                if duplicates:
                    log(f"  {gig.night} {gig.headliner.name}: {len(duplicates)} duplicate(s)")
                    dropped += len(duplicates)
                    if apply:
                        drop(api, duplicates, by_handle)
                continue
            posted = {name for v in old.values() for name in v["clips"]}
            if posted != {c.name for w in wanted for c in w.clips}:
                log(f"  {gig.key}: other clips than are posted; left for `make seed-demo`")
                left += 1
                continue
            dropped += len(duplicates)
            split = " + ".join(f"{len(w.clips)} @{w.persona.handle}" for w in wanted)
            log(f"  {gig.night} {gig.headliner.name}: {split}")
            changed += 1
            moved += sum(
                1 for w in wanted for c in w.clips if where[c.name].persona != w.persona.handle
            )
            if apply:
                made = move(
                    api, db, gig, gig_ids[gig.key]["id"], wanted, where, by_handle, overrides
                )
                kept = set(made)
                drop(api, [d for d in duplicates if d.post_id in kept], by_handle)
                for key in old:
                    del done[key]
                for i, (w, post_id) in enumerate(zip(wanted, made, strict=True)):
                    done[f"{prefix}{i}"] = {
                        "post_id": post_id,
                        "persona": w.persona.handle,
                        "clips": [c.name for c in w.clips],
                    }
                state.save()
        verb = "regrouped" if apply else "would regroup"
        log(
            f"{verb} {changed} gigs ({moved} clips change persona), {dropped} duplicate clips;"
            f" {left} left as they are"
        )
        if apply and followers:
            from soundcheck_lab.seed.run import archive_followers

            archive_followers(api, followers, log)
    finally:
        api.close()


def drop(api: Api, duplicates: Sequence[Slot], by_handle: dict[str, Persona]) -> None:
    """Removes duplicate uploads from the posts that hold them (each keeps its listed clips)."""
    for slot in duplicates:
        api.request(
            by_handle[slot.persona], "DELETE", f"/api/v1/posts/{slot.post_id}/clips/{slot.clip_id}"
        )


def move(
    api: Api,
    db: Db,
    gig: Gig,
    gig_id: str,
    wanted: Sequence[Wanted],
    where: dict[str, Slot],
    by_handle: dict[str, Persona],
    overrides: dict[str, str | None],
) -> list[str]:
    """Moves one gig's clips into the wanted posts and returns their post ids, in order. A post is
    kept when its persona keeps at least one of its clips (so its likes and comments stay); the
    others are deleted first, then the clips are handed over and added."""
    keeps: dict[str, Wanted] = {}  # old post id → the group it becomes
    for w in wanted:
        for c in w.clips:
            slot = where[c.name]
            mine = slot.persona == w.persona.handle
            if mine and slot.post_id not in keeps and w not in keeps.values():
                keeps[slot.post_id] = w
    old_posts = {s.post_id: s.persona for s in where.values()}

    # 1. Free every clip that leaves its post: delete posts nobody keeps (and their clip rows,
    #    which would still hold the media), remove the leaving clips from the posts that stay.
    gone = [p for p in old_posts if p not in keeps]
    for post_id in gone:
        api.request(by_handle[old_posts[post_id]], "DELETE", f"/api/v1/posts/{post_id}")
    if gone:
        db.rows(f"delete from content.post_clip where post_id in ({ids(gone)})")
    for post_id, w in keeps.items():
        staying = {c.name for c in w.clips}
        for name, slot in where.items():
            if slot.post_id == post_id and name not in staying:
                api.request(
                    by_handle[slot.persona],
                    "DELETE",
                    f"/api/v1/posts/{post_id}/clips/{slot.clip_id}",
                )

    # 2. Hand each clip that changes persona (and its preview frame) to its new poster.
    for w in wanted:
        handing = [where[c.name] for c in w.clips if where[c.name].persona != w.persona.handle]
        media = [s.media_id for s in handing] + [s.preview_id for s in handing if s.preview_id]
        if media:
            db.rows(
                "update media.media_asset set owner_id ="
                f" (select id from identity.app_user where handle = '{w.persona.handle}')"
                f" where id in ({ids(media)})"
            )

    # 3. Add each group's incoming clips to its kept post, or post them as a new one.
    acts, _ = acts_by_clip(gig, [c for w in wanted for c in w.clips], overrides)
    kept_by = {id(w): post_id for post_id, w in keeps.items()}
    made: list[str] = []
    for w in wanted:
        kept = kept_by.get(id(w))
        incoming = [c for c in w.clips if kept is None or where[c.name].post_id != kept]
        body = [
            {
                "mediaId": where[c.name].media_id,
                "artistId": where[c.name].artist_id,
                "previewMediaId": where[c.name].preview_id,
            }
            for c in incoming
        ]
        if kept is not None:
            post_id = kept
            if body:
                api.post(w.persona, f"/api/v1/posts/{post_id}/clips", {"clips": body})
        else:
            names = sorted({a for c in w.clips if (a := acts.get(c.name))})
            post: dict[str, Any] = {
                "clips": body,
                "gigId": gig_id,
                "caption": caption(gig, names, w.clips[0]),
            }
            tagged = sorted({b["artistId"] for b in body if b["artistId"]})
            if tagged:
                post["artistIds"] = tagged
            post_id = str(api.post(w.persona, "/api/v1/posts", post)["id"])
        made.append(post_id)
    return made

"""Batches of my footage, one act per clip, persona posts of several clips."""

import csv
import json
from datetime import date, datetime
from pathlib import Path
from typing import Any

import httpx
import pytest

from soundcheck_lab.seed import encode, realclips, run
from soundcheck_lab.seed.api import Api, Media
from soundcheck_lab.seed.personas import ARCHIVE, PERSONAS
from soundcheck_lab.seed.realclips import LONDON, Act, Clip, Gig

NIGHT = date(2026, 3, 25)


def gig(*acts: str, festival: bool = False) -> Gig:
    return Gig(
        key=f"{NIGHT}|{acts[-1]}",
        night=NIGHT,
        venue="Scala",
        city="London",
        acts=tuple(Act(a) for a in acts),
        festival=festival,
    )


def clip(name: str, hh: int, mm: int = 0, path: Path | None = None) -> Clip:
    when = datetime(NIGHT.year, NIGHT.month, NIGHT.day, hh, mm, tzinfo=LONDON)
    return Clip(path or Path(name), when, 30.0)


def test_one_act_per_clip_by_start_time_when_the_sets_do_not_fit_the_bill() -> None:
    # three acts but two sets of clips: no set-by-set match; 20:05 is the main support's set
    g = gig("Opener", "Westside Cowboy", "Geese")
    clips = [clip("a", 20, 5), clip("b", 20, 10), clip("c", 21, 18), clip("d", 21, 25)]
    acts, rule = realclips.clip_acts(g, clips)
    assert acts == {"a": "Westside Cowboy", "b": "Westside Cowboy", "c": "Geese", "d": "Geese"}
    assert rule.startswith("by start time")


def test_the_nights_last_set_is_the_headliners_even_before_nine() -> None:
    g = gig("Support", "Headliner")
    acts, _ = realclips.clip_acts(g, [clip("a", 19, 0), clip("b", 20, 40)])
    assert acts == {"a": "Support", "b": "Headliner"}


def test_festival_clips_have_no_act_and_a_one_act_bill_tags_that_act() -> None:
    assert realclips.clip_acts(gig("A", "B", festival=True), [clip("a", 15)])[0] == {"a": None}
    assert realclips.clip_acts(gig("Keo"), [clip("a", 19)])[0] == {"a": "Keo"}


def test_alexs_corrections_win_unless_they_name_an_act_not_on_the_bill(tmp_path: Path) -> None:
    path = tmp_path / "clip_acts.csv"
    path.write_text("file,act\na,Support\nb,\nc,Somebody Else\n")
    overrides = realclips.load_overrides(path)
    g = gig("Support", "Headliner")
    fixed = realclips.override_acts(
        g, {"a": "Headliner", "b": "Headliner", "c": "Headliner"}, overrides
    )
    assert fixed == {"a": "Support", "b": None, "c": "Headliner"}
    assert realclips.load_overrides(tmp_path / "missing.csv") == {}


def test_a_persona_post_carries_its_clips_and_four_or_more_make_two_posts() -> None:
    clips = [clip(str(i), 21, i) for i in range(5)]
    assert run.post_groups([]) == []
    assert run.post_groups(clips[:3]) == [clips[:3]]
    assert run.post_groups(clips[:4]) == [clips[:2], clips[2:4]]
    assert [len(g) for g in run.post_groups(clips)] == [2, 3]


def test_a_batch_stops_at_its_size_but_always_takes_the_first_clip(tmp_path: Path) -> None:
    files = []
    for name, size in (("a", 600), ("b", 300), ("c", 200)):
        (tmp_path / name).write_bytes(b"x" * size)
        files.append(clip(name, 21, path=tmp_path / name))
    a, b, c = files
    batch = run.Batch(limit_bytes=500)
    assert batch.admit(a)  # larger than the cap, but the first: a batch always moves
    assert batch.admit(a)  # counted once
    assert not batch.admit(b) and batch.full
    assert batch.used == 600 and batch.seen == {"a"}
    assert run.Batch().admit(c)  # no cap


def test_the_manifest_keeps_other_months_rows(tmp_path: Path) -> None:
    path = tmp_path / "archive.csv"
    fields = run.ARCHIVE_FIELDS
    run.merge_manifest(path, [{"file": "a", "night": "2025-01-01", "status": "posted"}], fields)
    run.merge_manifest(
        path,
        [
            {"file": "b", "night": "2025-02-01", "status": "next batch"},
            {"file": "a", "night": "2025-01-01", "status": "posted", "post_id": "p1"},
        ],
        fields,
    )
    with path.open() as fh:
        rows = list(csv.DictReader(fh))
    assert [(r["file"], r["status"], r["post_id"]) for r in rows] == [
        ("a", "posted", "p1"),
        ("b", "next batch", ""),
    ]


class Server:
    """Just enough of the API for the archive: me, attendance, a gig's bill and its edit, uploads,
    posts and their clips, people search and follow requests."""

    def __init__(self) -> None:
        self.lineup = [{"id": "a-geese", "name": "Geese"}]
        self.calls: list[tuple[str, str, dict[str, Any]]] = []
        self.posts: dict[str, list[dict[str, Any]]] = {}

    def __call__(self, request: httpx.Request) -> httpx.Response:
        path, method = request.url.path, request.method
        is_json = request.headers.get("content-type", "").startswith("application/json")
        body = json.loads(request.content) if request.content and is_json else {}
        self.calls.append((method, path, body))
        if path.startswith("/api/v1/auth/"):
            user = {"id": "arch", "handle": "alex_archive"}
            return httpx.Response(200, json={"accessToken": "t", "expiresIn": 900, "user": user})
        if path == "/api/v1/me":
            me = {"id": "arch", "onboarded": True, "profileVisibility": "PUBLIC"}
            return httpx.Response(200, json=me)
        if path == "/api/v1/gigs/g1" and method == "PATCH":
            self.lineup += [{"id": f"a-{n}", "name": n} for n in body["addArtistNames"]]
            return httpx.Response(200, json={})
        if path == "/api/v1/gigs/g1":
            return httpx.Response(200, json={"gig": {"id": "g1"}, "lineup": self.lineup})
        if path.endswith("/attendance"):
            return httpx.Response(200, json={})
        if path == "/api/v1/media/uploads":
            n = sum(1 for c in self.calls if c[1] == path)
            ticket = {"mediaId": f"m{n}", "strategy": "SINGLE", "putUrl": "http://s3.test/put"}
            return httpx.Response(201, json=ticket)
        if path == "/put" or path.endswith("/complete"):
            return httpx.Response(200, json={})
        if path == "/api/v1/posts":
            self.posts["p1"] = list(body["clips"])
            return httpx.Response(201, json={"id": "p1"})
        if path == "/api/v1/posts/p1/clips":
            self.posts["p1"] += body["clips"]
            return httpx.Response(200, json={})
        if path == "/api/v1/posts/p1":
            return httpx.Response(200, json={"status": "READY"})
        if path == "/api/v1/search/people":
            return httpx.Response(200, json={"items": [{"id": "u-alex", "handle": "alex"}]})
        if path.endswith("/accept"):
            return httpx.Response(204)
        return httpx.Response(404, json={"code": "NOT_FOUND", "detail": path})


def test_the_archive_goes_completes_the_bill_and_posts_every_clip_over_two_batches(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        encode,
        "encode",
        lambda src, out, start, length, log: (
            out.parent.mkdir(parents=True, exist_ok=True) or out.write_bytes(b"v")
        ),
    )
    clips = []
    for name, hh, mm in (("a.mp4", 20, 5), ("b.mp4", 21, 20), ("c.mp4", 21, 30)):
        (tmp_path / name).write_bytes(b"x" * 400)
        clips.append(clip(name, hh, mm, path=tmp_path / name))
    g = gig("Westside Cowboy", "Geese")
    settings = run.Settings(root=tmp_path, archive_followers=("alex",))
    settings.clips_dir.mkdir(parents=True)
    state = run.State.load(tmp_path / ".localdata" / "state.json")
    server = Server()
    api = Api(
        "http://api.test",
        transport=httpx.MockTransport(server),
        sleep=lambda _: None,
        auth_spacing_s=0,
    )
    summaries = {g.key: {"id": "g1"}}
    lines: list[str] = []

    def go(batch: run.Batch) -> None:
        run.archive(api, settings, [g], summaries, {g.key: clips}, state, batch, lines.append)

    go(run.Batch(limit_bytes=900))  # two of the three clips fit
    assert ("PATCH", "/api/v1/me", {"profileVisibility": "PRIVATE", "bio": ARCHIVE.bio}) in [
        c for c in server.calls
    ]
    assert ("PATCH", "/api/v1/gigs/g1", {"addArtistNames": ["Westside Cowboy"]}) in server.calls
    assert server.posts["p1"] == [
        {"mediaId": "m1", "artistId": "a-Westside Cowboy"},
        {"mediaId": "m2", "artistId": "a-geese"},
    ]
    go(run.Batch(limit_bytes=900))  # the next batch adds the rest to the same post
    assert server.posts["p1"][-1] == {"mediaId": "m3", "artistId": "a-geese"}
    assert sum(1 for c in server.calls if c[:2] == ("PUT", "/api/v1/gigs/g1/attendance")) == 1
    assert ("POST", "/api/v1/me/follow-requests/u-alex/accept", {}) in server.calls
    with (tmp_path / "fixtures" / "clips" / "mine" / "archive.csv").open() as fh:
        rows = list(csv.DictReader(fh))
    assert [(r["file"], r["act"], r["status"]) for r in rows] == [
        ("a.mp4", "Westside Cowboy", "posted"),
        ("b.mp4", "Geese", "posted"),
        ("c.mp4", "Geese", "posted"),
    ]


def test_persona_posts_carry_several_clips_and_replace_the_one_clip_posts(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(encode, "encode", lambda src, out, start, length, log: None)
    monkeypatch.setattr(
        Api,
        "upload",
        lambda self, who, path, ct, meta=None: Media(f"m-{path.stem}", "SINGLE"),
    )
    clips = []
    for name, hh, mm in (("a", 20, 5), ("b", 20, 10), ("c", 21, 20), ("d", 21, 40)):
        (tmp_path / name).write_bytes(b"x")
        clips.append(clip(name, hh, mm, path=tmp_path / name))
    g = gig("Westside Cowboy", "Geese")
    settings = run.Settings(root=tmp_path, clips_per_gig=4)
    settings.clips_dir.mkdir(parents=True)
    state = run.State.load(tmp_path / "state.json")
    indie = [p for p in PERSONAS if p.indie]
    poster = run.attendees(g, indie)[0]
    state.section("posts")["a"] = {"post_id": "old-a", "persona": poster.handle}
    server = Server()
    server.lineup.append({"id": "a-wc", "name": "Westside Cowboy"})
    api = Api(
        "http://api.test",
        transport=httpx.MockTransport(server),
        sleep=lambda _: None,
        auth_spacing_s=0,
    )
    run.real_posts(
        api,
        settings,
        [g],
        indie,
        {g.key: {"id": "g1"}},
        {g.key: clips},
        [],
        state,
        run.Batch(),
        lambda _: None,
    )
    made = [c[2] for c in server.calls if c[:2] == ("POST", "/api/v1/posts")]
    assert [[x["mediaId"] for x in body["clips"]] for body in made] == [
        ["m-a", "m-b"],
        ["m-c", "m-d"],
    ]
    assert made[0]["clips"][0]["artistId"] == "a-wc" and made[0]["artistIds"] == ["a-wc"]
    assert made[1]["artistIds"] == ["a-geese"]
    assert ("DELETE", "/api/v1/posts/old-a", {}) in server.calls
    assert state.section("posts") == {}
    assert set(state.section("gig_posts")) == {f"{g.key}#0", f"{g.key}#1"}

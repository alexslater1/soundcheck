import re
import struct
from datetime import date, datetime
from pathlib import Path
from typing import Any

import pytest
from typer.testing import CliRunner

from soundcheck_lab.cli import app
from soundcheck_lab.seed import encode, engage, run
from soundcheck_lab.seed.avatar import avatar_png
from soundcheck_lab.seed.personas import DEMO, PERSONAS, check_password_for
from soundcheck_lab.seed.realclips import Act, Clip, Gig

CANONICAL_GENRES = {
    "Rock",
    "Indie",
    "Alternative",
    "Punk",
    "Metal",
    "Pop",
    "Hip-Hop",
    "Grime",
    "R&B",
    "Soul",
    "Funk",
    "Electronic",
    "House",
    "Techno",
    "Drum and Bass",
    "Garage",
    "Jazz",
    "Blues",
    "Folk",
    "Country",
    "Classical",
    "Reggae",
    "Afrobeats",
    "Latin",
}
INDIE = [p for p in PERSONAS if p.indie]


def test_avatars_are_valid_pngs_and_the_same_for_the_same_handle() -> None:
    a = avatar_png("maya_frontrow", size=32)
    assert a.startswith(b"\x89PNG\r\n\x1a\n")
    assert struct.unpack(">II", a[16:24]) == (32, 32)
    assert a == avatar_png("maya_frontrow", size=32)
    assert a != avatar_png("leon_techno", size=32)


def test_personas_are_valid_accounts_with_known_genres() -> None:
    handles = [p.handle for p in (*PERSONAS, DEMO)]
    assert len(PERSONAS) == 30 and len(set(handles)) == 31
    assert all(re.fullmatch(r"[a-z0-9_]{3,30}", h) for h in handles)
    assert all(set(p.genres) <= CANONICAL_GENRES for p in (*PERSONAS, DEMO))
    assert len(INDIE) >= 18  # most personas go to the kind of gigs I filmed
    assert {p.city for p in PERSONAS} >= {"London", "Brighton", "Manchester", "Bristol"}


def test_the_default_password_is_refused_off_the_local_stack(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("SEED_PASSWORD", raising=False)
    check_password_for("http://localhost:8088")  # fine
    with pytest.raises(ValueError, match="SEED_PASSWORD"):
        check_password_for("https://soundcheck.example.com")
    monkeypatch.setenv("SEED_PASSWORD", "a-real-secret-from-secrets-manager")
    check_password_for("https://soundcheck.example.com")


def test_encode_command_cuts_scales_and_drops_metadata() -> None:
    cmd = encode.encode_command(Path("in.mp4"), Path("out.mp4"), 25.0, 45.0)
    joined = " ".join(cmd)
    assert "-ss 25.00 -i in.mp4 -t 45.00" in joined
    assert "min(1280,iw)" in joined and "-b:v 5M" in joined
    assert "-map_metadata -1" in joined and cmd[-1] == "out.mp4"


def test_capped_runs_inside_a_memory_limited_scope(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("shutil.which", lambda name: "/usr/bin/systemd-run")
    assert encode.capped(["ffmpeg"])[:4] == ["systemd-run", "--user", "--scope", "--quiet"]
    assert "MemoryMax=1G" in encode.capped(["ffmpeg"])
    monkeypatch.setattr("shutil.which", lambda name: None)
    assert encode.capped(["ffmpeg"]) == ["nice", "-n", "19", "ffmpeg"]


def gig(key: str, city: str = "London", festival: bool = False) -> Gig:
    return Gig(
        key,
        date(2026, 3, 25),
        "CHALK" if city == "Brighton" else "Scala",
        city,
        (Act("Bleech 9:3"), Act("Keo")),
        festival=festival,
    )


def test_attendees_are_stable_locals_first_and_three_to_five() -> None:
    london = run.attendees(gig("2026-03-12|Keo"), INDIE)
    assert london == run.attendees(gig("2026-03-12|Keo"), INDIE)
    assert 3 <= len(london) <= 5 and all(p.city == "London" for p in london)
    brighton = run.attendees(gig("2026-09-29|Keo", city="Brighton"), INDIE)
    assert {p.city for p in brighton[:2]} == {"Brighton"}
    assert all(p.city in {"Brighton", "London"} for p in brighton)
    many = {p.handle for k in range(40) for p in run.attendees(gig(f"t{k}", festival=True), INDIE)}
    assert any(p.city not in {"London", "Brighton"} for p in INDIE if p.handle in many)


def test_captions_name_the_act_or_the_festival() -> None:
    c = Clip(Path("x.mp4"), datetime(2026, 3, 25, 21), 30.0)
    assert {"Keo", "Scala"} & set(re.findall(r"\w+", run.caption(gig("k"), ("Keo",), c)))
    festival = Gig(
        "t",
        date(2026, 7, 24),
        "Hill Farm",
        "Steventon",
        (Act("The Maccabees"),),
        title="Truck Festival 2026 (Friday)",
        festival=True,
    )
    assert "Truck Festival 2026 (Friday)" in run.caption(festival, (), c)
    assert run.caption(gig("k"), ("Keo",), c) == run.caption(gig("k"), ("Keo",), c)


def post(genres: list[str], went: bool = False, liked: bool = False) -> dict[str, object]:
    return {
        "id": "p-" + "-".join(genres) + str(went),
        "likedByMe": liked,
        "artists": [{"genres": genres}],
        "gig": {"myAttendance": "WENT" if went else None},
    }


def test_engagement_follows_taste_and_never_likes_twice() -> None:
    maya = PERSONAS[0]  # Indie, Alternative, Punk
    assert engage.interest(maya, post(["Indie", "Alternative"])) == 1.0
    assert engage.interest(maya, post(["Techno"])) == 0.0
    assert engage.interest(maya, post(["Techno"], went=True)) == pytest.approx(0.3)
    loved = [
        engage.reactions(maya, post(["Indie"]) | {"id": f"a{i}"}, i, "s", "0") for i in range(200)
    ]
    ignored = [
        engage.reactions(maya, post(["Techno"]) | {"id": f"b{i}"}, i, "s", "0") for i in range(200)
    ]
    assert sum(len(e) for e, _ in loved) > 2 * sum(len(e) for e, _ in ignored)
    assert sum(like for _, like in loved) > sum(like for _, like in ignored)
    for events, _ in loved:
        assert events[0]["type"] == "IMPRESSION" and events[0]["surface"] == "FOR_YOU"
    already = [
        engage.reactions(maya, post(["Indie"], liked=True) | {"id": f"c{i}"}, i, "s", "0")
        for i in range(100)
    ]
    assert not any(like for _, like in already)


VENUES: list[dict[str, Any]] = [  # GET /venues lists those near home first
    {
        "id": "islington",
        "name": "O2 Academy2 Islington",
        "city": "London",
        "location": {"lat": 51.5345, "lng": -0.1066},
    },
    {"id": "o2", "name": "The O2", "city": "London", "location": {"lat": 51.5016, "lng": 0.0055}},
    {
        "id": "earth",
        "name": "EartH Theatre",
        "city": "London",
        "location": {"lat": 51.5501, "lng": -0.0754},
    },
    {
        "id": "dome",
        "name": "The Dome London",
        "city": "London",
        "location": {"lat": 51.5568, "lng": -0.1383},
    },
    {"id": "o2-belfast", "name": "The O2", "city": "Belfast", "location": None},
]


def test_choose_venue_never_takes_a_short_name_as_a_prefix() -> None:
    assert run.choose_venue(VENUES, "The O2", "London", None) == "o2"  # not O2 Academy2 Islington
    assert run.choose_venue(VENUES, "EartH", "London", None) == "earth"  # EartH + " Theatre"
    assert run.choose_venue(VENUES, "The Dome", "London", None) is None  # "dome" is too short
    assert run.choose_venue(VENUES, "The O2", "Belfast", None) == "o2-belfast"


def test_choose_venue_uses_gps_to_confirm_and_rule_out_not_to_decide() -> None:
    filmed = (51.5574, -0.1384)  # Tufnell Park, 72 m from the Dome
    assert run.choose_venue(VENUES, "The Dome", "London", filmed) == "dome"
    far_away = (51.4657, -0.1149)  # Brixton: no hit within 2 km, so not even the exact name
    assert run.choose_venue(VENUES, "The O2", "London", far_away) is None
    # Kasabian at Finsbury Park, 2026-07-04: the pub next to the park is not the park
    pub = [
        {
            "id": "pub",
            "name": "The Finsbury",
            "city": "London",
            "location": {"lat": 51.5649, "lng": -0.1060},
        }
    ]
    assert run.choose_venue(pub, "Finsbury Park", "London", (51.5675, -0.1028)) is None
    # Wolf Alice at The O2, 2025-12-03: a hit without coordinates is not "far away"
    unplaced = [{"id": "o2", "name": "The O2", "city": "London", "location": None}]
    assert run.choose_venue(unplaced, "The O2", "London", (51.5028, 0.0029)) == "o2"


def test_words_drop_the_and_apostrophes() -> None:
    assert run.words("The Elephant's Head") == ["elephants", "head"]
    assert run.words("Signature Brew - Haggerston") == ["signature", "brew", "haggerston"]


def test_centroid_of_the_gps_tagged_clips_only() -> None:
    t = datetime(2026, 3, 25, 21)
    clips = [
        Clip(Path("a"), t, 1, 51.0, -0.2),
        Clip(Path("b"), t, 1, 52.0, -0.4),
        Clip(Path("c"), t, 1),
    ]
    assert run.centroid(clips) == pytest.approx((51.5, -0.3))
    assert run.centroid(clips[2:]) is None


def test_state_survives_a_restart(tmp_path: Path) -> None:
    state = run.State.load(tmp_path / "state.json")
    state.section("posts")["PXL_1.mp4"] = {"post_id": "p1"}
    state.save()
    assert run.State.load(tmp_path / "state.json").section("posts") == {
        "PXL_1.mp4": {"post_id": "p1"}
    }


def test_cli_offers_seed_demo() -> None:
    result = CliRunner().invoke(app, ["seed-demo", "--help"])
    assert result.exit_code == 0
    assert "--real" in result.output and "--gigs" in result.output

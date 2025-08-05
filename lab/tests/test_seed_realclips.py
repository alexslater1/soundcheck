import json
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

from soundcheck_lab.seed import realclips
from soundcheck_lab.seed.realclips import LONDON, Act, Clip, Gig

LONDON_LAT, LONDON_LNG = 51.5074, -0.1278


def gig(key: str, night: date, *acts: str, festival: bool = False, note: str = "") -> Gig:
    return Gig(
        key=key,
        night=night,
        venue="Scala",
        city="London",
        acts=tuple(Act(a) for a in acts),
        festival=festival,
        lat=LONDON_LAT,
        lng=LONDON_LNG,
        note=note,
    )


def clip(
    name: str,
    when: datetime,
    seconds: float = 30.0,
    *,
    lat: float | None = None,
    lng: float | None = None,
) -> Clip:
    return Clip(Path(name), when, seconds, lat, lng)


def at(day: date, hh: int, mm: int = 0) -> datetime:
    return datetime(day.year, day.month, day.day, hh, mm, tzinfo=LONDON)


NIGHT = date(2026, 3, 25)


def test_a_clip_after_midnight_belongs_to_the_evening_before() -> None:
    assert clip("a.mp4", at(date(2026, 3, 26), 1, 30)).night == NIGHT
    assert clip("b.mp4", at(NIGHT, 21)).night == NIGHT


def test_match_by_night_skips_non_gigs_and_clips_filmed_far_away() -> None:
    gigs = [gig("geese", NIGHT, "Westside Cowboy", "Geese")]
    clips = [
        clip("near.mp4", at(NIGHT, 21), lat=51.491, lng=-0.2237),  # Hammersmith: 7 km
        clip("old-phone.mp4", at(NIGHT, 22)),  # no GPS: the date decides
        clip("budapest.mp4", at(NIGHT, 23), lat=47.51, lng=19.05),
        clip("arsenal.mp4", at(date(2026, 5, 5), 21, 54)),
        clip("no-gig.mp4", at(date(2026, 4, 1), 21)),
    ]
    matched, skipped = realclips.match(clips, gigs, skip_nights={date(2026, 5, 5)})
    assert [c.name for c in matched["geese"]] == ["near.mp4", "old-phone.mp4"]
    reasons = {s.clip.name: s.reason for s in skipped}
    assert reasons["arsenal.mp4"] == "not a gig (skip list)"
    assert reasons["no-gig.mp4"] == "no gig in my list that night"
    assert reasons["budapest.mp4"].endswith("km from London")
    assert int(reasons["budapest.mp4"].split()[0]) > 1400


def test_two_gigs_in_one_night_split_at_the_longest_pause() -> None:
    night = date(2025, 10, 28)
    gigs = [
        gig("cardinals-late", night, "Cardinals", note="late show after NewDad"),
        gig("newdad", night, "Cardinals", "NewDad", note="early part of the night"),
    ]
    clips = [
        clip("1.mp4", at(night, 20, 24)),
        clip("2.mp4", at(night, 21, 10)),
        clip("3.mp4", at(night, 21, 40)),
        clip("4.mp4", at(night, 23, 50)),
        clip("5.mp4", at(date(2025, 10, 29), 0, 5)),
    ]
    matched, _ = realclips.match(clips, gigs, set())
    assert [c.name for c in matched["newdad"]] == ["1.mp4", "2.mp4", "3.mp4"]
    assert [c.name for c in matched["cardinals-late"]] == ["4.mp4", "5.mp4"]


def test_latest_edits_keeps_only_alexs_last_trim_of_each_recording() -> None:
    t = at(NIGHT, 21)
    clips = [
        clip("PXL_1.mp4", t),
        clip("PXL_1~2.mp4", t),
        clip("PXL_1~3.mp4", t),
        clip("PXL_2.mp4", t + timedelta(minutes=1)),
    ]
    assert [c.name for c in realclips.latest_edits(clips)] == ["PXL_1~3.mp4", "PXL_2.mp4"]


def test_sets_start_after_a_pause_longer_than_fifteen_minutes() -> None:
    times = [(20, 0), (20, 5), (20, 40), (20, 52), (21, 30)]
    groups = realclips.sets([clip(f"{h}{m}.mp4", at(NIGHT, h, m)) for h, m in times])
    assert [[c.name for c in g] for g in groups] == [
        ["200.mp4", "205.mp4"],
        ["2040.mp4", "2052.mp4"],
        ["2130.mp4"],
    ]


def test_two_clips_in_one_long_headline_set_are_not_read_as_support_and_headliner() -> None:
    # an invented case: one headline set filmed twice, 30 minutes apart, both after 21:00
    during_headline = [clip("h1.mp4", at(NIGHT, 21, 10)), clip("h2.mp4", at(NIGHT, 21, 40))]
    bill = gig("night", NIGHT, "Support", "Headliner")
    assert len(realclips.sets(during_headline)) == 2  # two groups of clips…
    acts, rule = realclips.clip_acts(bill, during_headline)
    assert acts == {"h1.mp4": "Headliner", "h2.mp4": "Headliner"}  # …both from 21:00: headliner
    assert rule.startswith("by start time")
    # my real Pixies night (20:40 and 22:07): the sets fit the bill, support then headliner
    pixies = [clip("p1.mp4", at(NIGHT, 20, 40)), clip("p2.mp4", at(NIGHT, 22, 7))]
    assert realclips.clip_acts(gig("pixies", NIGHT, "BIG SPECIAL", "Pixies"), pixies) == (
        {"p1.mp4": "BIG SPECIAL", "p2.mp4": "Pixies"},
        "sets matched to the bill (a guess)",
    )
    # a 20-minute pause is two sets but no changeover (25 min): the start time decides, the
    # earlier set the support's, the night's last the headliner's
    close = [clip("a.mp4", at(NIGHT, 20, 0)), clip("b.mp4", at(NIGHT, 20, 20))]
    acts, rule = realclips.clip_acts(bill, close)
    assert acts == {"a.mp4": "Support", "b.mp4": "Headliner"} and rule.startswith("by start time")


def test_evening_minutes_run_past_midnight() -> None:
    assert realclips.evening_minutes(at(NIGHT, 21, 30)) == 1290
    assert realclips.evening_minutes(at(date(2026, 3, 26), 0, 30)) == 1470


def test_clip_acts_by_the_sets_by_the_bill_and_for_festivals() -> None:
    two_sets = [
        clip("s1.mp4", at(NIGHT, 20)),
        clip("h1.mp4", at(NIGHT, 21, 30)),
        clip("h2.mp4", at(NIGHT, 21, 40)),
    ]
    bill = gig("geese", NIGHT, "Westside Cowboy", "Geese")  # stage order: support, headliner
    assert realclips.clip_acts(bill, two_sets) == (
        {"s1.mp4": "Westside Cowboy", "h1.mp4": "Geese", "h2.mp4": "Geese"},
        "sets matched to the bill (a guess)",
    )
    one_set = two_sets[1:]
    assert realclips.clip_acts(bill, one_set)[0]["h1.mp4"] == "Geese"
    assert realclips.clip_acts(gig("keo", NIGHT, "Keo"), one_set) == (
        {"h1.mp4": "Keo", "h2.mp4": "Keo"},
        "one act on the bill",
    )
    festival = gig("truck", NIGHT, "The K's", "The Wombats", festival=True)
    assert realclips.clip_acts(festival, two_sets) == (
        {"s1.mp4": None, "h1.mp4": None, "h2.mp4": None},
        "festival: no act",
    )


def test_pick_spreads_over_the_night_and_drops_tiny_clips() -> None:
    clips = [
        clip(f"{i}.mp4", at(NIGHT, 20, i * 5), seconds=2 if i == 0 else 10 + i) for i in range(7)
    ]
    chosen = realclips.pick(clips, 3)
    assert len(chosen) == 3
    assert "0.mp4" not in [c.name for c in chosen]
    assert chosen == sorted(chosen, key=lambda c: c.taken)
    assert realclips.pick(clips[:3], 4) == clips[1:3]


def test_segment_keeps_short_clips_whole_and_cuts_long_ones_from_a_third_in() -> None:
    assert realclips.segment(30.0, 45) == (0.0, 30.0)
    assert realclips.segment(120.0, 45) == (25.0, 45)


def test_load_bills_puts_the_headliner_first_for_the_api(tmp_path: Path) -> None:
    path = tmp_path / "bills.json"
    path.write_text(
        json.dumps(
            [
                {
                    "key": "2026-03-25|Geese",
                    "date": "2026-03-25",
                    "venue": "Eventim Apollo",
                    "city": "London",
                    "acts": [{"name": "Westside Cowboy"}, {"name": "Geese", "mbid": "m1"}],
                    "foundOn": {
                        "externalId": "abc",
                        "url": "https://www.setlist.fm/x",
                        "artistMbid": "m1",
                    },
                },
                {"key": "empty", "date": "2026-01-01", "venue": "v", "city": "c", "acts": []},
            ]
        )
    )
    [loaded] = realclips.load_bills(path)
    assert loaded.headliner == Act("Geese", "m1")
    assert loaded.billing == ["Geese", "Westside Cowboy"]


def test_phone_clip_reads_gps_and_time_and_falls_back_to_the_file_name() -> None:
    probed: dict[str, str | float | None] = {
        "duration": 38.5,
        "creation_time": "2026-03-25T20:05:28.000000Z",
        "location": "+51.4910-000.2237/",
    }
    c = realclips.phone_clip(Path("PXL_20260325_200528000.mp4"), probed)
    assert (c.lat, c.lng, c.duration_s) == (51.491, -0.2237, 38.5)
    assert c.taken == datetime(2026, 3, 25, 20, 5, 28, tzinfo=UTC)
    bare = realclips.phone_clip(Path("PXL_20260325_200528000.mp4"), {"duration": 3})
    assert bare.taken == datetime(2026, 3, 25, 20, 5, 28, tzinfo=UTC)
    assert bare.lat is None


def test_old_phone_clips_use_the_corrected_dates_and_ignore_snapchat(tmp_path: Path) -> None:
    (tmp_path / "20250928_202134.mp4").write_bytes(b"x")
    (tmp_path / "Snapchat-1.mp4").write_bytes(b"x")
    (tmp_path / "index.csv").write_text(
        "file,taken_local,size,duration_s\n"
        "20250928_202134.mp4,2025-09-28T20:23:17+01:00,1,102\n"
        "Snapchat-1.mp4,2025-09-28T21:00:00+01:00,1,9\n"
        "gone.mp4,2025-09-28T21:30:00+01:00,1,9\n"
    )
    [c] = realclips.old_phone_clips(tmp_path)
    assert c.name == "20250928_202134.mp4"
    assert c.night == date(2025, 9, 28) and c.old_phone and c.duration_s == 102

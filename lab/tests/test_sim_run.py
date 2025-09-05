"""The simulator's session loop against a fake API (httpx.MockTransport)."""

import json
from dataclasses import replace
from pathlib import Path
from typing import Any

import httpx
import pytest

from simworld import NOW, WORLD
from soundcheck_lab.seed.api import Api
from soundcheck_lab.sim import check, click, personas
from soundcheck_lab.sim import run as sim

POSTS = list(WORLD.posts)


class FakeApi:
    """Serves For You from the synthetic world: each new session lists every post, in order."""

    def __init__(
        self, expire_first_feed: bool = False, unknown: bool = False, posts: int = len(POSTS)
    ) -> None:
        self.posts = POSTS[:posts]
        self.calls: list[tuple[str, str]] = []
        self.batches: list[int] = []
        self.feed_limits: list[int] = []
        self.sign_ins = 0
        self.expire_first_feed = expire_first_feed
        self.unknown = unknown
        self.sessions = 0

    def __call__(self, request: httpx.Request) -> httpx.Response:
        path, method = request.url.path, request.method
        self.calls.append((method, path))
        if path.startswith("/api/v1/auth/"):
            self.sign_ins += 1
            body = json.loads(request.content)
            return httpx.Response(
                200,
                json={
                    "accessToken": f"t{self.sign_ins}",
                    "user": {"id": f"user-{body['email']}", "handle": body.get("handle")},
                },
            )
        if path == "/api/v1/feed/home":
            if self.expire_first_feed:
                self.expire_first_feed = False
                return httpx.Response(401, json={"code": "UNAUTHORIZED"})
            limit = int(request.url.params["limit"])
            self.feed_limits.append(limit)
            cursor = request.url.params.get("cursor")
            if cursor is None:
                self.sessions += 1
                session, offset = f"00000000-0000-7000-9000-{self.sessions:012d}", 0
            else:
                session, raw = cursor.split(":")
                offset = int(raw)
            ids = self.posts[offset : offset + limit]
            if self.unknown:
                ids = ["00000000-0000-7000-8000-999999999999"]
            items = [
                {"position": offset + i, "post": {"id": p, "likedByMe": False}}
                for i, p in enumerate(ids)
            ]
            following = offset + limit < len(self.posts)
            return httpx.Response(
                200,
                json={
                    "sessionId": session,
                    "items": items,
                    "nextCursor": f"{session}:{offset + limit}" if following else None,
                },
            )
        if path == "/api/v1/events":
            self.batches.append(len(json.loads(request.content)["events"]))
            return httpx.Response(202)
        return httpx.Response(200, json={})


def client(fake: FakeApi) -> Api:
    return Api(
        "http://api.test",
        transport=httpx.MockTransport(fake),
        sleep=lambda _: None,
        auth_spacing_s=0,
    )


def persona(n: int = 0, length: int = 40) -> personas.SimPersona:
    return replace(personas.make(WORLD, 7, n + 1, 5, 14, NOW)[n], session_length=length)


def test_a_session_pages_through_for_you_and_sends_events_in_batches_of_100() -> None:
    fake = FakeApi()
    api = client(fake)
    p = persona(length=37)
    api.sign_in(p.account)
    log = sim.session(api, WORLD, p, "u", 2, 1, click.DEFAULT)
    assert log.served == 37  # exactly the session length: nothing served goes unseen
    assert fake.feed_limits == [10, 10, 10, 7]
    assert len(log.feed_sessions) == 1 and log.round == 2 and log.index == 1
    assert fake.batches and all(1 <= b <= 100 for b in fake.batches)
    assert sum(fake.batches) == log.events >= 2 * 37  # IMPRESSION and VIEW_START at least
    assert len(fake.batches) >= 2  # 37 items send more than 100 events


def test_a_session_stops_when_the_feed_runs_out() -> None:
    fake = FakeApi(posts=15)
    log = sim.session(client(fake), WORLD, persona(length=40), "u", 0, 0, click.DEFAULT)
    assert log.served == 15
    assert fake.feed_limits == [10, 10]


def test_an_expired_token_signs_in_again() -> None:
    fake = FakeApi(expire_first_feed=True)
    api = client(fake)
    p = persona(length=8)
    api.sign_in(p.account)
    log = sim.session(api, WORLD, p, "u", 0, 0, click.DEFAULT)
    assert fake.sign_ins == 2
    assert log.served == 8
    feed_calls = [c for c in fake.calls if c[1] == "/api/v1/feed/home"]
    assert len(feed_calls) == 2  # the 401, then the retry with the new token


def test_a_post_the_world_lacks_stops_the_run() -> None:
    api = client(FakeApi(unknown=True))
    with pytest.raises(sim.WorldMismatchError):
        sim.session(api, WORLD, persona(length=8), "u", 0, 0, click.DEFAULT)


def test_the_same_seed_makes_the_same_choices() -> None:
    def one() -> tuple[int, int, int]:
        fake = FakeApi()
        log = sim.session(client(fake), WORLD, persona(3), "u", 0, 2, click.DEFAULT)
        return log.engaged, log.likes, sum(fake.batches)

    assert one() == one()


def test_setup_signs_in_and_makes_the_starting_choices() -> None:
    fake = FakeApi()
    api = client(fake)
    p = persona(1)
    user_id = sim.setup(api, WORLD, p)
    assert user_id == f"user-{p.account.email}"
    puts = [path for method, path in fake.calls if method == "PUT"]
    assert sum(path.startswith("/api/v1/artists/") for path in puts) == len(p.follow_artists)
    assert sum(path.startswith("/api/v1/users/") for path in puts) == len(p.taste.followed_authors)
    assert sum(path.endswith("/attendance") for path in puts) == len(p.attend)
    assert ("PATCH", "/api/v1/me") in fake.calls


def test_a_run_goes_round_by_round_and_writes_its_record(tmp_path: Path) -> None:
    fake = FakeApi()
    api = client(fake)
    people = [replace(p, session_length=8) for p in personas.make(WORLD, 7, 4, 2, 3, NOW)]
    sleeps: list[float] = []
    lines: list[str] = []
    settings = sim.Settings(4, 2, 3, 7, "http://api.test", round_wait_s=12.0, workers=2)
    result = sim.run(settings, api, WORLD, people, lines.append, sleep=sleeps.append)
    assert sleeps == [12.0, 12.0]  # between rounds, not after the last
    assert len(result.sessions) == 8
    assert sorted((s.persona, s.round) for s in result.sessions) == sorted(
        (p.n, r) for p in people for r in p.rounds
    )
    assert lines[0].startswith("SYNTHETIC:")
    path = sim.write(result, tmp_path, "abc1234")
    record: dict[str, Any] = json.loads(path.read_text())
    assert path.name.startswith("sim-7-")
    assert record["synthetic"] is True and "click model" in record["note"]
    assert record["parameters"]["rounds"] == 3 and record["commit"] == "abc1234"
    assert record["counts"]["served"] == 8 * 8
    assert {s["round"] for s in record["sessions"]} <= {0, 1, 2}
    assert all(s["feedSessions"] for s in record["sessions"])
    assert record["personas"][0]["handle"] == "sim_7_000"


def test_the_check_relates_outcomes_to_hidden_affinity() -> None:
    people = personas.make(WORLD, 7, 30, 5, 14, NOW)
    users = {p.n: f"u{p.n}" for p in people}
    rows = []
    for p in people:
        for post_id in POSTS[:40]:
            affinity = click.true_affinity(p.taste, WORLD.item(post_id))
            rows.append((users[p.n], post_id, "s1", affinity > 0.3, False))
    rows.append(("u0", POSTS[0], "other-session", True, False))  # not this run's: ignored
    summary = check.summarise(rows, {"s1"}, WORLD, people, users)
    assert summary["servedRows"] == 30 * 40
    assert summary["spearmanAffinityPositive"] > 0.5
    quintiles = summary["byAffinityQuintile"]
    assert quintiles[-1]["positiveRate"] > quintiles[0]["positiveRate"]

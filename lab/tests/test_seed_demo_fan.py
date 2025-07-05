"""The demo fan's steps: a public profile and follows of its most-seen acts."""

import json
from pathlib import Path
from typing import Any

import httpx

from soundcheck_lab.seed import run
from soundcheck_lab.seed.api import Api
from soundcheck_lab.seed.personas import DEMO

GENRES = {g: i for i, g in enumerate(DEMO.genres, start=1)}


def act(artist_id: str, name: str, times: int) -> dict[str, Any]:
    return {"id": artist_id, "name": name, "times": times, "first": "x", "last": "x"}


class FakeApi:
    """Just enough of the API for the demo fan: /me, its stats, popular artists, artist follows."""

    def __init__(
        self,
        acts: list[dict[str, Any]],
        popular: list[str],
        following: set[str] | None = None,
        visibility: str = "PUBLIC",
    ) -> None:
        self.acts = acts
        self.popular = popular
        self.following = following or set()
        self.visibility = visibility
        self.calls: list[tuple[str, str]] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        method, path = request.method, request.url.path
        self.calls.append((method, path))
        if path.endswith("/auth/register"):
            user = {"id": "demo-id", "handle": DEMO.handle}
            return httpx.Response(200, json={"accessToken": "t", "expiresIn": 900, "user": user})
        if path == "/api/v1/genres":
            return httpx.Response(200, json=[])
        if path == "/api/v1/me":
            if method == "PATCH":
                body = json.loads(request.content)
                self.visibility = body.get("profileVisibility", self.visibility)
            me = {
                "id": "demo-id",
                "onboarded": True,
                "avatarUrl": "a",
                "profileVisibility": self.visibility,
            }
            return httpx.Response(200, json=me)
        if path == "/api/v1/users/demo-id/stats":
            totals = {"gigs": sum(a["times"] for a in self.acts), "acts": len(self.acts)}
            return httpx.Response(200, json={"totals": totals, "acts": self.acts})
        if path == "/api/v1/artists":
            items = [
                {"id": a, "name": a, "genres": [], "isFollowing": a in self.following}
                for a in self.popular
            ]
            return httpx.Response(200, json={"items": items})
        if path.startswith("/api/v1/artists/") and path.endswith("/follow"):
            artist = path.split("/")[4]
            (self.following.add if method == "PUT" else self.following.discard)(artist)
            return httpx.Response(204)
        return httpx.Response(404, json={"code": "NOT_FOUND", "detail": path})

    def writes(self) -> list[tuple[str, str]]:
        return [c for c in self.calls if c[0] in ("PUT", "DELETE", "PATCH")]


def client(server: FakeApi) -> Api:
    return Api(
        "http://api.test",
        transport=httpx.MockTransport(server),
        sleep=lambda _: None,
        auth_spacing_s=0,
    )


def legacy_state(tmp_path: Path) -> run.State:
    """A database seeded before demo_follows: the follows step already ran for the demo fan."""
    state = run.State.load(tmp_path / "state.json")
    state.section("follows")[DEMO.handle] = "demo-id"
    return state


def test_most_seen_ranks_by_gigs_then_name_and_keeps_the_top() -> None:
    acts = [act("b", "beta", 2), act("a", "Alpha", 2), act("c", "Gamma", 7), act("d", "delta", 1)]
    assert [a["id"] for a in run.most_seen(acts, top=3)] == ["c", "a", "b"]


def test_the_demo_fan_follows_its_most_seen_acts_and_drops_the_old_popular_ones_once(
    tmp_path: Path,
) -> None:
    acts = [act(f"act{i}", f"Act {i:02d}", 20 - i) for i in range(20)]
    # before demo_follows it followed five popular artists; one of them is also among its most seen
    server = FakeApi(acts, popular=["pop1", "pop2", "act3"], following={"pop1", "pop2", "act3"})
    state, logs = legacy_state(tmp_path), list[str]()
    run.demo_follows(client(server), GENRES, state, logs.append)

    top = {f"act{i}" for i in range(run.DEMO_TOP_ACTS)}
    assert server.following == top  # pop1 and pop2 undone, act3 kept
    assert ("DELETE", "/api/v1/artists/pop1/follow") in server.calls
    assert ("DELETE", "/api/v1/artists/act3/follow") not in server.calls
    assert state.section("demo_follows")["artists"] == sorted(top)
    assert "unfollowed the earlier pop1, pop2" in logs[-1]

    server.calls.clear()  # a second run with the same gigs changes nothing
    run.demo_follows(client(server), GENRES, run.State.load(tmp_path / "state.json"), logs.append)
    assert server.writes() == []


def test_more_gigs_bring_new_follows_but_nothing_is_undone_again(tmp_path: Path) -> None:
    server = FakeApi([act("a", "A", 3)], popular=["pop1"], following={"pop1"})
    state = legacy_state(tmp_path)
    run.demo_follows(client(server), GENRES, state, lambda _: None)
    server.acts.append(act("b", "B", 1))  # a later run added a gig
    server.following.add("pop1")  # followed by hand since: left alone
    server.calls.clear()
    run.demo_follows(client(server), GENRES, state, lambda _: None)
    assert server.writes() == [
        ("PUT", "/api/v1/artists/a/follow"),
        ("PUT", "/api/v1/artists/b/follow"),
    ]
    assert "pop1" in server.following


def test_without_gigs_the_demo_fan_follows_popular_artists_like_the_personas(
    tmp_path: Path,
) -> None:
    server = FakeApi([], popular=["pop1", "pop2"])
    run.demo_follows(client(server), GENRES, legacy_state(tmp_path), lambda _: None)
    assert server.following == {"pop1", "pop2"}
    assert not [c for c in server.calls if c[0] == "DELETE"]


def test_the_follows_step_no_longer_gives_the_demo_fan_popular_artists(tmp_path: Path) -> None:
    server = FakeApi([], popular=["pop1"])
    api = client(server)
    api.ids[DEMO.handle] = "demo-id"
    run.follows(api, [DEMO], GENRES, run.State.load(tmp_path / "state.json"), lambda _: None)
    assert not [c for c in server.calls if c[1].startswith("/api/v1/artists")]


def test_the_demo_fans_profile_stays_public_and_a_private_switch_is_undone(
    tmp_path: Path,
) -> None:
    state = run.State.load(tmp_path / "state.json")
    state.section("profiles")[DEMO.handle] = "demo-id"  # already seeded

    public = FakeApi([], popular=[])  # the default: nothing to send
    run.accounts(client(public), [DEMO], state, tmp_path, lambda _: None)
    assert public.writes() == []

    private = FakeApi([], popular=[], visibility="PRIVATE")  # made private by hand
    run.accounts(client(private), [DEMO], state, tmp_path, lambda _: None)
    assert private.visibility == "PUBLIC"
    assert private.writes() == [("PATCH", "/api/v1/me")]

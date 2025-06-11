import json
from pathlib import Path

import httpx
import pytest

from soundcheck_lab.seed.api import Api, ApiError
from soundcheck_lab.seed.personas import PERSONAS

MAYA = PERSONAS[0]


class FakeClock:
    def __init__(self) -> None:
        self.now = 0.0
        self.sleeps: list[float] = []

    def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.now += seconds

    def __call__(self) -> float:
        return self.now


def auth_ok(token: str = "t1") -> httpx.Response:
    return httpx.Response(
        200,
        json={"accessToken": token, "expiresIn": 900, "user": {"id": "u1", "handle": MAYA.handle}},
    )


def make(handler: object, clock: FakeClock | None = None) -> Api:
    clock = clock or FakeClock()
    return Api(
        "http://api.test",
        transport=httpx.MockTransport(handler),  # type: ignore[arg-type]
        sleep=clock.sleep,
        clock=clock,
    )


def test_an_existing_account_logs_in_instead_and_auth_calls_are_paced() -> None:
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.url.path)
        if request.url.path.endswith("/register"):
            return httpx.Response(409, json={"code": "EMAIL_TAKEN", "detail": "taken"})
        return auth_ok()

    clock = FakeClock()
    api = make(handler, clock)
    assert api.sign_in(MAYA) == "t1"
    assert seen == ["/api/v1/auth/register", "/api/v1/auth/login"]
    assert clock.sleeps == [6.5]  # the login waited for the register's slot
    assert api.ids[MAYA.handle] == "u1"


def test_an_expired_token_signs_in_again_once() -> None:
    tokens: list[str | None] = []

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.startswith("/api/v1/auth/"):
            return auth_ok(token=f"t{len(tokens) + 1}")
        tokens.append(request.headers.get("Authorization"))
        return httpx.Response(401) if len(tokens) == 1 else httpx.Response(200, json={"ok": True})

    assert make(handler).get(MAYA, "/api/v1/me") == {"ok": True}
    assert tokens == ["Bearer t1", "Bearer t2"]


def test_429_is_waited_out_using_retry_after() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        if request.url.path.startswith("/api/v1/auth/"):
            return auth_ok()
        calls += 1
        if calls == 1:
            return httpx.Response(429, headers={"Retry-After": "12"})
        return httpx.Response(200, json=[1])

    clock = FakeClock()
    assert make(handler, clock).get(MAYA, "/api/v1/genres") == [1]
    assert 12.0 in clock.sleeps


def test_errors_carry_the_problem_code() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.startswith("/api/v1/auth/"):
            return auth_ok()
        return httpx.Response(
            400, json={"code": "GIG_NEEDS_AN_ACT", "detail": "Name at least one act"}
        )

    with pytest.raises(ApiError) as raised:
        make(handler).post(MAYA, "/api/v1/gigs", {})
    assert (raised.value.status, raised.value.code) == (400, "GIG_NEEDS_AN_ACT")


def test_single_upload_puts_the_bytes_then_completes(tmp_path: Path) -> None:
    file = tmp_path / "a.png"
    file.write_bytes(b"pngbytes")
    log: list[tuple[str, str, bytes]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        log.append((request.method, str(request.url), request.content))
        if request.url.path.startswith("/api/v1/auth/"):
            return auth_ok()
        if request.url.path == "/api/v1/media/uploads":
            assert json.loads(request.content) == {
                "kind": "IMAGE",
                "contentType": "image/png",
                "bytes": 8,
            }
            return httpx.Response(
                200,
                json={
                    "mediaId": "m1",
                    "strategy": "SINGLE",
                    "putUrl": "http://s3.test/put",
                    "headers": {"Content-Type": "image/png"},
                },
            )
        if request.url.host == "s3.test":
            assert request.headers["Content-Type"] == "image/png"
            return httpx.Response(200)
        return httpx.Response(200, json={"status": "UPLOADED"})

    media = make(handler).upload(MAYA, file, "image/png", kind="IMAGE")
    assert media.media_id == "m1"
    assert [(m, u) for m, u, _ in log[-2:]] == [
        ("PUT", "http://s3.test/put"),
        ("POST", "http://api.test/api/v1/media/uploads/m1/complete"),
    ]
    assert log[-2][2] == b"pngbytes"


def test_multipart_upload_sends_each_part_and_completes_with_their_etags(tmp_path: Path) -> None:
    file = tmp_path / "big.mp4"
    file.write_bytes(b"aaaabbbbcc")
    puts: dict[str, bytes] = {}
    completed: list[object] = []

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.startswith("/api/v1/auth/"):
            return auth_ok()
        if request.url.path == "/api/v1/media/uploads":
            return httpx.Response(
                200,
                json={
                    "mediaId": "m2",
                    "strategy": "MULTIPART",
                    "partSize": 4,
                    "parts": [{"partNumber": n, "url": f"http://s3.test/p{n}"} for n in (3, 1, 2)],
                },
            )
        if request.url.host == "s3.test":
            puts[request.url.path] = request.content
            return httpx.Response(200, headers={"ETag": f'"e{request.url.path[-1]}"'})
        completed.append(json.loads(request.content))
        return httpx.Response(200, json={"status": "UPLOADED"})

    assert make(handler).upload(MAYA, file, "video/mp4").strategy == "MULTIPART"
    assert puts == {"/p1": b"aaaa", "/p2": b"bbbb", "/p3": b"cc"}
    assert completed == [{"parts": [{"partNumber": n, "etag": f'"e{n}"'} for n in (1, 2, 3)]}]


def test_pages_follows_the_cursor_to_the_end() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.startswith("/api/v1/auth/"):
            return auth_ok()
        if request.url.params.get("cursor") == "c1":
            return httpx.Response(200, json={"items": [3], "nextCursor": None})
        return httpx.Response(200, json={"items": [1, 2], "nextCursor": "c1"})

    assert list(make(handler).pages(MAYA, "/api/v1/me/posts")) == [1, 2, 3]

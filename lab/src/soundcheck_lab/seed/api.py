"""A small client for the Soundcheck API, used by the demo seeding.

It behaves like a polite app: one account per persona, register (or log in when the account exists),
pace the auth calls under the API's per-IP limit (10 a minute), wait out 429s, log in
again when an access token expires, and upload media the way the app does (presigned PUT, or parts
for big files)."""

from __future__ import annotations

import threading
import time
from collections.abc import Callable, Iterator, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx

from soundcheck_lab.seed.personas import Persona, password

Json = Any  # parsed JSON: dicts, lists and scalars as the API returns them
AUTH_SPACING_S = 6.5  # 10 auth calls a minute per IP, with a margin
MAX_RETRIES = 8


class ApiError(Exception):
    def __init__(self, status: int, code: str | None, detail: str) -> None:
        super().__init__(f"{status} {code or ''} {detail}".strip())
        self.status = status
        self.code = code


@dataclass(frozen=True)
class Media:
    media_id: str
    strategy: str


class Api:
    def __init__(
        self,
        base_url: str,
        *,
        transport: httpx.BaseTransport | None = None,
        sleep: Callable[[float], None] = time.sleep,
        clock: Callable[[], float] = time.monotonic,
        auth_spacing_s: float = AUTH_SPACING_S,
    ) -> None:
        self._http = httpx.Client(base_url=base_url, transport=transport, timeout=60)
        self._storage = httpx.Client(
            transport=transport, timeout=300
        )  # presigned URLs are absolute
        self._sleep = sleep
        self._clock = clock
        self._auth_spacing_s = auth_spacing_s
        self._last_auth = -1e9
        self._auth_lock = threading.Lock()  # the simulator signs in from several threads
        self._tokens: dict[str, str] = {}
        self.ids: dict[str, str] = {}  # handle → user id
        self.calls = 0

    def close(self) -> None:
        self._http.close()
        self._storage.close()

    # ── transport ────────────────────────────────────────────────────────────────────────────────
    def _send(
        self, method: str, url: str, *, token: str | None = None, **kw: Any
    ) -> httpx.Response:
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        for attempt in range(MAX_RETRIES):
            self.calls += 1
            response = self._http.request(method, url, headers=headers, **kw)
            if response.status_code == 429:
                self._sleep(_retry_after(response, default=5.0 * (attempt + 1)))
                continue
            if response.status_code in (502, 503, 504):
                self._sleep(2.0 * (attempt + 1))
                continue
            return response
        return response

    @staticmethod
    def _check(response: httpx.Response) -> Json:
        if response.status_code >= 400:
            try:
                problem = response.json()
            except ValueError:
                problem = {}
            raise ApiError(
                response.status_code,
                problem.get("code"),
                problem.get("detail") or response.text[:200],
            )
        return response.json() if response.content else None

    # ── accounts ─────────────────────────────────────────────────────────────────────────────────
    def _pace_auth(self) -> None:
        with self._auth_lock:
            wait = self._last_auth + self._auth_spacing_s - self._clock()
            if wait > 0:
                self._sleep(wait)
            self._last_auth = self._clock()

    def sign_in(self, persona: Persona) -> str:
        """Registers the persona, or logs in when the email is taken (a re-run). Returns the access
        token."""
        self._pace_auth()
        response = self._send(
            "POST",
            "/api/v1/auth/register",
            json={
                "email": persona.email,
                "password": password(),
                "handle": persona.handle,
                "displayName": persona.display_name,
            },
        )
        if response.status_code == 409:
            self._pace_auth()
            response = self._send(
                "POST",
                "/api/v1/auth/login",
                json={
                    "email": persona.email,
                    "password": password(),
                },
            )
        body = self._check(response)
        self._tokens[persona.handle] = body["accessToken"]
        self.ids[persona.handle] = body["user"]["id"]
        return str(body["accessToken"])

    def request(self, persona: Persona, method: str, url: str, **kw: Any) -> Json:
        """One API call as {@code persona}; signs in first if needed, and again once on a 401
        (expired token)."""
        token = self._tokens.get(persona.handle) or self.sign_in(persona)
        response = self._send(method, url, token=token, **kw)
        if response.status_code == 401:
            response = self._send(method, url, token=self.sign_in(persona), **kw)
        return self._check(response)

    def get(self, persona: Persona, url: str, **params: Any) -> Json:
        return self.request(
            persona, "GET", url, params={k: v for k, v in params.items() if v is not None}
        )

    def post(self, persona: Persona, url: str, body: Json = None) -> Json:
        return self.request(persona, "POST", url, json=body)

    def put(self, persona: Persona, url: str, body: Json = None) -> Json:
        return self.request(persona, "PUT", url, json=body)

    def patch(self, persona: Persona, url: str, body: Json = None) -> Json:
        return self.request(persona, "PATCH", url, json=body)

    def pages(self, persona: Persona, url: str, limit: int = 50, **params: Any) -> Iterator[Json]:
        """Every item of a keyset-paginated list (items + nextCursor)."""
        cursor: str | None = None
        while True:
            page = self.get(persona, url, limit=limit, cursor=cursor, **params)
            yield from page["items"]
            cursor = page.get("nextCursor")
            if not cursor:
                return

    # ── media ────────────────────────────────────────────────────────────────────────────────────
    def upload(
        self,
        persona: Persona,
        path: Path,
        content_type: str,
        kind: str = "VIDEO",
        meta: Mapping[str, int] | None = None,
    ) -> Media:
        """Opens an upload, PUTs the bytes (one request, or one per part), then completes it."""
        size = path.stat().st_size
        ticket = self.post(
            persona,
            "/api/v1/media/uploads",
            {
                "kind": kind,
                "contentType": content_type,
                "bytes": size,
                **(meta or {}),
            },
        )
        media_id = ticket["mediaId"]
        if ticket["strategy"] == "SINGLE":
            headers = dict(ticket.get("headers") or {"Content-Type": content_type})
            self._put(ticket["putUrl"], path.read_bytes(), headers)
            self.post(persona, f"/api/v1/media/uploads/{media_id}/complete", {})
        else:
            part_size = int(ticket["partSize"])
            parts = []
            with path.open("rb") as fh:
                for part in sorted(ticket["parts"], key=lambda p: p["partNumber"]):
                    fh.seek((part["partNumber"] - 1) * part_size)
                    response = self._put(part["url"], fh.read(part_size), {})
                    parts.append(
                        {"partNumber": part["partNumber"], "etag": response.headers["ETag"]}
                    )
            self.post(persona, f"/api/v1/media/uploads/{media_id}/complete", {"parts": parts})
        return Media(media_id, ticket["strategy"])

    def _put(self, url: str, data: bytes, headers: Mapping[str, str]) -> httpx.Response:
        for attempt in range(4):
            response = self._storage.put(url, content=data, headers=dict(headers))
            if response.status_code < 500:
                break
            self._sleep(2.0 * (attempt + 1))
        if response.status_code >= 300:
            raise ApiError(response.status_code, "STORAGE_PUT_FAILED", response.text[:200])
        return response


def _retry_after(response: httpx.Response, default: float) -> float:
    try:
        return max(1.0, float(response.headers.get("Retry-After", default)))
    except ValueError:
        return default

"""`publish` against a mock API. The responses in fixtures/publish/ were recorded from the
lab's own stack (2025-09-08): the config list after a LAB draft was published, and the 400 for an
invalid config."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import httpx
import pytest

from soundcheck_lab import publish as pub

FIXTURES = Path(__file__).parent / "fixtures" / "publish"
CONFIGS: list[dict[str, Any]] = json.loads((FIXTURES / "configs.json").read_text(encoding="utf-8"))
INVALID: dict[str, Any] = json.loads((FIXTURES / "invalid.json").read_text(encoding="utf-8"))
ADMIN = pub.AdminSettings(email="admin@example.test", password="correct horse battery")

MODEL: dict[str, Any] = {
    "featureSchemaVersion": 1,
    "bias": -3.19,
    "weights": {**{k: 0.5 for k in CONFIGS[1]["weights"] if k != "bias"}, "position_prior": 0.0},
    "position_prior": 0.0,
}
METRICS: dict[str, Any] = {
    "auc": 0.7654,
    "logLoss": 0.4360,
    "ndcg10": 0.6469,
    "ndcg10ExplorationOnly": 0.8541,
    "rows": 4804,
    "sessions": 205,
    "data": "simulated",
    "activeNdcg10": 0.5737,
    "report": "lab/reports/20250908T213423Z",
}


class FakeApi:
    def __init__(self, *, login: int = 200, create: int = 201, configs: Any = None) -> None:
        self.login = login
        self.create = create
        self.configs = CONFIGS if configs is None else configs
        self.posted: dict[str, Any] | None = None
        self.auth: list[str | None] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/api/v1/auth/login":
            body = json.loads(request.content)
            assert body == {"email": ADMIN.email, "password": ADMIN.password}
            if self.login != 200:
                return httpx.Response(self.login, json={"status": self.login, "code": "BAD_LOGIN"})
            return httpx.Response(200, json={"accessToken": "tok", "user": {"id": "a"}})
        self.auth.append(request.headers.get("Authorization"))
        if request.method == "GET" and path == pub.CONFIGS:
            return httpx.Response(200, json=self.configs)
        if request.method == "POST" and path == pub.CONFIGS:
            self.posted = json.loads(request.content)
            if self.create == 400:
                return httpx.Response(400, json=INVALID)
            return httpx.Response(201, json=CONFIGS[0])
        return httpx.Response(404)


def run(api: FakeApi) -> pub.Published:
    return pub.publish(
        "http://lab.test",
        MODEL,
        METRICS,
        METRICS["report"],
        settings=ADMIN,
        transport=httpx.MockTransport(api),
    )


def test_publish_posts_a_lab_draft_with_the_active_params() -> None:
    api = FakeApi()
    created = run(api)
    assert created == pub.Published(CONFIGS[0]["id"], 2, "DRAFT")
    body = api.posted
    assert body is not None
    assert body["source"] == "LAB"
    assert body["params"] == CONFIGS[1]["params"]  # copied from the ACTIVE config, not learned
    assert body["weights"]["bias"] == -3.19
    assert body["weights"]["position_prior"] == 0.0
    assert set(body["weights"]) == set(CONFIGS[1]["weights"])
    assert body["metrics"]["data"] == "simulated"
    for key in ("auc", "logLoss", "ndcg10", "ndcg10ExplorationOnly", "rows", "sessions"):
        assert key in body["metrics"]
    assert "lab/reports/20250908T213423Z" in body["notes"]
    assert "simulated" in body["notes"]
    assert len(body["notes"]) <= pub.NOTES_LIMIT
    assert api.auth == ["Bearer tok", "Bearer tok"]


def test_a_failed_sign_in_is_an_error() -> None:
    with pytest.raises(pub.PublishError, match="sign-in failed: HTTP 401"):
        run(FakeApi(login=401))


def test_an_invalid_config_reports_every_broken_rule() -> None:
    with pytest.raises(pub.PublishError) as e:
        run(FakeApi(create=400))
    assert "RANKING_CONFIG_INVALID" in str(e.value)
    assert "halfLifeHours" in str(e.value)


def test_without_an_active_config_there_are_no_params_to_copy() -> None:
    drafts = [c for c in CONFIGS if c["status"] != "ACTIVE"]
    with pytest.raises(pub.PublishError, match="no ACTIVE"):
        run(FakeApi(configs=drafts))


def test_missing_admin_credentials_stop_before_any_call() -> None:
    api = FakeApi()
    with pytest.raises(pub.PublishError, match="SOUNDCHECK_ADMIN_EMAIL"):
        pub.publish(
            "http://lab.test",
            MODEL,
            METRICS,
            "r",
            settings=pub.AdminSettings(email="", password=""),
            transport=httpx.MockTransport(api),
        )
    assert api.auth == []

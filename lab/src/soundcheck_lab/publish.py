"""`soundcheck-lab publish`: a trained config as a LAB draft, through the API.

Signs in as the admin (``SOUNDCHECK_ADMIN_EMAIL/PASSWORD`` from the environment or ``.env``),
reads the active config's params (the lab learns weights, not params) and
``POST /api/v1/admin/ranking/configs`` with ``source = LAB``, the held-out metrics
(``data: "simulated"``) and a note with the report path. The API validates it as it validates a
console draft. Nothing is activated.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx
from pydantic_settings import BaseSettings, SettingsConfigDict

from soundcheck_lab.schema import DEFAULT_SCHEMA_PATH

ENV_FILE = DEFAULT_SCHEMA_PATH.parents[2] / ".env"
CONFIGS = "/api/v1/admin/ranking/configs"
NOTES_LIMIT = 2000


class AdminSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="SOUNDCHECK_ADMIN_", env_file=ENV_FILE, extra="ignore"
    )

    email: str = ""
    password: str = ""


class PublishError(Exception):
    pass


@dataclass(frozen=True, slots=True)
class Published:
    id: str
    version: int
    status: str


def _problem(response: httpx.Response) -> str:
    try:
        body = response.json()
    except ValueError:
        return f"HTTP {response.status_code}"
    if not isinstance(body, dict):
        return f"HTTP {response.status_code}"
    detail = body.get("detail") or body.get("title") or ""
    errors = body.get("errors") or []
    messages = "; ".join(str(e.get("message")) for e in errors if isinstance(e, dict))
    code = body.get("code") or ""
    return f"HTTP {response.status_code} {code} {detail} {messages}".strip()


def sign_in(http: httpx.Client, settings: AdminSettings) -> str:
    if not settings.email or not settings.password:
        raise PublishError(
            "SOUNDCHECK_ADMIN_EMAIL and SOUNDCHECK_ADMIN_PASSWORD must be set (.env)"
        )
    response = http.post(
        "/api/v1/auth/login", json={"email": settings.email, "password": settings.password}
    )
    if response.status_code != 200:
        raise PublishError(f"admin sign-in failed: {_problem(response)}")
    token = response.json().get("accessToken")
    if not isinstance(token, str):
        raise PublishError("admin sign-in: no access token in the response")
    return token


def active_params(http: httpx.Client, token: str) -> dict[str, Any]:
    response = http.get(CONFIGS, headers={"Authorization": f"Bearer {token}"})
    if response.status_code != 200:
        raise PublishError(f"listing ranking configs failed: {_problem(response)}")
    for config in response.json():
        if config.get("status") == "ACTIVE":
            params: dict[str, Any] = config["params"]
            return params
    raise PublishError("no ACTIVE ranking config to copy the params from")


def request_body(
    model: dict[str, Any], metrics: dict[str, Any], params: dict[str, Any], report: str
) -> dict[str, Any]:
    """The draft: the learned weights (``position_prior`` 0), the active params, the metrics."""
    weights = {str(k): float(v) for k, v in model["weights"].items()}
    weights["bias"] = float(model["bias"])
    weights["position_prior"] = 0.0
    note = (
        f"Lab draft (simulated data). Report: {report}. "
        f"Held-out NDCG@10 {_or_dash(metrics.get('ndcg10'))} vs active "
        f"{_or_dash(metrics.get('activeNdcg10'))}; "
        f"exploration-only {_or_dash(metrics.get('ndcg10ExplorationOnly'))}; "
        f"{metrics['rows']} test rows, {metrics['sessions']} sessions."
    )
    return {
        "weights": weights,
        "params": params,
        "source": "LAB",
        "metrics": metrics,
        "notes": note[:NOTES_LIMIT],
    }


def _or_dash(v: Any) -> str:
    return f"{v:.4f}" if isinstance(v, float) else "—"


def publish(
    api_url: str,
    model: dict[str, Any],
    metrics: dict[str, Any],
    report: str,
    *,
    settings: AdminSettings | None = None,
    transport: httpx.BaseTransport | None = None,
) -> Published:
    settings = settings or AdminSettings()
    with httpx.Client(base_url=api_url, transport=transport, timeout=30) as http:
        token = sign_in(http, settings)
        body = request_body(model, metrics, active_params(http, token), report)
        response = http.post(CONFIGS, json=body, headers={"Authorization": f"Bearer {token}"})
        if response.status_code != 201:
            raise PublishError(f"publishing the draft failed: {_problem(response)}")
        created = response.json()
        return Published(str(created["id"]), int(created["version"]), str(created["status"]))


def report_path_for_notes(report_dir: Path, root: Path) -> str:
    try:
        return str(report_dir.resolve().relative_to(root.resolve()))
    except ValueError:
        return str(report_dir)

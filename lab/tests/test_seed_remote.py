"""The seed's report against a remote API: the database figure is skipped."""

from __future__ import annotations

from typing import Any

from soundcheck_lab.seed import engage
from soundcheck_lab.seed.personas import PERSONAS


class FullFeed:
    """Every persona's For You has 30 items, in three pages."""

    def get(self, persona: Any, path: str, **params: Any) -> dict[str, Any]:
        page = 0 if params.get("cursor") is None else int(params["cursor"])
        more = page < 2
        return {"items": [{}] * 10, "nextCursor": str(page + 1) if more else None}


def run_report(api_url: str, environ: dict[str, str]) -> tuple[engage.Report, list[str], int]:
    lines: list[str] = []
    calls = 0

    def stats() -> tuple[int, int]:
        nonlocal calls
        calls += 1
        return 10, 8

    result = engage.report(
        FullFeed(),  # type: ignore[arg-type]
        PERSONAS[:2],
        lines.append,
        api_url,
        stats=stats,
        environ=environ,
    )
    return result, lines, calls


def test_remote_api_skips_the_database_figure() -> None:
    result, lines, calls = run_report("https://d1example.cloudfront.net", {})
    assert calls == 0
    assert result.posts is None and result.stats_share is None
    assert result.feed_sizes == {PERSONAS[0].handle: 30, PERSONAS[1].handle: 30}
    assert "post_stats: n/a (remote)" in lines


def test_local_api_reads_the_database() -> None:
    result, lines, calls = run_report("http://localhost:8088", {})
    assert calls == 1
    assert result.stats_share == 0.8
    assert any(line.startswith("post_stats: 8/10") for line in lines)


def test_db_host_set_reads_the_database_for_a_remote_api() -> None:
    _, _, calls = run_report("https://d1example.cloudfront.net", {"DB_HOST": "db.example"})
    assert calls == 1


def test_database_reachable() -> None:
    assert engage.database_reachable("http://127.0.0.1:8088", {})
    assert not engage.database_reachable("https://d1example.cloudfront.net", {})
    assert not engage.database_reachable("https://d1example.cloudfront.net", {"DB_HOST": ""})

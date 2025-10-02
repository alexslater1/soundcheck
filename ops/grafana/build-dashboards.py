#!/usr/bin/env python3
"""Writes ops/grafana/dashboards/{api,feed,media,ingestion}.json.

The dashboards are code: edit this file and re-run it (`python3 ops/grafana/build-dashboards.py`), never the
JSON. Metric names are what the Prometheus inside grafana/otel-lgtm stores for Micrometer's OTLP export, read
from its datasource API on 2025-10-02: dots become underscores, timers gain `_milliseconds` (`_bucket`, `_sum`,
`_count`, `_max`), counters `_total`. Every panel filters on `deployment_environment="$env"`, so the perf API
(`perf`) and the local stack's (`local`) never mix on one graph.
"""

import json
from pathlib import Path

OUT = Path(__file__).resolve().parent / "dashboards"
DS = {"type": "prometheus", "uid": "prometheus"}
ENV = 'deployment_environment="$env"'
RATE = "$__rate_interval"


def target(expr: str, legend: str, ref: str = "A") -> dict:
    return {"datasource": DS, "expr": expr, "legendFormat": legend, "refId": ref, "range": True}


def panel(pid: int, title: str, targets: list[dict], unit: str, x: int, y: int, w: int = 12, h: int = 8,
          description: str = "", stack: bool = False) -> dict:
    return {
        "id": pid,
        "type": "timeseries",
        "title": title,
        "description": description,
        "datasource": DS,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "targets": targets,
        "fieldConfig": {
            "defaults": {
                "unit": unit,
                "custom": {"lineWidth": 1, "fillOpacity": 10 if stack else 0,
                           "stacking": {"mode": "normal" if stack else "none"}, "showPoints": "never"},
            },
            "overrides": [],
        },
        "options": {"legend": {"displayMode": "table", "placement": "bottom", "calcs": ["lastNotNull", "max"]},
                    "tooltip": {"mode": "multi", "sort": "desc"}},
    }


def row(pid: int, title: str, y: int) -> dict:
    return {"id": pid, "type": "row", "title": title, "collapsed": False, "gridPos": {"x": 0, "y": y, "w": 24, "h": 1},
            "panels": []}


def dashboard(uid: str, title: str, description: str, panels: list[dict]) -> dict:
    return {
        "uid": uid,
        "title": title,
        "description": description,
        "tags": ["soundcheck", "generated"],
        "timezone": "utc",
        "refresh": "10s",
        "time": {"from": "now-30m", "to": "now"},
        "schemaVersion": 39,
        "editable": True,
        "templating": {"list": [{
            "name": "env",
            "label": "Environment",
            "type": "query",
            "datasource": DS,
            "query": {"query": "label_values(jvm_info, deployment_environment)", "refId": "env"},
            "definition": "label_values(jvm_info, deployment_environment)",
            "refresh": 2,
            "includeAll": False,
            "multi": False,
            "current": {},
            "description": "deployment.environment: perf (the perf stack's load runs) or local (the local stack)",
        }]},
        "panels": panels,
    }


def p95(metric: str, by: str, extra: str = "") -> str:
    sel = f"{ENV}{extra}"
    return f"histogram_quantile(0.95, sum by (le, {by}) (rate({metric}_bucket{{{sel}}}[{RATE}])))"


def mean(metric: str, by: str, extra: str = "") -> str:
    sel = f"{ENV}{extra}"
    return (f"sum by ({by}) (rate({metric}_sum{{{sel}}}[{RATE}])) / "
            f"sum by ({by}) (rate({metric}_count{{{sel}}}[{RATE}]))")


API_URI = ', uri=~"/api/.*"'
HTTP = "http_server_requests_milliseconds"

api = dashboard("soundcheck-api", "Soundcheck · API", "RED per endpoint, JVM and Hikari.", [
    row(1, "Requests (RED per endpoint)", 0),
    panel(2, "Rate per endpoint", [target(
        f"sum by (method, uri) (rate({HTTP}_count{{{ENV}{API_URI}}}[{RATE}]))", "{{method}} {{uri}}")],
        "reqps", 0, 1, description="Requests a second, every /api endpoint."),
    panel(3, "Errors per endpoint (4xx and 5xx)", [target(
        f'sum by (method, uri, status) (rate({HTTP}_count{{{ENV}{API_URI}, outcome=~"CLIENT_ERROR|SERVER_ERROR"}}[{RATE}]))',
        "{{status}} {{method}} {{uri}}")], "reqps", 12, 1),
    panel(4, "p95 latency per endpoint", [target(p95(HTTP, "method, uri", API_URI), "{{method}} {{uri}}")],
          "ms", 0, 9, description="From the percentile histograms the perf profile turns on. The k6 run "
                                  "file stays the measurement of record."),
    panel(5, "Mean latency per endpoint", [target(mean(HTTP, "method, uri", API_URI), "{{method}} {{uri}}")],
          "ms", 12, 9),
    row(6, "JVM", 17),
    panel(7, "Heap used / max", [
        target(f'sum(jvm_memory_used_bytes{{{ENV}, area="heap"}})', "used"),
        target(f'sum(jvm_memory_max_bytes{{{ENV}, area="heap"}})', "max", "B")], "bytes", 0, 18, w=8),
    panel(8, "GC pause time a second", [target(
        f"sum by (action, cause) (rate(jvm_gc_pause_milliseconds_sum{{{ENV}}}[{RATE}]))", "{{action}} ({{cause}})")],
        "ms", 8, 18, w=8),
    panel(9, "Process CPU and threads", [
        target(f"process_cpu_usage{{{ENV}}}", "process CPU (share of the CPUs it may use)"),
        target(f"jvm_threads_live{{{ENV}}}", "live threads", "B")], "short", 16, 18, w=8),
    row(10, "Hikari (database pool)", 26),
    panel(11, "Connections", [
        target(f"sum by (pool) (hikaricp_connections_active{{{ENV}}})", "active {{pool}}"),
        target(f"sum by (pool) (hikaricp_connections_idle{{{ENV}}})", "idle {{pool}}", "B"),
        target(f"sum by (pool) (hikaricp_connections_pending{{{ENV}}})", "pending {{pool}}", "C"),
        target(f"sum by (pool) (hikaricp_connections_max{{{ENV}}})", "max {{pool}}", "D")], "short", 0, 27),
    panel(12, "Connection acquire time (p95) and timeouts", [
        target(p95("hikaricp_connections_acquire_milliseconds", "pool"), "acquire p95 {{pool}}"),
        target(f"sum by (pool) (rate(hikaricp_connections_timeout_total{{{ENV}}}[{RATE}]))", "timeouts/s {{pool}}", "B")],
        "ms", 12, 27, description="Pending connections and a rising acquire time mean the pool, not Postgres, is the queue."),
])

FEED_STAGE = "soundcheck_feed_stage_milliseconds"
feed = dashboard("soundcheck-feed", "Soundcheck · Feed", "Stage latencies, candidates by source, session sizes.", [
    row(1, "For You builds", 0),
    panel(2, "Stage latency p95", [target(p95(FEED_STAGE, "stage"), "{{stage}}")], "ms", 0, 1,
          description="soundcheck.feed.stage: one timer per pipeline stage of a first page (HomeFeedService)."),
    panel(3, "Stage latency mean", [target(mean(FEED_STAGE, "stage"), "{{stage}}")], "ms", 12, 1, stack=True,
          description="Stacked, the means add up to roughly one build."),
    panel(4, "Candidates per build, by source", [target(mean("soundcheck_feed_candidates", "source"), "{{source}}")],
          "short", 0, 9, stack=True),
    panel(5, "Session size (items per build)", [
        target(mean("soundcheck_feed_session_size", "job"), "mean"),
        target(f"max(soundcheck_feed_session_size_max{{{ENV}}})", "max", "B")], "short", 12, 9),
    row(6, "Feed endpoints", 17),
    panel(7, "Feed requests a second", [
        target(f'sum by (uri) (rate({HTTP}_count{{{ENV}, uri=~"/api/v1/feed/.*"}}[{RATE}]))', "{{uri}}"),
        target(f"sum(rate(soundcheck_feed_session_size_count{{{ENV}}}[{RATE}]))", "For You builds (first pages)", "B")],
        "reqps", 0, 18),
    panel(8, "Feed p95 latency", [target(p95(HTTP, "uri", ', uri=~"/api/v1/feed/.*"'), "{{uri}}")], "ms", 12, 18,
          description="A first page and a later page share /api/v1/feed/home; k6 tags them apart (feed_first, feed_later)."),
    panel(9, "Events ingested by type", [target(
        f"sum by (type) (rate(soundcheck_events_ingested_total{{{ENV}}}[{RATE}]))", "{{type}}")], "short", 0, 26,
        w=24, stack=True),
])

media = dashboard("soundcheck-media", "Soundcheck · Media pipeline", "Queue wait, stage timings, outcomes. "
                  "Worker metrics: empty for env=perf, which runs no worker.", [
    panel(1, "Queue wait p95", [target(p95("soundcheck_media_queue_wait_milliseconds", "job"), "p95")], "ms", 0, 0),
    panel(2, "Transcode stage p95", [target(p95("soundcheck_media_transcode_duration_milliseconds", "stage"), "{{stage}}")],
          "ms", 12, 0),
    panel(3, "Jobs by outcome", [target(
        f"sum by (outcome) (increase(soundcheck_media_jobs_total{{{ENV}}}[$__interval]))", "{{outcome}}")],
        "short", 0, 8, stack=True),
    panel(4, "Bytes by kind (mean per job)", [target(mean("soundcheck_media_bytes", "kind"), "{{kind}}")],
          "bytes", 12, 8),
    panel(5, "Upload endpoints", [target(
        f'sum by (method, uri) (rate({HTTP}_count{{{ENV}, uri=~"/api/v1/media/.*"}}[{RATE}]))', "{{method}} {{uri}}")],
        "reqps", 0, 16, w=24),
])

ingestion = dashboard("soundcheck-ingestion", "Soundcheck · Ingestion", "Runs by status and API calls by outcome "
                      "(the code counts runs and calls, not items). Scheduler metrics: empty for env=perf.", [
    panel(1, "Runs by source and status", [target(
        f"sum by (source, status) (increase(soundcheck_ingestion_runs_total{{{ENV}}}[$__interval]))",
        "{{source}} {{status}}")], "short", 0, 0, stack=True),
    panel(2, "External API calls by source and outcome", [target(
        f"sum by (source, outcome) (increase(soundcheck_ingestion_source_calls_total{{{ENV}}}[$__interval]))",
        "{{source}} {{outcome}}")], "short", 12, 0, stack=True),
    panel(3, "Rollup duration p95", [target(p95("soundcheck_rollup_duration_milliseconds", "job"), "{{job}}")],
          "ms", 0, 8, w=24),
])

for name, board in {"api": api, "feed": feed, "media": media, "ingestion": ingestion}.items():
    (OUT / f"{name}.json").write_text(json.dumps(board, indent=2) + "\n")
    print(f"wrote ops/grafana/dashboards/{name}.json")

"""`soundcheck-lab` command line."""

from __future__ import annotations

from collections.abc import Callable
from pathlib import Path

import typer

from soundcheck_lab import __version__
from soundcheck_lab import parity as parity_cases
from soundcheck_lab.schema import load_schema

app = typer.Typer(
    name="soundcheck-lab",
    help="Soundcheck ranking lab: seed-scale, simulate, extract, train, evaluate, publish, parity.",
    no_args_is_help=True,
)


def _not_yet(command: str) -> None:
    typer.echo(f"{command}: not yet implemented", err=True)
    raise typer.Exit(code=1)


@app.command()
def version() -> None:
    """Print the lab version."""
    typer.echo(__version__)


@app.command()
def schema() -> None:
    """Print the feature schema (contracts/ranking/feature-schema.json)."""
    loaded = load_schema()
    typer.echo(f"feature schema v{loaded.version} ({len(loaded)} features)")
    for feature in loaded.features:
        typer.echo(f"  {feature.index:>2}  {feature.name:<20} {feature.description}")


@app.command("regroup-posts")
def regroup_posts(
    api_url: str = typer.Option("http://localhost:8088", envvar="SOUNDCHECK_API_URL"),
    apply: bool = typer.Option(False, "--apply", help="Make the changes (default: only list them)"),
    gig: str = typer.Option("", help="Only gigs whose key contains this (e.g. 2026-03-25)"),
    archive_followers: str = typer.Option(
        "",
        help="My real handles (comma-separated): accept their follow requests to the archive",
    ),
) -> None:
    """Regroup the personas' posts of my clips into the seed's current split, reusing the
    uploaded clips (no upload, no transcode; local stack only)."""
    from soundcheck_lab.seed import regroup as regrouping
    from soundcheck_lab.seed import run as seeding

    settings = seeding.Settings(root=seeding.default_root(), api_url=api_url)
    handles = [h.strip().lstrip("@") for h in archive_followers.split(",") if h.strip()]
    regrouping.regroup(settings, typer.echo, apply, handles, gig)


@app.command("seed-demo")
def seed_demo(
    api_url: str = typer.Option("http://localhost:8088", envvar="SOUNDCHECK_API_URL"),
    personas: int = typer.Option(30, min=1, max=30, help="How many of the 30 personas"),
    real: bool = typer.Option(
        True,
        "--real/--no-real",
        help="My real gigs and clips (fixtures/clips/mine/, local only)",
    ),
    gigs: int | None = typer.Option(None, help="Only the first N of my gigs (a small run)"),
    clips_per_gig: int = typer.Option(
        4, min=0, help="Real clips posted per gig, at most, shared between 1-3 persona posts"
    ),
    max_clip_seconds: float = typer.Option(45.0, min=5, max=60),
    month: str = typer.Option("", help="Batch: only my gigs of this month (YYYY-MM)"),
    max_gb: float = typer.Option(0, min=0, help="Batch: at most this much footage (0: no cap)"),
    archive: bool = typer.Option(
        True, "--archive/--no-archive", help="The private @alex_archive account"
    ),
    archive_followers: str = typer.Option(
        "",
        help="My real handles (comma-separated): accept their follow requests to the archive",
    ),
    synthetic: int = typer.Option(
        3, min=0, help="Synthetic clips for personas with fewer real ones"
    ),
    engage: bool = typer.Option(
        True, "--engage/--no-engage", help="Run the synthetic engagement pass"
    ),
    require_feed: int = typer.Option(
        0,
        min=0,
        help="Exit 1 unless every persona's For You has at least this many items "
        "(the acceptance check passes 30)",
    ),
) -> None:
    """Seed personas, my gigs and clips, likes and engagement through the API."""
    from soundcheck_lab.seed import engage as engagement
    from soundcheck_lab.seed import run as seeding
    from soundcheck_lab.seed.api import Api
    from soundcheck_lab.seed.personas import DEMO, PERSONAS

    settings = seeding.Settings(
        root=seeding.default_root(),
        api_url=api_url,
        personas=personas,
        clips_per_gig=clips_per_gig,
        max_clip_s=max_clip_seconds,
        synthetic_per_persona=synthetic,
        gig_limit=gigs,
        real=real,
        month=month or None,
        max_gb=max_gb or None,
        archive=archive,
        archive_followers=tuple(
            h.strip().lstrip("@") for h in archive_followers.split(",") if h.strip()
        ),
    )
    if real and not (settings.clips_dir / "bills.json").exists():
        typer.echo(f"no {settings.clips_dir / 'bills.json'}: seeding without real clips", err=True)
        settings.real = False

    def log(line: str) -> None:
        typer.echo(line)

    seeding.run(settings, log)
    people = [*PERSONAS[:personas], DEMO]
    api = Api(api_url)
    try:
        if engage:
            engagement.engage(api, people, log)
        report = engagement.report(api, people, log)
    finally:
        api.close()
    short = {h: n for h, n in report.feed_sizes.items() if n < require_feed}
    if short:
        typer.echo(
            f"FAILED: {len(short)} of {len(report.feed_sizes)} personas have fewer than "
            f"{require_feed} For You items ({', '.join(f'{h} {n}' for h, n in short.items())})",
            err=True,
        )
        raise typer.Exit(1)


@app.command("backfill-setlists")
def backfill_setlists(
    artists: str = typer.Option("", help="Only these acts (comma-separated); default: every act"),
    from_month: str = typer.Option(
        "", "--from", help="YYYY-MM: look back to this month; default: my first gig"
    ),
    dry_run: bool = typer.Option(False, "--dry-run", help="Print the changes; keep bills.json"),
    budget: int = typer.Option(1000, min=1, max=1400, help="setlist.fm calls a day (UTC)"),
) -> None:
    """My gigs with their full bills from setlist.fm; other dates for review."""
    import json
    import os
    from datetime import date

    from soundcheck_lab.seed import run as seeding
    from soundcheck_lab.seed import setlists

    key = os.environ.get("SETLISTFM_API_KEY", "")
    if not key:
        typer.echo("SETLISTFM_API_KEY is not set (.env)", err=True)
        raise typer.Exit(1)
    root = seeding.default_root()
    bills = root / "fixtures" / "clips" / "mine" / "bills.json"
    out = root / ".localdata" / "setlistfm"
    if from_month:
        since = date.fromisoformat(f"{from_month}-01")
    else:
        since = min(date.fromisoformat(b["date"]) for b in json.loads(bills.read_text())).replace(
            day=1
        )
    state = setlists.State.load(out / "state.json")
    client = setlists.Client(key, state, budget=budget)
    try:
        result = setlists.backfill(
            client,
            state,
            bills,
            out,
            since=since,
            only=[a.strip() for a in artists.split(",") if a.strip()],
            dry_run=dry_run,
            log=typer.echo,
        )
    finally:
        client.close()
    typer.echo(f"setlist.fm calls today: {client.calls_today()} of {budget}")
    if result is None:
        raise typer.Exit(2)


@app.command("seed-scale")
def seed_scale(profile: str = typer.Option("small", help="small | medium | large")) -> None:
    """Generate the performance dataset directly in Postgres."""
    _not_yet("seed-scale")


@app.command()
def simulate(
    api_url: str = typer.Option(
        ...,
        "--api",
        help="The simulator's own API (no default: never the dev stack, :8080/:8088)",
    ),
    db: str = typer.Option(
        ...,
        "--db",
        help="The database behind --api, read-only (postgresql://user:pass@host:port/db)",
    ),
    personas: int = typer.Option(200, min=1, max=2000),
    sessions: int = typer.Option(5, min=1, help="Sessions per persona, in total"),
    days: int = typer.Option(14, min=1, help="Rounds (a round stands for a day)"),
    seed: int = typer.Option(7, min=0, max=10**9 - 1),
    round_wait: float = typer.Option(
        12.0, min=0, help="Seconds between rounds: one rollup interval plus 2 s"
    ),
    workers: int = typer.Option(4, min=1, max=16, help="Sessions run at once within a round"),
    out: str = typer.Option("", help="Directory for sim-<seed>-<ts>.json (default lab/data)"),
) -> Path:
    """Run synthetic personas against the real API with a click model."""
    from datetime import UTC, datetime

    from soundcheck_lab.seed.api import Api
    from soundcheck_lab.seed.personas import check_password_for
    from soundcheck_lab.sim import check, world
    from soundcheck_lab.sim import personas as sim_personas
    from soundcheck_lab.sim import run as sim

    check_password_for(api_url)
    lab_root = Path(__file__).resolve().parents[2]
    the_world = world.load(db, seed)
    people = sim_personas.make(the_world, seed, personas, sessions, days, datetime.now(UTC))
    settings = sim.Settings(personas, sessions, days, seed, api_url, round_wait, workers)
    client = Api(api_url, auth_spacing_s=0.5)  # the local profile allows 120 auth calls a minute
    try:
        result = sim.run(settings, client, the_world, people, typer.echo)
    except sim.WorldMismatchError as mismatch:
        typer.echo(f"simulate: {mismatch}", err=True)
        raise typer.Exit(1) from mismatch
    finally:
        client.close()
    feed_sessions = {f for s in result.sessions for f in s.feed_sessions}
    result.check = check.run(
        db, result.started_at, feed_sessions, the_world, people, result.user_ids
    )
    typer.echo(f"served_outcome: {result.check}")
    path = sim.write(result, Path(out) if out else lab_root / "data", _commit())
    typer.echo(f"wrote {path} (SYNTHETIC: {sim.HONESTY})")
    return path


def _lab_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _commit() -> str | None:
    import subprocess

    sha = subprocess.run(
        ["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True, check=False
    ).stdout.strip()
    return sha or None


def _stamp() -> str:
    from datetime import UTC, datetime

    return datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")


def _newest(directory: Path, pattern: str) -> Path:
    found = sorted(directory.glob(pattern))
    if not found:
        typer.echo(f"no {pattern} in {directory}", err=True)
        raise typer.Exit(1)
    return found[-1]


DB_OPTION = typer.Option(
    ...,
    "--db",
    envvar="LAB_DATABASE_URL",
    help="The lab's own database (no default: never the dev stack), postgresql://user:pass@host:port/db",
)


@app.command()
def extract(
    db: str = DB_OPTION,
    since: str = typer.Option("14d", help="Window start: 14d, 36h or an ISO date"),
    sim: str = typer.Option("", help="A simulator run (lab/data/sim-*.json): keep its sessions"),
    out: str = typer.Option("", help="Directory (default lab/data)"),
) -> Path:
    """Build the training table from served items and outcomes (contracts/ranking/extract.sql)."""
    from datetime import UTC, datetime

    from soundcheck_lab import extract as ex

    now = datetime.now(UTC)
    try:
        start = ex.parse_since(since, now)
    except ex.ExtractError as e:
        typer.echo(str(e), err=True)
        raise typer.Exit(2) from e
    schema_ = load_schema()
    shaped = ex.shape(ex.fetch(db, start, now), schema_)
    if shaped.refused:
        typer.echo(
            f"extract: refused {shaped.refused} rows whose feature schema version isn't "
            f"v{schema_.version} (contracts/ranking/feature-schema.json)",
            err=True,
        )
    frame, outside, run = shaped.frame, 0, None
    if sim:
        run = ex.load_sim(Path(sim))
        frame, outside = ex.attach_rounds(frame, run)
        typer.echo(f"extract: kept the simulator run's sessions; left out {outside} other rows")
    meta = ex.sidecar(
        frame,
        since=start,
        until=now,
        refused=shaped.refused,
        synthetic_users=ex.fetch_synthetic_users(db),
        sim=run,
        outside_sim=outside,
        commit=_commit(),
    )
    path = ex.write(frame, meta, Path(out) if out else _lab_root() / "data", _stamp())
    typer.echo(
        f"wrote {path}: {meta['rows']} rows, {meta['sessions']} sessions, "
        f"{meta['positives']} positive, synthetic share {meta['syntheticShare']}"
    )
    return path


@app.command()
def train(
    model: str = typer.Option("logistic", help="logistic (the only servable model)"),
    data: str = typer.Option(
        "", help="An extract (default: the newest lab/data/extract-*.parquet)"
    ),
    out: str = typer.Option("", help="Reports directory (default lab/reports)"),
) -> Path:
    """Fit ranking weights on the earlier rounds; writes model.json."""
    import json

    import polars as pl

    from soundcheck_lab import train as tr

    if model != "logistic":
        typer.echo(f"train: unknown model {model!r} (only logistic is servable)", err=True)
        raise typer.Exit(2)
    data_path = Path(data) if data else _newest(_lab_root() / "data", "extract-*.parquet")
    frame = pl.read_parquet(data_path)
    sidecar = json.loads(data_path.with_suffix(".json").read_text(encoding="utf-8"))
    schema_ = load_schema()
    split = tr.time_split(frame)
    groups = split.train["session_id"].to_numpy()
    y = tr.labels(split.train)
    commit = _commit()
    extra = {"data": str(data_path), "commit": commit, "split": split.describe()}
    with_prior = tr.fit_logistic(tr.design(split.train, schema_, prior=True), y, groups, schema_)
    without = tr.fit_logistic(tr.design(split.train, schema_, prior=False), y, groups, schema_)
    gbt = tr.fit_gbt(tr.design(split.train, schema_, prior=False), y)
    gbt_scores = gbt.predict_proba(tr.design(split.test, schema_, prior=False))[:, 1]

    report = (Path(out) if out else _lab_root() / "reports") / _stamp()
    report.mkdir(parents=True, exist_ok=True)
    tr.write_json(
        report / "model.json", tr.export(with_prior, schema_, with_prior=True, extra=extra)
    )
    tr.write_json(
        report / "model-no-prior.json", tr.export(without, schema_, with_prior=False, extra=extra)
    )
    tr.write_json(
        report / "split.json",
        {"data": str(data_path), "commit": commit, "extract": sidecar, **split.describe()},
    )
    split.test.select("session_id", "position").with_columns(
        pl.Series("score", gbt_scores.astype(float))
    ).write_parquet(report / "gbt-test.parquet")
    typer.echo(
        f"train: {split.train.height} rows / {split.describe()['trainSessions']} sessions; "
        f"test = {split.by} >= {split.test_from} ({split.test.height} rows). "
        f"C = {with_prior.c} (with the prior), {without.c} (without)"
    )
    if with_prior.clipped or without.clipped:
        typer.echo(f"train: clipped to ±{tr.WEIGHT_LIMIT}: {with_prior.clipped + without.clipped}")
    typer.echo(f"wrote {report / 'model.json'}")
    return report


@app.command()
def evaluate(
    configs: str = typer.Option(
        ..., help="Comma-separated: active and/or lab/reports/<ts>/model.json (its test rows)"
    ),
    db: str = DB_OPTION,
) -> Path:
    """Offline metrics on the held-out rounds; writes report.md, metrics.json and charts."""
    import json

    import polars as pl

    from soundcheck_lab import evaluate as ev
    from soundcheck_lab import train as tr

    tokens = [t.strip() for t in configs.split(",") if t.strip()]
    paths = [Path(t) for t in tokens if t != "active"]
    if not paths:
        typer.echo(
            "evaluate: name a lab/reports/<ts>/model.json (its split is the test set)", err=True
        )
        raise typer.Exit(2)
    report = paths[0].parent
    split_info = json.loads((report / "split.json").read_text(encoding="utf-8"))
    frame = pl.read_parquet(split_info["data"])
    split = tr.time_split(frame)
    if split.test.height != split_info["testRows"]:
        typer.echo("evaluate: the extract's test rows differ from training's split", err=True)
        raise typer.Exit(1)
    test = split.test
    schema_ = load_schema()

    scored: list[ev.Scored] = []
    weights_by: dict[str, dict[str, float]] = {}
    active_name = None
    if "active" in tokens:
        version, active = ev.fetch_active(db)
        active_name = f"Active v{version}"
        scored.append(ev.Scored(active_name, "config", ev.score_config(test, active, schema_)))
        weights_by[active_name] = active
    candidates = list(paths)
    sibling = report / "model-no-prior.json"
    if sibling.exists() and sibling not in candidates:
        candidates.append(sibling)
    models: dict[str, dict[str, object]] = {}
    for path in candidates:
        model = json.loads(path.read_text(encoding="utf-8"))
        name = {"model.json": "Learned", "model-no-prior.json": "Learned (no prior)"}.get(
            path.name, path.stem
        )
        w = tr.scorer_weights(model)
        scored.append(ev.Scored(name, "config", ev.score_config(test, w, schema_)))
        weights_by[name] = w
        models[name] = model
    gbt_path = report / "gbt-test.parquet"
    if gbt_path.exists():
        gbt = test.select("session_id", "position").join(
            pl.read_parquet(gbt_path), on=["session_id", "position"], how="left"
        )
        scored.append(ev.Scored("GBT (upper bound)", "reference", gbt["score"].to_list()))
    served = [-float(p) for p in test["position"].to_list()]
    scored.append(ev.Scored("As served", "reference", served, probabilities=False))

    order = ["bias", *schema_.names]
    weights_by = {n: {k: w[k] for k in order if k in w} for n, w in weights_by.items()}
    results = [ev.evaluate_one(test, s) for s in scored]
    baseline = active_name or results[0].name
    comparison = ev.compare(results, "Learned", baseline) if "Learned" in models else None
    verdict_line = ev.verdict(comparison) if comparison else ""
    why = ev.explain(results, baseline)
    learned_prior = models.get("Learned", {}).get("training")
    if isinstance(learned_prior, dict) and learned_prior.get("learnedPositionPriorWeight"):
        why.append(
            f"The position prior's weight in training was "
            f"{learned_prior['learnedPositionPriorWeight']:.3f} (exported as 0)."
        )
    images = ev.charts(report, results, weights_by, ev.position_curve(frame))
    data = {"path": split_info["data"], **split_info["extract"]}
    md = ev.report_markdown(
        results,
        split=split_info,
        data=data,
        models=weights_by,
        verdict_line=verdict_line,
        why=why,
        commit=_commit(),
        images=images,
    )
    (report / "report.md").write_text(md, encoding="utf-8")
    by = {r.name: r for r in results}
    learned = by.get("Learned")
    out: dict[str, object] = {
        "verdict": verdict_line,
        "comparison": comparison,
        "why": why,
        "split": split_info,
        "results": [r.as_json() for r in results],
    }
    if learned is not None:
        out["publish"] = {
            "auc": learned.auc,
            "logLoss": learned.log_loss,
            "ndcg10": learned.ndcg10,
            "ndcg10ExplorationOnly": learned.exploration.get("ndcg10"),
            "rows": learned.rows,
            "sessions": learned.sessions,
            "data": "simulated",
            "activeNdcg10": by[baseline].ndcg10,
            "report": str(report),
        }
    ev.write_json(report / "metrics.json", out)
    for r in results:
        typer.echo(
            f"  {r.name:<20} AUC {ev.fmt(r.auc)}  log-loss {ev.fmt(r.log_loss)}  "
            f"NDCG@10 {ev.fmt(r.ndcg10)}  exploration-only NDCG@10 "
            f"{ev.fmt(r.exploration.get('ndcg10'))}"
        )
    typer.echo(verdict_line)
    typer.echo(f"wrote {report / 'report.md'} (SIMULATED data)")
    return report


@app.command()
def publish(
    report: str = typer.Argument(..., help="lab/reports/<ts> (after evaluate)"),
    api_url: str = typer.Option(
        ..., "--api", envvar="LAB_API", help="The lab's own API (no default: never the dev stack)"
    ),
) -> str:
    """Publish a trained config as a LAB DRAFT via the API (params copied from the active one)."""
    import json

    from soundcheck_lab import publish as pub

    directory = Path(report)
    model = json.loads((directory / "model.json").read_text(encoding="utf-8"))
    evaluated = json.loads((directory / "metrics.json").read_text(encoding="utf-8"))
    if "publish" not in evaluated:
        typer.echo("publish: run evaluate on this report first", err=True)
        raise typer.Exit(1)
    metrics = dict(evaluated["publish"])
    metrics["report"] = pub.report_path_for_notes(directory, _lab_root().parent)
    try:
        created = pub.publish(api_url, model, metrics, metrics["report"])
    except pub.PublishError as e:
        typer.echo(f"publish: {e}", err=True)
        raise typer.Exit(1) from e
    typer.echo(f"published LAB draft v{created.version} ({created.status}): {created.id}")
    return created.id


@app.command()
def loop(
    api_url: str = typer.Option(
        ..., "--api", envvar="LAB_API", help="The lab's own API (no default: never the dev stack)"
    ),
    db: str = DB_OPTION,
    personas: int = typer.Option(40, min=1),
    sessions: int = typer.Option(3, min=1),
    days: int = typer.Option(6, min=2),
    seed: int = typer.Option(11, min=0, max=10**9 - 1),
) -> None:
    """simulate → extract → train → evaluate → publish, on the lab's own stack."""
    import time

    timings: dict[str, float] = {}

    def step[T](name: str, run: Callable[[], T]) -> T:
        typer.echo(f"── {name}")
        began = time.monotonic()
        value = run()
        timings[name] = round(time.monotonic() - began, 1)
        return value

    sim_path = step(
        "simulate",
        lambda: simulate(
            api_url=api_url,
            db=db,
            personas=personas,
            sessions=sessions,
            days=days,
            seed=seed,
            round_wait=12.0,
            workers=4,
            out="",
        ),
    )
    data = step("extract", lambda: extract(db=db, since="1d", sim=str(sim_path), out=""))
    report = step("train", lambda: train(model="logistic", data=str(data), out=""))
    step("evaluate", lambda: evaluate(configs=f"active,{report / 'model.json'}", db=db))
    draft = step("publish", lambda: publish(report=str(report), api_url=api_url))
    total = sum(timings.values())
    typer.echo(
        "loop: "
        + ", ".join(f"{k} {v} s" for k, v in timings.items())
        + f"; total {round(total, 1)} s. LAB draft {draft} (SIMULATED data)"
    )


@app.command()
def parity(
    write: bool = typer.Option(False, "--write", help="Regenerate the cases file instead."),
) -> None:
    """Run contracts/ranking/parity-cases.json through the Python scorer."""
    if write:
        parity_cases.DEFAULT_CASES_PATH.write_text(
            parity_cases.render(load_schema()), encoding="utf-8"
        )
        typer.echo(f"wrote {parity_cases.DEFAULT_CASES_PATH}")
        return
    mismatches = parity_cases.check()
    _, cases = parity_cases.load_cases()
    for mismatch in mismatches:
        typer.echo(
            f"MISMATCH {mismatch.name}: expected {mismatch.expected!r}, got {mismatch.actual!r}",
            err=True,
        )
    if mismatches:
        raise typer.Exit(code=1)
    typer.echo(f"parity: {len(cases)} cases agree within {parity_cases.TOLERANCE}")


if __name__ == "__main__":
    app()

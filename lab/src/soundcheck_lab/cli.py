"""`soundcheck-lab` command line. Most commands are stubs for now."""

from __future__ import annotations

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
) -> None:
    """Run synthetic personas against the real API with a click model."""
    import subprocess
    from datetime import UTC, datetime
    from pathlib import Path

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
    sha = subprocess.run(
        ["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True, check=False
    ).stdout.strip()
    path = sim.write(result, Path(out) if out else lab_root / "data", sha or None)
    typer.echo(f"wrote {path} (SYNTHETIC: {sim.HONESTY})")


@app.command()
def extract(since: str = typer.Option("14d")) -> None:
    """Build the training table from served items and outcomes."""
    _not_yet("extract")


@app.command()
def train(model: str = typer.Option("logistic")) -> None:
    """Fit ranking weights."""
    _not_yet("train")


@app.command()
def evaluate(configs: str = typer.Option("active")) -> None:
    """Offline metrics on a time-based holdout."""
    _not_yet("evaluate")


@app.command()
def publish(report: str = typer.Argument(...)) -> None:
    """Publish a trained config as a DRAFT via the API."""
    _not_yet("publish")


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

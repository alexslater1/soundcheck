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
    clips_per_gig: int = typer.Option(4, min=0),
    max_clip_seconds: float = typer.Option(45.0, min=5, max=60),
    synthetic: int = typer.Option(
        3, min=0, help="Synthetic clips for personas with fewer real ones"
    ),
    engage: bool = typer.Option(
        True, "--engage/--no-engage", help="Run the synthetic engagement pass"
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
        engagement.report(api, people, log)
    finally:
        api.close()


@app.command("seed-scale")
def seed_scale(profile: str = typer.Option("small", help="small | medium | large")) -> None:
    """Generate the performance dataset directly in Postgres."""
    _not_yet("seed-scale")


@app.command()
def simulate(
    personas: int = typer.Option(200),
    sessions: int = typer.Option(5),
    days: int = typer.Option(14),
) -> None:
    """Run synthetic personas against the real API with a click model."""
    _not_yet("simulate")


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

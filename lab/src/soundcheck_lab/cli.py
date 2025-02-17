"""`soundcheck-lab` command line. Commands are stubs until their phase."""

from __future__ import annotations

import typer

from soundcheck_lab import __version__
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
def parity() -> None:
    """Run contracts/ranking/parity-cases.json through the Python scorer."""
    _not_yet("parity")


if __name__ == "__main__":
    app()

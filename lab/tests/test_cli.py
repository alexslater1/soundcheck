from typer.testing import CliRunner

from soundcheck_lab.cli import app

runner = CliRunner()


def test_help_lists_commands() -> None:
    result = runner.invoke(app, ["--help"])
    assert result.exit_code == 0
    for command in ("seed-scale", "simulate", "extract", "train", "evaluate", "publish", "parity"):
        assert command in result.output


def test_schema_command_prints_all_features() -> None:
    result = runner.invoke(app, ["schema"])
    assert result.exit_code == 0
    assert "feature schema v1 (12 features)" in result.output
    assert "position_prior" in result.output


def test_unimplemented_commands_exit_1() -> None:
    result = runner.invoke(app, ["train"])
    assert result.exit_code == 1
    assert "not yet implemented" in result.output


def test_simulate_has_no_default_api_or_database() -> None:
    """Never the dev stack by accident: --api and --db must be given."""
    result = runner.invoke(app, ["simulate"])
    assert result.exit_code == 2
    assert "--api" in result.output
    result = runner.invoke(app, ["simulate", "--api", "http://localhost:8140"])
    assert result.exit_code == 2
    assert "--db" in result.output

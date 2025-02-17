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

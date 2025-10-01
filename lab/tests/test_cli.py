import json
import re
from pathlib import Path

from typer.testing import CliRunner

from soundcheck_lab.cli import app

runner = CliRunner()


def plain(text: str) -> str:
    """Without ANSI styling: CI forces colour, and rich's escape codes split option names."""
    return re.sub(r"\x1b\[[0-9;]*m", "", text)


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


def test_seed_scale_refuses_anything_but_the_local_perf_stack() -> None:
    remote = runner.invoke(
        app, ["seed-scale", "--db", "postgresql://u:p@db.example.com/soundcheck_perf"]
    )
    assert remote.exit_code == 2
    assert "local perf stack" in remote.output
    alexs = runner.invoke(app, ["seed-scale", "--db", "postgresql://u:p@localhost:5432/soundcheck"])
    assert alexs.exit_code == 2
    assert "soundcheck_perf" in alexs.output
    large = runner.invoke(app, ["seed-scale", "--profile", "large"])
    assert large.exit_code == 2
    assert "cut" in large.output


NO_LAB_ENV = {"LAB_DATABASE_URL": None, "LAB_API": None}


def test_the_loop_commands_have_no_default_database_or_api() -> None:
    """Never the dev stack by accident: --db and --api must be given (or LAB_* set)."""
    for args, option in (
        (["extract"], "--db"),
        (["evaluate", "--configs", "active"], "--db"),
        (["publish", "lab/reports/x"], "--api"),
        (["loop", "--db", "postgresql://x"], "--api"),
        (["loop", "--api", "http://localhost:8141"], "--db"),
    ):
        # typer's invoke types env as str-only; click's runner, which receives it, unsets a
        # variable given None.
        result = runner.invoke(app, args, env=NO_LAB_ENV)  # type: ignore[arg-type]
        assert result.exit_code == 2, args
        assert option in plain(result.output), args


def test_train_refuses_a_model_it_cannot_serve() -> None:
    result = runner.invoke(app, ["train", "--model", "gbt"])
    assert result.exit_code == 2
    assert "only logistic" in plain(result.output)


def test_train_then_evaluate_offline(tmp_path: Path) -> None:
    """Both commands end to end on a synthetic extract (no database: "active" is not asked for)."""
    from soundcheck_lab import extract as ex
    from test_train import frame

    data = tmp_path / "extract-x.parquet"
    f = frame(rounds=5, sessions_per_round=6, items=8)
    f.write_parquet(data)
    data.with_suffix(".json").write_text(
        json.dumps({"rows": f.height, "sessions": 30, "positives": 1, "syntheticShare": 1.0}),
        encoding="utf-8",
    )
    trained = runner.invoke(app, ["train", "--data", str(data), "--out", str(tmp_path)])
    assert trained.exit_code == 0, trained.output
    report = next(p for p in tmp_path.iterdir() if p.is_dir())
    model = json.loads((report / "model.json").read_text(encoding="utf-8"))
    assert model["weights"]["position_prior"] == 0.0
    evaluated = runner.invoke(
        app,
        ["evaluate", "--configs", str(report / "model.json"), "--db", "postgresql://unused"],
    )
    assert evaluated.exit_code == 0, evaluated.output
    text = (report / "report.md").read_text(encoding="utf-8")
    assert "**simulated**" in text
    metrics = json.loads((report / "metrics.json").read_text(encoding="utf-8"))
    assert metrics["publish"]["data"] == "simulated"
    assert {"auc", "logLoss", "ndcg10", "ndcg10ExplorationOnly", "rows", "sessions"} <= set(
        metrics["publish"]
    )
    for png in ("calibration.png", "ndcg.png", "weights.png", "position.png"):
        assert (report / png).exists()
    assert ex.RAW_SCHEMA  # the extract module imports without a database


def test_simulate_has_no_default_api_or_database() -> None:
    """Never the dev stack by accident: --api and --db must be given."""
    result = runner.invoke(app, ["simulate"])
    assert result.exit_code == 2
    assert "--api" in plain(result.output)
    result = runner.invoke(app, ["simulate", "--api", "http://localhost:8140"])
    assert result.exit_code == 2
    assert "--db" in plain(result.output)

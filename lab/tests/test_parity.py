import json
from pathlib import Path

import pytest
from typer.testing import CliRunner

from soundcheck_lab import parity
from soundcheck_lab.cli import app
from soundcheck_lab.schema import load_schema

SCHEMA = load_schema()


def test_the_committed_cases_agree_with_the_python_scorer() -> None:
    version, cases = parity.load_cases()
    assert version == SCHEMA.version
    assert len(cases) >= 20
    assert parity.check() == []


def test_the_committed_file_is_exactly_what_the_generator_produces() -> None:
    # Regenerating must not change a byte unless the scorer or the schema changed.
    assert parity.DEFAULT_CASES_PATH.read_text(encoding="utf-8") == parity.render(SCHEMA)


def test_the_cases_cover_the_corners() -> None:
    _, cases = parity.load_cases()
    by_name = {case.name: case for case in cases}
    assert by_name["v1 weights, nothing known about the post"].expected_score == pytest.approx(
        0.11920292202211755
    )
    assert by_name["largest allowed weights, all features on"].expected_score == 1.0
    assert by_name["smallest allowed weights, all features on"].expected_score < 1e-50
    assert all(len(case.features) == len(SCHEMA) for case in cases)
    assert all(0.0 <= f <= 1.0 for case in cases for f in case.features)
    assert all(abs(w) <= 10.0 for case in cases for w in case.weights.values())


def test_a_wrong_score_is_reported(tmp_path: Path) -> None:
    document = json.loads(parity.DEFAULT_CASES_PATH.read_text(encoding="utf-8"))
    document["cases"][0]["expectedScore"] += 1e-5
    tampered = tmp_path / "cases.json"
    tampered.write_text(json.dumps(document), encoding="utf-8")

    mismatches = parity.check(tampered)

    assert [m.name for m in mismatches] == [document["cases"][0]["name"]]


def test_cases_for_another_schema_version_are_refused(tmp_path: Path) -> None:
    document = json.loads(parity.DEFAULT_CASES_PATH.read_text(encoding="utf-8"))
    document["schemaVersion"] = 2
    stale = tmp_path / "cases.json"
    stale.write_text(json.dumps(document), encoding="utf-8")
    with pytest.raises(ValueError, match="regenerate"):
        parity.check(stale)


def test_the_parity_command_passes_on_the_committed_file() -> None:
    result = CliRunner().invoke(app, ["parity"])
    assert result.exit_code == 0
    assert "cases agree within 1e-06" in result.output

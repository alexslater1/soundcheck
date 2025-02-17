import json
from pathlib import Path

import pytest

from soundcheck_lab.schema import DEFAULT_SCHEMA_PATH, load_schema

EXPECTED_NAMES = (
    "follows_author",
    "follows_artist",
    "artist_affinity",
    "author_affinity",
    "genre_similarity",
    "gig_relation",
    "proximity",
    "recency",
    "engagement_quality",
    "social_proof",
    "seen",
    "position_prior",
)


def test_loads_the_committed_schema_v1() -> None:
    schema = load_schema()
    assert DEFAULT_SCHEMA_PATH.exists()
    assert schema.version == 1
    assert len(schema) == 12
    assert schema.names == EXPECTED_NAMES
    assert schema.index_of("position_prior") == 11


def test_rejects_non_contiguous_indexes(tmp_path: Path) -> None:
    bad = tmp_path / "schema.json"
    bad.write_text(
        json.dumps(
            {
                "version": 9,
                "features": [
                    {"index": 0, "name": "a", "description": ""},
                    {"index": 2, "name": "b", "description": ""},
                ],
            }
        )
    )
    with pytest.raises(ValueError, match="indexes"):
        load_schema(bad)


def test_rejects_duplicate_names(tmp_path: Path) -> None:
    bad = tmp_path / "schema.json"
    bad.write_text(
        json.dumps(
            {
                "version": 9,
                "features": [
                    {"index": 0, "name": "a", "description": ""},
                    {"index": 1, "name": "a", "description": ""},
                ],
            }
        )
    )
    with pytest.raises(ValueError, match="duplicate"):
        load_schema(bad)

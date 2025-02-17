"""Feature schema, shared with the Java feed module.

Source of truth: contracts/ranking/feature-schema.json.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

# contracts/ranking/feature-schema.json relative to this file (lab/src/soundcheck_lab/schema.py).
DEFAULT_SCHEMA_PATH = (
    Path(__file__).resolve().parents[3] / "contracts" / "ranking" / "feature-schema.json"
)


@dataclass(frozen=True, slots=True)
class Feature:
    index: int
    name: str
    description: str


@dataclass(frozen=True, slots=True)
class FeatureSchema:
    version: int
    features: tuple[Feature, ...]

    @property
    def names(self) -> tuple[str, ...]:
        return tuple(f.name for f in self.features)

    def index_of(self, name: str) -> int:
        for feature in self.features:
            if feature.name == name:
                return feature.index
        raise KeyError(name)

    def __len__(self) -> int:
        return len(self.features)


def load_schema(path: Path = DEFAULT_SCHEMA_PATH) -> FeatureSchema:
    """Loads and validates the schema: contiguous indexes from 0, unique names."""
    raw = json.loads(path.read_text(encoding="utf-8"))
    features = tuple(
        Feature(index=int(f["index"]), name=str(f["name"]), description=str(f["description"]))
        for f in raw["features"]
    )
    indexes = [f.index for f in features]
    if indexes != list(range(len(features))):
        raise ValueError(f"feature indexes must be 0..{len(features) - 1} in order, got {indexes}")
    names = [f.name for f in features]
    if len(set(names)) != len(names):
        raise ValueError(f"duplicate feature names in {path}")
    return FeatureSchema(version=int(raw["version"]), features=features)

import math

import pytest

from soundcheck_lab.parity import V1_WEIGHTS
from soundcheck_lab.schema import load_schema
from soundcheck_lab.scorer import contributions, logit, score, sigmoid

SCHEMA = load_schema()


def test_sigmoid_is_the_logistic_function_and_survives_large_inputs() -> None:
    assert sigmoid(0.0) == 0.5
    assert sigmoid(2.0) == pytest.approx(1 / (1 + math.exp(-2.0)), abs=1e-15)
    assert sigmoid(-2.0) == pytest.approx(1 - sigmoid(2.0), abs=1e-15)
    assert sigmoid(1000.0) == 1.0
    assert sigmoid(-1000.0) == 0.0


def test_a_post_nothing_is_known_about_scores_sigmoid_of_the_bias() -> None:
    features = [0.0] * len(SCHEMA)
    assert logit(V1_WEIGHTS, features, SCHEMA) == -2.0
    assert score(V1_WEIGHTS, features, SCHEMA) == pytest.approx(0.11920292202211755, abs=1e-15)


def test_contributions_are_weight_times_feature_in_schema_order() -> None:
    features = [0.0] * len(SCHEMA)
    features[SCHEMA.index_of("follows_author")] = 1.0
    features[SCHEMA.index_of("recency")] = 0.5
    features[SCHEMA.index_of("seen")] = 1.0
    parts = contributions(V1_WEIGHTS, features, SCHEMA)
    assert parts[SCHEMA.index_of("follows_author")] == 1.6
    assert parts[SCHEMA.index_of("recency")] == 0.75
    assert parts[SCHEMA.index_of("seen")] == -2.5
    assert logit(V1_WEIGHTS, features, SCHEMA) == pytest.approx(-2.0 + 1.6 + 0.75 - 2.5)


def test_weights_and_features_must_match_the_schema() -> None:
    with pytest.raises(ValueError, match="expected 12 features"):
        score(V1_WEIGHTS, [0.0] * 11, SCHEMA)
    missing = {k: v for k, v in V1_WEIGHTS.items() if k != "seen"}
    with pytest.raises(ValueError, match="weights must name exactly"):
        score(missing, [0.0] * 12, SCHEMA)

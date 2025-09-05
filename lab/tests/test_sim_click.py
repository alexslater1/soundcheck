"""The click model and the personas (pure; no API, no database)."""

import numpy as np
import pytest

from simworld import NOW, WORLD, synthetic_world
from soundcheck_lab import events as ev
from soundcheck_lab.sim import click, personas
from soundcheck_lab.sim.personas import rng_for


def test_the_same_seed_gives_the_same_personas() -> None:
    first = personas.make(WORLD, 7, 20, 5, 14, NOW)
    again = personas.make(synthetic_world(), 7, 20, 5, 14, NOW)
    assert first == again
    assert personas.make(WORLD, 8, 20, 5, 14, NOW) != first
    # Persona n doesn't depend on how many there are.
    assert personas.make(WORLD, 7, 3, 5, 14, NOW) == first[:3]


def test_personas_have_the_specified_shape() -> None:
    for p in personas.make(WORLD, 7, 50, 5, 14, NOW):
        assert p.handle == f"sim_7_{p.n:03d}"
        assert p.account.email == f"sim_7_{p.n:03d}@sim.soundcheck.test"
        assert 3 <= len(p.taste.genres) <= 4
        assert sum(p.taste.genres.values()) == pytest.approx(1.0)
        assert 5 <= len(p.taste.favourite_artists) <= 15
        assert set(p.follow_artists) <= p.taste.favourite_artists and p.follow_artists
        assert p.taste.followed_authors <= set(WORLD.authors)
        assert 0 <= len(p.attend) <= 3
        assert all(a.status == "WENT" for a in p.attend)  # every synthetic gig is in the past
        assert 8 <= p.session_length <= 40
        assert 0.0 <= p.social <= 1.0
        assert p.city in {"London", "Leeds"}
        assert len(p.rounds) == 5 and len(set(p.rounds)) == 5 and all(0 <= r < 14 for r in p.rounds)


def test_more_sessions_than_rounds_share_rounds() -> None:
    rounds = personas.rounds_for(rng_for(7, 0), sessions=5, days=2)
    assert len(rounds) == 5 and set(rounds) <= {0, 1}


def test_a_seed_too_long_for_a_handle_is_refused() -> None:
    with pytest.raises(ValueError):
        personas.make(WORLD, 10**9, 1, 1, 1, NOW)


def test_quality_is_seeded_by_seed_and_post() -> None:
    post = next(iter(WORLD.posts))
    assert WORLD.quality(post) == synthetic_world().quality(post)
    assert WORLD.quality(post) != synthetic_world(seed=8).quality(post)
    qualities = [WORLD.quality(p) for p in WORLD.posts]
    assert abs(float(np.mean(qualities))) < 0.2 and 0.8 < float(np.std(qualities)) < 1.2


def test_p_engage_falls_with_position() -> None:
    curve = [click.p_engage(0.5, 0.0, k) for k in range(60)]
    assert all(a > b for a, b in zip(curve, curve[1:], strict=False))
    assert click.p_engage(0.0, 0.0, 0) == pytest.approx(click.sigmoid(click.DEFAULT.bias))


def test_p_engage_rises_with_affinity_and_quality() -> None:
    assert click.p_engage(0.9, 0.0, 3) > click.p_engage(0.1, 0.0, 3)
    assert click.p_engage(0.5, 1.0, 3) > click.p_engage(0.5, -1.0, 3)


def test_true_affinity_mixes_genre_artist_and_author() -> None:
    item = click.Item({"Indie": 1.0}, ("a1",), "u1", 0.0, True)
    nothing = click.Taste({"Jazz": 1.0}, frozenset(), frozenset())
    everything = click.Taste({"Indie": 1.0}, frozenset({"a1"}), frozenset({"u1"}))
    assert click.true_affinity(nothing, item) == 0.0
    assert click.true_affinity(everything, item) == pytest.approx(1.0)
    genre_only = click.Taste({"Indie": 0.5, "Jazz": 0.5}, frozenset(), frozenset())
    assert click.true_affinity(genre_only, item) == pytest.approx(0.6 * 0.5**0.5)


def test_a_high_affinity_item_engages_more_often_over_10000_draws() -> None:
    item = click.Item({"Indie": 1.0}, ("a1",), "u1", 0.0, True)
    fan = click.Taste({"Indie": 1.0}, frozenset({"a1"}), frozenset())
    stranger = click.Taste({"Techno": 1.0}, frozenset(), frozenset())
    rng = rng_for(7, 1)
    fan_rate = np.mean(
        [
            click.react(rng, fan, item, 3, social=0.5, liked_before=False).engaged
            for _ in range(10_000)
        ]
    )
    stranger_rate = np.mean(
        [
            click.react(rng, stranger, item, 3, social=0.5, liked_before=False).engaged
            for _ in range(10_000)
        ]
    )
    assert fan_rate > stranger_rate + 0.4


def test_engagement_correlates_with_latent_taste_on_a_synthetic_world() -> None:
    """The target statistic: Spearman ρ > 0.3 between trueAffinity and engagement, over 10 000
    random (persona, post, position) draws."""
    people = personas.make(WORLD, 7, 100, 5, 14, NOW)
    posts = list(WORLD.posts)
    rng = rng_for(7, 99)
    affinity, engaged = [], []
    for _ in range(10_000):
        persona = people[int(rng.integers(len(people)))]
        item = WORLD.item(posts[int(rng.integers(len(posts)))])
        reaction = click.react(
            rng,
            persona.taste,
            item,
            int(rng.integers(40)),
            social=persona.social,
            liked_before=False,
        )
        affinity.append(click.true_affinity(persona.taste, item))
        engaged.append(float(reaction.engaged))
    rho = click.spearman(affinity, engaged)
    assert rho > 0.3, rho


def test_watch_fractions_stay_in_0_1_and_follow_engagement() -> None:
    item = click.Item({"Indie": 1.0}, ("a1",), "u1", 0.0, False)
    taste = click.Taste({"Indie": 1.0}, frozenset(), frozenset())
    rng = rng_for(7, 2)
    reactions = [
        click.react(rng, taste, item, k % 40, social=0.3, liked_before=False) for k in range(5000)
    ]
    assert all(0.0 <= r.watch <= 1.0 for r in reactions)
    engaged = [r.watch for r in reactions if r.engaged]
    skipped = [r.watch for r in reactions if not r.engaged]
    assert np.mean(engaged) > 0.7 > 0.3 > np.mean(skipped)
    assert not any(r.gig_tap for r in reactions)  # no gig, nothing to tap
    assert not any(r.hide for r in reactions)  # affinity 0.6 is above hide_below


def test_likes_need_engagement_and_never_repeat() -> None:
    item = click.Item({"Indie": 1.0}, ("a1",), "u1", 0.0, True)
    taste = click.Taste({"Indie": 1.0}, frozenset({"a1"}), frozenset())
    rng = rng_for(7, 3)
    fresh = [click.react(rng, taste, item, 0, social=0.5, liked_before=False) for _ in range(2000)]
    assert any(r.like for r in fresh)
    assert all(r.engaged for r in fresh if r.like)
    liked = [click.react(rng, taste, item, 0, social=0.5, liked_before=True) for _ in range(2000)]
    assert not any(r.like for r in liked)


def test_hides_come_rarely_and_only_for_low_affinity() -> None:
    item = click.Item({"Jazz": 1.0}, ("a9",), "u9", 0.0, True)
    taste = click.Taste({"Indie": 1.0}, frozenset(), frozenset())
    rng = rng_for(7, 4)
    reactions = [
        click.react(rng, taste, item, 5, social=0.5, liked_before=False) for _ in range(5000)
    ]
    hides = sum(r.hide for r in reactions)
    assert 0 < hides < 0.05 * len(reactions)
    assert all(not r.engaged for r in reactions if r.hide)


def test_the_same_draws_give_the_same_reactions() -> None:
    item = WORLD.item(next(iter(WORLD.posts)))
    taste = personas.make(WORLD, 7, 1, 5, 14, NOW)[0].taste
    a = [
        click.react(rng_for(7, 5), taste, item, k, social=0.2, liked_before=False)
        for k in range(20)
    ]
    b = [
        click.react(rng_for(7, 5), taste, item, k, social=0.2, liked_before=False)
        for k in range(20)
    ]
    assert a == b


def test_event_kinds_follow_the_watch_fraction() -> None:
    def kinds(watch: float, **flags: bool) -> list[tuple[str, float | None]]:
        base = {"like": False, "profile_tap": False, "gig_tap": False, "hide": False}
        return click.event_kinds(click.Reaction(0.5, True, watch, **(base | flags)))

    assert kinds(0.1) == [("IMPRESSION", None), ("VIEW_START", None)]
    assert kinds(0.6) == [
        ("IMPRESSION", None),
        ("VIEW_START", None),
        ("WATCH_PROGRESS", 0.25),
        ("WATCH_PROGRESS", 0.5),
    ]
    assert kinds(0.97, like=True, gig_tap=True)[-4:] == [
        ("WATCH_PROGRESS", 0.75),
        ("COMPLETE", None),
        ("LIKE", None),
        ("GIG_TAP", None),
    ]
    assert kinds(0.0, hide=True)[-1] == ("HIDE", None)
    assert ev.watch_kinds(0.95)[-1] == ("COMPLETE", None)


def test_spearman_and_ranks() -> None:
    assert list(click.ranks([10, 20, 20, 30])) == [1.0, 2.5, 2.5, 4.0]
    assert click.spearman([1, 2, 3, 4], [10, 20, 30, 40]) == pytest.approx(1.0)
    assert click.spearman([1, 2, 3, 4], [4, 3, 2, 1]) == pytest.approx(-1.0)
    assert click.spearman([1, 1, 1], [1, 2, 3]) == 0.0
    with pytest.raises(ValueError):
        click.spearman([1, 2], [1])


def test_cosine() -> None:
    assert click.cosine({"a": 1.0}, {"a": 2.0}) == pytest.approx(1.0)
    assert click.cosine({"a": 1.0}, {"b": 1.0}) == 0.0
    assert click.cosine({}, {"a": 1.0}) == 0.0

"""Unit tests for the DjRecommendationEngine (jam feature ported from Android)."""

from musicvault.experience import DjRecommendationEngine, DjUiState, JAM_MIX_ID
from musicvault.models import Track


def _track(index: int) -> Track:
    return Track(f"song:{index}", f"Song {index}", f"Artist {index % 4}", "Album", 180_000)


def test_jam_mix_id_constant():
    assert JAM_MIX_ID == "recommendation:dj"


def test_dj_ui_state_defaults():
    state = DjUiState()
    assert state.active is False
    assert state.loading is False
    assert state.reason == "dj_reason_learning"
    assert state.upcoming == []
    assert state.message is None


def test_offline_mix_contains_forty_songs_and_interleaves_artists():
    engine = DjRecommendationEngine()
    known = [_track(i) for i in range(50)]
    mix, reason = engine.recommendations(
        [], known, [], set(), None, False, {t.id for t in known},
    )
    assert len(mix) == 40
    assert len({t.id for t in mix}) == 40
    assert [t.artist for t in mix[:4]] == ["Artist 0", "Artist 1", "Artist 2", "Artist 3"]
    assert reason == "dj_reason_offline"


def test_offline_mix_excludes_unavailable_current_and_previously_played():
    engine = DjRecommendationEngine()
    known = [_track(i) for i in range(8)]
    mix, reason = engine.recommendations(
        [], known, [], {"song:1"}, known[0], False,
        {"song:0", "song:1", "song:2", "song:3"},
    )
    assert {t.id for t in mix} == {"song:2", "song:3"}


def test_mix_respects_preferred_artists():
    engine = DjRecommendationEngine()
    known = [
        Track("t1", "Song 1", "Preferred Artist", "Album", 180_000),
        Track("t2", "Song 2", "Other Artist", "Album", 180_000),
        Track("t3", "Song 3", "Preferred Artist", "Album", 180_000),
        Track("t4", "Song 4", "Other Artist", "Album", 180_000),
    ]
    mix, reason = engine.recommendations(
        [], known, ["Preferred Artist"], set(), None, True, {t.id for t in known},
        discover_fn=lambda seed, limit: [],
    )
    assert reason == "Preferred Artist"
    preferred_tracks = [t for t in mix if t.artist == "Preferred Artist"]
    other_tracks = [t for t in mix if t.artist != "Preferred Artist"]
    assert len(preferred_tracks) > 0
    assert len(other_tracks) > 0


def test_mix_uses_play_history_with_decay():
    engine = DjRecommendationEngine()
    now = 1_700_000_000_000
    known = [_track(i) for i in range(5)]
    history = [
        {"track_id": "song:0", "played_at": now - 1_000_000, "completion_ratio": 1.0},
        {"track_id": "song:1", "played_at": now - 1_000_000, "completion_ratio": 0.1},
    ]
    mix, reason = engine.recommendations(
        history, known, [], set(), None, True, {t.id for t in known},
        discover_fn=lambda seed, limit: [],
    )
    assert len(mix) > 0


def test_mix_reason_with_learned_artists():
    engine = DjRecommendationEngine()
    now = 1_700_000_000_000
    known = [_track(i) for i in range(5)]
    history = [
        {"track_id": "song:0", "played_at": now - 1_000_000, "completion_ratio": 1.0},
    ]
    mix, reason = engine.recommendations(
        history, known, [], set(), None, True, {t.id for t in known},
        discover_fn=lambda seed, limit: [],
    )
    assert reason == "Artist 0"


def test_empty_known_returns_empty_mix():
    engine = DjRecommendationEngine()
    mix, reason = engine.recommendations(
        [], [], [], set(), None, False, set(),
    )
    assert mix == []
    assert reason == "dj_reason_offline"


def test_mix_caps_at_forty():
    engine = DjRecommendationEngine()
    known = [_track(i) for i in range(100)]
    mix, _ = engine.recommendations(
        [], known, [], set(), None, False, {t.id for t in known},
    )
    assert len(mix) <= 40

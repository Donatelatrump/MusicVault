import pytest

from musicvault.spotify import playlist_url, spotify_url_from_text, track_url


def test_playlist_url_accepts_only_spotify_playlists():
    assert playlist_url("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x") == (
        "37i9dQZF1DXcBWIGoYBM5M", "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
    with pytest.raises(ValueError):
        playlist_url("https://example.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
    with pytest.raises(ValueError):
        playlist_url("https://open.spotify.com/track/37i9dQZF1DXcBWIGoYBM5M")


def test_track_url_and_clipboard_text_accept_supported_spotify_links():
    assert track_url("https://open.spotify.com/intl-es/track/4uLU6hMCjMI75M1A2tKUQC?si=x") == (
        "4uLU6hMCjMI75M1A2tKUQC", "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC")
    assert spotify_url_from_text(
        "Escucha esto: https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=x)."
    ) == ("track", "4uLU6hMCjMI75M1A2tKUQC", "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC")
    assert spotify_url_from_text("https://open.spotify.com/album/4uLU6hMCjMI75M1A2tKUQC") is None

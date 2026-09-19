from dataclasses import replace
import threading

from PyQt6.QtCore import QTimer, QSize
from PyQt6.QtGui import QColor, QPixmap
from PyQt6.QtTest import QTest
from PyQt6.QtWidgets import QApplication, QDialog

from musicvault.library import read_track
from musicvault.player import Player
from musicvault.window import Window
from test_player import until


def test_native_window_navigation_player_collections_and_settings(app, db, audio_file, monkeypatch, tmp_path):
    monkeypatch.setattr("musicvault.window.fetch_lyrics", lambda track, db: {"plain": "", "synced": [(0, "Hello"), (1, "World")]})
    tracks = [replace(read_track(audio_file(f"{i}.wav", 5)), album="Test album", artist="Artist") for i in range(3)]
    for track in tracks:
        db.save_track(track)
    player = Player(db, audio_output="null")
    window = Window(db, player)
    try:
        window.show()
        for route in window.nav:
            window.show_route(route)
            assert window.heading.text() == route
        window.show_route("Biblioteca")
        assert window.table.model().rowCount() == 3
        window.favorite(tracks[0])
        window.show_route("Favoritos")
        assert window.table.model().rowCount() == 1
        key = db.create_collection("My album", "album")
        for track in tracks:
            db.add_member(key, track)
        window.show_route("Álbumes")
        window.open_collection_item(window.collections_list.item(0))
        assert window.table.model().rowCount() == 3
        window.move_member(0, 1)
        assert db.tracks(collection=key)[1].id == tracks[0].id
        window.play_row(0)
        until(lambda: player.loaded)
        window.open_queue()
        window.open_player()
        QTest.qWait(100)
        assert window.queue_list.count() == 3
        assert window.lyrics.count() == 2
        window.player_dialog.grab().save(str(tmp_path / "player.png"))

        def close_settings():
            for widget in QApplication.topLevelWidgets():
                if isinstance(widget, QDialog) and widget.windowTitle() == "Ajustes de MusicVault":
                    widget.accept()

        QTimer.singleShot(100, close_settings)
        window.settings()
        window.resize(920, 620)
        QTest.qWait(50)
        assert window.centralWidget().width() <= 920
        window.grab().save(str(tmp_path / "window.png"))
    finally:
        window.quit_app()


def test_search_album_covers_keep_full_square_geometry(app, db, monkeypatch, tmp_path):
    cover_path = tmp_path / "cover.png"
    cover = QPixmap(300, 300)
    cover.fill(QColor("#e0a458"))
    assert cover.save(str(cover_path))
    player = Player(db, audio_output="null")
    window = Window(db, player)
    try:
        window.catalog.search = lambda *args: {
            "tracks": [],
            "albums": [{
                "id": "album-1", "title": "Álbum", "artist": "Artista",
                "cover": "https://images.example/cover.jpg", "source": "deezer",
            }],
        }
        monkeypatch.setattr(window, "load_cover", lambda source, callback: callback(str(cover_path)))
        window.show()
        window.show_route("Buscar")
        window.search.setText("album")
        window.submit_search()
        until(lambda: window.album_list.count() == 1)

        item = window.album_list.item(0)
        assert window.album_list.height() == 224
        assert item.sizeHint() == QSize(180, 216)
        assert item.icon().actualSize(QSize(148, 148)) == QSize(148, 148)
    finally:
        window.quit_app()


def test_connected_account_offers_copied_spotify_track(app, db, monkeypatch):
    monkeypatch.setattr("musicvault.window.session_configured", lambda: True)
    player = Player(db, audio_output="null")
    window = Window(db, player)
    imported = []
    try:
        monkeypatch.setattr(window, "import_spotify_track", imported.append)
        window.clipboard.setText(
            "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=copied"
        )
        window.check_spotify_clipboard(force=True)

        assert not window.clipboard_prompt.isHidden()
        assert "una canción" in window.clipboard_prompt_text.text()
        window.import_spotify_clipboard()
        assert imported == ["https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"]
        assert window.clipboard_prompt.isHidden()
    finally:
        window.clipboard.clear()
        window.quit_app()


def test_clipboard_spotify_offer_requires_connected_account(app, db, monkeypatch):
    monkeypatch.setattr("musicvault.window.session_configured", lambda: False)
    player = Player(db, audio_output="null")
    window = Window(db, player)
    try:
        window.clipboard.setText(
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
        )
        window.check_spotify_clipboard(force=True)
        assert window.clipboard_prompt.isHidden()
    finally:
        window.clipboard.clear()
        window.quit_app()


def test_library_filters_sorting_and_resizable_columns(app, db, audio_file):
    tracks = [
        replace(read_track(audio_file("zeta.wav", 2)), title="Zeta", artist="Beta", album="Dos"),
        replace(read_track(audio_file("alfa.wav", 2)), title="Alfa", artist="Ámbar", album="Uno"),
        replace(read_track(audio_file("medio.wav", 2)), title="Medio", artist="Beta", album="Uno"),
    ]
    for track in tracks:
        db.save_track(track)
    player = Player(db, audio_output="null")
    window = Window(db, player)
    try:
        window.show()
        window.show_route("Biblioteca")
        assert window.library_filters.isVisible()
        window.artist_filter.setCurrentIndex(window.artist_filter.findData("Beta"))
        assert {track.artist for track in window.shown_tracks} == {"Beta"}
        window.artist_filter.setCurrentIndex(0)
        window.library_sort.setCurrentIndex(window.library_sort.findData("title"))
        assert [track.title for track in window.shown_tracks] == ["Alfa", "Medio", "Zeta"]
        window.album_filter.setCurrentIndex(window.album_filter.findData("Uno"))
        assert {track.album for track in window.shown_tracks} == {"Uno"}
        window.album_filter.setCurrentIndex(0)
        window.library_sort.setCurrentIndex(window.library_sort.findData("artist"))
        assert [track.artist for track in window.shown_tracks] == ["Ámbar", "Beta", "Beta"]
        window.resize(1440, 900)
        window.table.horizontalHeader().resizeSection(1, 444)
        QTest.qWait(300)
        assert db.setting("track_column_widths")["title"] == 444
    finally:
        window.quit_app()


def test_downloading_catalog_album_creates_album_collection(app, db, monkeypatch):
    from musicvault.models import Track
    album = {"id": "album-7", "title": "Disco", "artist": "Artista", "cover": "cover", "source": "deezer"}
    tracks = [Track(f"deezer:{index}", f"Tema {index}", "Artista", "Disco", source="deezer") for index in range(4)]
    player = Player(db, audio_output="null")
    window = Window(db, player)
    started = []
    try:
        window.remote_album = album
        window.shown_tracks = tracks
        monkeypatch.setattr(window, "start_download", lambda track, **kwargs: started.append((track, kwargs)))
        window.download_all()
        collection = db.collection_by_source("deezer", "album-7", kind="album")
        assert collection and collection["title"] == "Disco" and collection["kind"] == "album"
        assert [track.id for track in db.tracks(collection=collection["id"])] == [track.id for track in tracks]
        assert [track.id for track, _kwargs in started] == [track.id for track in tracks]
        assert all(kwargs["prepared"] and kwargs["defer_refresh"] for _track, kwargs in started)
    finally:
        window.quit_app()


def test_shared_download_consumers_and_stop_before_completion(app, db, audio_file, monkeypatch):
    source = read_track(audio_file(duration=5))
    remote = replace(source, id="deezer:one", source="deezer", path="")
    release = threading.Event()
    calls, consumers = [], []

    def fake_download(track, destination, cancel, progress):
        calls.append(track.id)
        release.wait(3)
        return replace(track, path=source.path)

    monkeypatch.setattr("musicvault.window.download", fake_download)
    player = Player(db, audio_output="null")
    window = Window(db, player)
    try:
        player.play_queue([remote])
        window.start_download(remote, consumers.append)
        window.start_download(remote, consumers.append)
        until(lambda: calls == [remote.id])
        player.stop()
        release.set()
        until(lambda: not window.download_jobs)
        assert len(consumers) == 2
        assert db.get_track(remote.id).path == source.path
        assert not player.playing
        assert db.downloads()[0]["status"] == "complete"
    finally:
        release.set()
        window.quit_app()


def test_failed_download_requires_explicit_retry_and_never_looks_local(app,db,audio_file,monkeypatch):
    from musicvault.models import Track
    remote=Track("deezer:failed","Song",source="deezer")
    local=read_track(audio_file(duration=5))
    db.save_track(remote);db.set_download(remote,"error",error="YouTube rechazó la descarga (403)")
    calls=[]
    monkeypatch.setattr("musicvault.window.download",lambda track,*args:(calls.append(track.id),replace(track,path=local.path))[1])
    player=Player(db,audio_output="null");window=Window(db,player)
    try:
        window.show_route("Biblioteca")
        assert window.table.model().rowCount()==0
        player.play_queue([remote]);QTest.qWait(50)
        assert not calls and not window.message_bar.isHidden()
        window.retry_action()
        until(lambda:not window.download_jobs)
        assert calls==[remote.id] and player.playing
        window.show_route("Biblioteca")
        assert window.table.model().rowCount()==1
        player.stop();window.play_row(0)
        until(lambda:player.loaded)
        assert calls==[remote.id]
    finally:window.quit_app()


def test_large_library_loads_only_visible_artwork_and_keeps_selection(app, db, audio_file, monkeypatch):
    from musicvault.models import Track
    from PyQt6.QtCore import Qt
    tracks = [Track(str(i), f"Track {i}", path=str(audio_file("shared.wav")) if i == 0 else "",
                    cover=f"https://example.test/{i}.jpg") for i in range(2000)]
    tracks = [replace(t, path=tracks[0].path) for t in tracks]
    player = Player(db, audio_output="null")
    window = Window(db, player)
    requested = []
    monkeypatch.setattr(window, "load_cover", lambda source, callback: requested.append(source))
    try:
        window.show()
        window.show_route("Biblioteca")
        window.render_tracks(tracks)
        QTest.qWait(100)
        assert 0 < len(requested) < 25
        window.table.selectRow(2)
        window.render_tracks(tracks)
        assert window.selected_tracks()[0].id == "2"
        requested.clear()
        window.table.scrollToBottom()
        QTest.qWait(100)
        assert tracks[-1].cover in requested
        assert len(requested) < 25
        window.track_model.set_progress("1999", 42)
        assert "42%" in window.track_model.index(1999, 5).data()
        window.track_model.set_current("1999")
        assert window.track_model.index(1999, 1).data(Qt.ItemDataRole.UserRole)
        assert window.selected_tracks()[0].id == "2"
    finally:
        window.quit_app()


def test_cover_requests_are_shared_and_reused_after_navigation(app, db, monkeypatch):
    release = threading.Event()
    calls, results = [], []

    def fetch(source):
        calls.append(source)
        release.wait(3)
        return "/tmp/shared-cover.png"

    monkeypatch.setattr("musicvault.window.cover_file", fetch)
    player = Player(db, audio_output="null")
    window = Window(db, player)
    try:
        source = "https://example.test/cover.jpg"
        for _ in range(10):
            window.load_cover(source, results.append)
        until(lambda: len(calls) == 1)
        window.show_route("Buscar")
        release.set()
        until(lambda: len(results) == 10)
        window.load_cover(source, results.append)
        assert len(results) == 11 and len(calls) == 1
    finally:
        release.set()
        window.quit_app()


def test_global_search_playlist_shortcuts_and_idle_animation(app, db, audio_file, monkeypatch):
    monkeypatch.setattr("musicvault.window.fetch_lyrics", lambda *args: {"plain": "", "synced": []})
    source = read_track(audio_file(duration=5))
    db.save_track(source)
    key = db.create_collection("Focus")
    db.add_member(key, source)
    player = Player(db, audio_output="null")
    window = Window(db, player)
    try:
        window.show()
        window.search.setText("test")
        assert window.route == "Buscar" and window.search.text() == "test"
        window.open_sidebar_collection(window.sidebar_collections.item(0))
        assert window.collection == key
        assert window.shown_tracks == [source]
        window.play_row(0)
        until(lambda: player.loaded)
        window.open_player()
        assert window.record.timer.isActive()
        window.player_dialog.hide()
        assert not window.record.timer.isActive()
        window.player_dialog.show()
        assert window.record.timer.isActive()
        player.toggle()
        QTest.qWait(2200)
        assert not window.record.timer.isActive()
    finally:
        window.quit_app()


def test_stored_dns_failure_uses_banner_with_details_and_explicit_retry(app, db):
    from musicvault.models import Track
    track = Track("youtube:dns", "Una canción", source="youtube")
    raw = "No se pudo descargar: HTTPSConnection: Failed to resolve 'www.youtube.com' ([Errno -3] Temporary failure in name resolution)"
    db.save_track(track)
    db.set_download(track, "error", error=raw)
    player = Player(db, audio_output="null")
    window = Window(db, player)
    try:
        window.resize(920, 680)
        window.show()
        player.play_queue([track])
        QTest.qWait(50)
        assert "DNS" in window.message_text.text()
        assert "HTTPSConnection" not in window.message_text.text()
        assert "HTTPSConnection" not in window.statusBar().currentMessage()
        assert window.message_details.isVisible()
        assert window.download_error_details == raw
        assert not window.download_jobs
        window.show_route("Descargas")
        assert "DNS" in window.downloads_list.item(0).text()
        assert "HTTPSConnection" not in window.downloads_list.item(0).text()
        assert window.width() == 920
    finally:
        window.quit_app()

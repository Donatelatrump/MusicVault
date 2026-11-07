from collections import OrderedDict, Counter
from dataclasses import replace
from pathlib import Path
import json
import time
import unicodedata

from PyQt6.QtCore import Qt, QTimer, QUrl, QSize
from PyQt6.QtGui import QAction, QDesktopServices, QIcon, QKeySequence, QPixmap, QShortcut, QColor, QTransform
from PyQt6.QtWidgets import (
    QApplication, QAbstractItemView, QCheckBox, QComboBox, QCompleter, QDialog,
    QFileDialog, QFrame, QHBoxLayout, QHeaderView,
    QInputDialog, QLabel, QLineEdit, QListWidget, QListWidgetItem, QMainWindow,
    QMenu, QMessageBox, QPushButton, QScrollArea, QSpinBox,
    QStackedWidget, QSystemTrayIcon, QVBoxLayout, QWidget,
)

from . import __version__
from .library import scan
from .models import Track, clock_text, data_dir
from .services import Catalog, DownloadCancelled, Jobs, cover_file, download, fetch_lyrics
from .spotify import spotify_url_from_text
from .songmirror_spotify import clear_session, save_session, session_configured, validate_session
from .downloader import explain_download_error
from .streaming import StreamingService, StreamCancelled
from .experience import artist_matches, DjRecommendationEngine, DjUiState, JAM_MIX_ID, round_robin, taste_signature
from .experience_ui import OnboardingDialog, StartupOverlay, TrackPickerDialog
from .widgets import Cover, Record, STYLE, ElidedLabel, icon, load_fonts


def button(text, callback, primary=False):
    widget = QPushButton(text)
    if primary:
        widget.setObjectName("primary")
    widget.clicked.connect(callback)
    return widget


def label(text, name=""):
    widget = ElidedLabel(text) if name == "trackTitle" else QLabel(text)
    widget.setTextFormat(Qt.TextFormat.PlainText)
    if name:
        widget.setObjectName(name)
    return widget


class Window(QMainWindow):
    def __init__(self, db, player):
        super().__init__()
        self.db, self.player = db, player
        self.jobs, self.catalog = Jobs(self), Catalog(db)
        self.streaming = StreamingService(db)
        self.stream_jobs = Jobs(self)
        self.stream_jobs.pool.setMaxThreadCount(1)
        self.warm_jobs = Jobs(self)
        self.warm_jobs.pool.setMaxThreadCount(1)
        self.route, self.collection, self.remote_album = "Inicio", None, None
        self.local_album = None
        self.shown_tracks, self.remote_tracks, self.remote_albums = [], [], []
        self.remote_catalog_tracks, self.remote_youtube_tracks = [], []
        self.remote_playlists, self.remote_playlist = [], None
        self.search_generation = 0
        self._submitted_search_query = ""
        self.search_job = None
        self.search_tracks_job = None
        self.search_albums_job = None
        self.search_youtube_job = None
        self.search_playlists_job = None
        self.album_job = None
        self.playlist_job = None
        self.search_mode = "tracks"
        self.search_loading_tracks = False
        self.search_loading_albums = False
        self.search_loading_playlists = False
        self.home_recommendations, self.home_recommendation_albums = [], []
        self.taste_generation = 0
        self.jam_engine = DjRecommendationEngine()
        self.jam_state = DjUiState()
        self.jam_discover_job = None
        self._startup_overlay = None
        self.download_jobs, self.download_callbacks = {}, {}
        self.download_items = {}
        self.download_progress_values = {}
        self.cover_pending, self.cover_cache = {}, OrderedDict()
        self.cover_failures = {}
        self.cover_attempts = {}
        self.cover_jobs = Jobs(self)
        self.cover_jobs.pool.setMaxThreadCount(4)
        self.home_signature = None
        self.stream_prepare_job = None
        self.stream_prepare_track_id = None
        self.stream_warm_job = None
        self.stream_warm_track_id = None
        self.stream_warm_generation = 0
        self._spinner_angle = 0
        self.album_render_signature = None
        self.sidebar_signature = None
        self.library_filters_signature = None
        self.lyric_track, self.lyric_lines = None, []
        self.lyric_times = []
        self.lyric_generation = 0
        self._clipboard_last_url = None
        self._clipboard_spotify = None
        self._quitting = False
        self.mpris = None
        load_fonts()
        self.setWindowTitle("MusicVault")
        self.setWindowIcon(QIcon(str(Path(__file__).parent / "assets/musicvault.png")))
        self.resize(1440, 900)
        self.setMinimumSize(920, 680)
        self.setAcceptDrops(True)
        self.setStyleSheet(STYLE)
        self._build()
        self.clipboard = QApplication.clipboard()
        self.clipboard.dataChanged.connect(self.check_spotify_clipboard)
        QTimer.singleShot(0, lambda: self.check_spotify_clipboard(force=True))
        self.download_refresh_timer = QTimer(self)
        self.download_refresh_timer.setSingleShot(True)
        self.download_refresh_timer.setInterval(160)
        self.download_refresh_timer.timeout.connect(self.refresh_download_surfaces)
        self.play_spinner_timer = QTimer(self)
        self.play_spinner_timer.setInterval(80)
        self.play_spinner_timer.timeout.connect(self.animate_play_spinner)
        self.screen_warm_timer = QTimer(self)
        self.screen_warm_timer.setSingleShot(True)
        self.screen_warm_timer.setInterval(650)
        self.screen_warm_timer.timeout.connect(self.warm_visible_streams)
        self._tray()
        self._shortcuts()
        player.track_changed.connect(self.track_changed)
        player.changed.connect(self.playback_changed)
        player.progress.connect(self.playback_progress)
        player.queue_changed.connect(self.refresh_queue)
        player.error.connect(self.notify)
        player.need_track.connect(self.ensure_playable)
        player.stream_cached.connect(self.stream_cached)
        self.refresh_sidebar()
        self.show_route("Inicio")
        self.track_changed(player.current)
        self.playback_changed()
        self.refresh_queue()
        QTimer.singleShot(0, self.start_taste_experience)
        self.auto_scan = QTimer(self)
        self.auto_scan.setInterval(5 * 60 * 1000)
        self.auto_scan.timeout.connect(self.rescan)
        if db.setting("auto_scan", True):
            self.auto_scan.start()
            QTimer.singleShot(500, self.rescan)

    def _build(self):
        from .layout import build_window
        build_window(self)

    def notify(self, message):
        self.statusBar().showMessage(str(message), 15000)

    def check_spotify_clipboard(self, force=False):
        """Offer supported copied Spotify links only while an account is connected."""
        found = spotify_url_from_text(self.clipboard.text())
        canonical = found[2] if found else None
        if not force and canonical == self._clipboard_last_url:
            return
        self._clipboard_last_url = canonical
        if not found or not session_configured():
            self._clipboard_spotify = None
            self.hide_spotify_clipboard_prompt()
            return
        kind, _resource_id, canonical = found
        self._clipboard_spotify = (kind, canonical)
        noun = "una canción" if kind == "track" else "una playlist"
        self.clipboard_prompt_text.setText(
            f"Copiaste {noun} de Spotify. ¿Quieres importarla a MusicVault?"
        )
        self.clipboard_prompt.show()
        self.clipboard_prompt_host.show()

    def hide_spotify_clipboard_prompt(self):
        self.clipboard_prompt.hide()
        self.clipboard_prompt_host.hide()

    def import_spotify_clipboard(self):
        selected = self._clipboard_spotify
        self.hide_spotify_clipboard_prompt()
        if not selected:
            return
        kind, canonical = selected
        if kind == "playlist":
            self.show_route("Buscar")
            self.search.blockSignals(True)
            self.search.setText(canonical)
            self.search.blockSignals(False)
            self.import_spotify_playlist(canonical)
        else:
            self.import_spotify_track(canonical)

    def schedule_download_refresh(self):
        self.download_refresh_timer.start()

    def refresh_download_surfaces(self):
        self.refresh_downloads()
        if self.route not in ("Álbumes", "Descargas"):
            self.refresh_tracks()

    def _tray(self):
        self.tray = None
        if QSystemTrayIcon.isSystemTrayAvailable():
            self.tray = QSystemTrayIcon(self.windowIcon(), self)
            self.tray.setToolTip("MusicVault")
            menu = QMenu(self)
            menu.addAction("Mostrar MusicVault", self.restore_from_tray)
            menu.addAction("Reproducir / pausar", self.player.toggle)
            menu.addAction("Siguiente", self.player.next)
            menu.addSeparator()
            menu.addAction("Salir", self.quit_app)
            self.tray.setContextMenu(menu)
            self.tray.activated.connect(
                lambda reason: self.restore_from_tray()
                if reason == QSystemTrayIcon.ActivationReason.Trigger else None
            )
            self.tray.show()

    def _hide_auxiliary_windows_for_tray(self):
        """Hide every MusicVault top-level surface before the main window goes to tray."""
        app = QApplication.instance()
        if not app:
            return
        for widget in list(app.topLevelWidgets()):
            if widget is self:
                continue
            try:
                # Popups, menus and dialogs can otherwise survive the main-window
                # hide operation as tiny orphan windows under Plasma/KWin.
                widget.hide()
            except RuntimeError:
                pass

    def restore_from_tray(self):
        # Only the real main window is restored. Auxiliary dialogs stay hidden
        # until the user explicitly opens them again.
        if self.isMinimized():
            self.setWindowState(self.windowState() & ~Qt.WindowState.WindowMinimized)
        self.showNormal()
        self.raise_()
        self.activateWindow()

    def _shortcuts(self):
        bindings = {"Ctrl+F": lambda: (self.show_route("Buscar"), self.search.setFocus()),
                    "Ctrl+Q": self.quit_app, "Ctrl+P": self.player.toggle,
                    "Alt+Right": self.player.next, "Alt+Left": self.player.previous,
                    "Ctrl+L": self.open_queue}
        self.shortcuts = [QShortcut(QKeySequence(key), self, activated=callback) for key, callback in bindings.items()]

    def show_route(self, route):
        self.route, self.collection, self.remote_album = route, None, None
        self.remote_playlist = None
        self.local_album = None
        for name, widget in self.nav.items():
            active = name == route
            widget.setChecked(active)
            glyph = {"Inicio":"home","Buscar":"search","Biblioteca":"library","Álbumes":"album","Descargas":"download","Favoritos":"heart","Jam":"queue"}[name]
            widget.setIcon(icon(glyph,"#E0A458" if active else "#B3A992",20))
        self.heading.setText(route)
        self.heading.setVisible(route != "Inicio")
        self.home_greeting.setVisible(route == "Inicio")
        self.description.hide()
        self.home_shelf.setVisible(route == "Inicio")
        self.retry_download_button.setVisible(route == "Descargas")
        self.cancel_download_button.setVisible(route == "Descargas")
        self.favorites_filter.setVisible(route in ("Biblioteca","Favoritos"))
        self.library_filters.setVisible(route in ("Biblioteca", "Favoritos"))
        self.list_title.setText("Tu música" if route == "Inicio" else "Canciones")
        self.search_button.setVisible(route == "Buscar")
        self.search.show()
        self.search.setPlaceholderText("Filtrar tu música" if route in ("Biblioteca", "Favoritos") else "¿Qué quieres escuchar?")
        self.library_badge.setVisible(route != "Buscar" and self.width() >= 1200)
        self.search.blockSignals(True)
        self.search.clear()
        self.search.blockSignals(False)
        self._submitted_search_query = ""
        self.debounce.stop()
        self.local_debounce.stop()
        if self.search_job:
            self.search_job.cancel.set()
        if self.search_tracks_job:
            self.search_tracks_job.cancel.set()
        if self.search_albums_job:
            self.search_albums_job.cancel.set()
        if self.search_youtube_job:
            self.search_youtube_job.cancel.set()
        if self.search_playlists_job:
            self.search_playlists_job.cancel.set()
        if self.album_job:
            self.album_job.cancel.set()
        if hasattr(self, "import_loading"):
            self.import_loading.hide()
        self.search_generation += 1
        self.remote_tracks, self.remote_albums, self.remote_playlists = [], [], []
        self.remote_catalog_tracks, self.remote_youtube_tracks = [], []
        self.search_loading_tracks = self.search_loading_albums = self.search_loading_playlists = False
        self.search_mode = "tracks"
        self.search_tabs.hide()
        self.search_tracks_tab.setChecked(True)
        self.search_albums_tab.setChecked(False)
        self.search_playlists_tab.setChecked(False)
        self.download_all_button.hide()
        self.save_remote_playlist_button.hide()
        self.collection_menu_button.hide()
        self.collection_delete_button.hide()
        self.album_section_title.hide()
        self.playlist_section_title.hide()
        self.playlist_list.hide()
        self.new_collection_button.setVisible(route == "Álbumes")
        self.rescan_button.setVisible(route == "Biblioteca")
        self.play_all_button.setVisible(route in ("Biblioteca","Favoritos","Buscar","Jam"))
        self.album_list.hide()
        self.album_render_signature = None
        if route == "Álbumes":
            self.stack.setCurrentWidget(self.collections_list)
            self.refresh_collections()
        elif route == "Descargas":
            self.stack.setCurrentWidget(self.downloads_list)
            self.refresh_downloads()
        elif route == "Jam":
            self.stack.setCurrentWidget(self.track_page)
            self.refresh_tracks()
        else:
            self.stack.setCurrentWidget(self.track_page)
            self.refresh_tracks()
            if route == "Inicio":
                self.render_album_results(self.home_recommendation_albums, title="Álbumes para ti")

    def refresh_tracks(self):
        query = self.search.text()
        availability = {}
        if self.remote_album or self.remote_playlist:
            tracks = [self.db.get_track(t.id) or t for t in self.remote_tracks]
        elif self.local_album is not None:
            tracks = [t for key in self.local_album if (t := self.db.get_track(key))]
        elif self.collection:
            tracks = self.db.tracks(query, collection=self.collection)
        elif self.route in ("Biblioteca", "Favoritos"):
            favorites = self.route == "Favoritos"
            self.refresh_library_filters(self.db.library_facets(favorites=favorites))
            artist = self.artist_filter.currentData() or ""
            album = self.album_filter.currentData() or ""
            tracks = self.db.library_tracks(query, favorites=favorites, availability=availability,
                                            artist=artist, album=album)
            order = self.library_sort.currentData()
            def sort_text(value):
                return "".join(
                    char for char in unicodedata.normalize("NFKD", value.casefold())
                    if not unicodedata.combining(char)
                )

            if order == "title":
                tracks.sort(key=lambda track: (sort_text(track.title), sort_text(track.artist)))
            elif order == "artist":
                tracks.sort(key=lambda track: (sort_text(track.artist), sort_text(track.album), sort_text(track.title)))
            elif order == "album":
                tracks.sort(key=lambda track: (sort_text(track.album), sort_text(track.artist), sort_text(track.title)))
        elif self.route == "Inicio":
            recent = self.db.library_tracks(query, recent=True, availability=availability)
            recommended = [self.db.get_track(t.id) or t for t in self.home_recommendations]
            seen = {t.id for t in recommended}
            tracks = recommended + [t for t in recent if t.id not in seen]
            seen.update(t.id for t in recent)
            tracks += [t for t in self.db.library_tracks(query, availability=availability) if t.id not in seen]
        elif self.route == "Buscar":
            local = self.db.tracks(query)
            seen = {t.id for t in local}
            tracks = local + [self.db.get_track(t.id) or t for t in self.remote_tracks if t.id not in seen] if query else []
        elif self.route == "Jam":
            tracks = list(self.jam_state.upcoming)
        else:
            tracks = self.db.library_tracks(query, availability=availability)
        self.render_tracks(tracks, availability)

    def refresh_library_filters(self, facets):
        """Refresh facet choices without discarding the listener's selection."""
        artists = tuple(value.strip() for value in facets[0] if value.strip())
        albums = tuple(value.strip() for value in facets[1] if value.strip())
        signature = (artists, albums)
        if signature == self.library_filters_signature:
            return
        self.library_filters_signature = signature

        def refill(combo, first, values):
            selected = combo.currentData() or ""
            combo.blockSignals(True)
            combo.clear()
            combo.addItem(first, "")
            for value in sorted({value.strip() for value in values if value.strip()}, key=str.casefold):
                combo.addItem(value, value)
            index = combo.findData(selected)
            combo.setCurrentIndex(index if index >= 0 else 0)
            combo.blockSignals(False)

        refill(self.artist_filter, "Todos los artistas", artists)
        refill(self.album_filter, "Todos los álbumes", albums)

    def render_tracks(self, tracks, availability=None):
        selected = {t.id for t in self.selected_tracks()}
        prior_ids = [t.id for t in self.shown_tracks]
        self.shown_tracks = tracks
        records = {r["track_id"]: r for r in self.db.downloads()}
        current = self.player.current
        availability = availability or {}
        for track in tracks:
            if track.id not in availability:
                availability[track.id] = bool(track.path and Path(track.path).is_file())
        self.track_model.set_tracks(tracks, records, self.download_jobs,
                                    current.id if current else None, availability)
        # Keep a bounded native viewport so Qt recycles/paints only visible rows.
        # Older desktop builds expanded the table to every row, which made large
        # albums and playlists stutter because the outer page owned all scrolling.
        header_height = self.table.horizontalHeader().height() or 34
        content_height = header_height + max(1, len(tracks)) * self.table.verticalHeader().defaultSectionSize() + 2
        viewport_cap = max(360, min(680, self.main_scroll.viewport().height() - 120))
        table_height = min(content_height, viewport_cap)
        if self.table.height() != table_height:
            self.table.setFixedHeight(table_height)
            self.stack.updateGeometry()
        if prior_ids != [t.id for t in tracks]:
            from PyQt6.QtCore import QItemSelectionModel
            selection = self.table.selectionModel()
            for key in selected:
                row = self.track_model.rows.get(key)
                if row is not None:
                    selection.select(self.track_model.index(row, 0), QItemSelectionModel.SelectionFlag.Select | QItemSelectionModel.SelectionFlag.Rows)
        self.empty_panel.setVisible(not tracks)
        self.table.setVisible(bool(tracks))
        self.list_title.setVisible(bool(tracks))
        searching = self.route == "Buscar"
        self.empty_title.setText("Encuentra tu próxima canción" if searching else "Tu música empieza aquí")
        self.empty.setText("Busca canciones, artistas o álbumes en el catálogo y en tu biblioteca." if searching else
                           "Importa tus canciones o añade música desde la búsqueda.")
        self.empty_import.setVisible(not searching)
        self.count.setText(f"{len(tracks)} canciones")
        self.play_all_button.setEnabled(bool(tracks))
        if self.route == "Inicio":
            self.render_home(tracks)
            self.render_album_results(self.home_recommendation_albums, title="Álbumes para ti")
        self.apply_search_mode()
        self.table.schedule_covers()
        if prior_ids != [t.id for t in tracks] and tracks:
            self.schedule_visible_stream_warm(initial=True)

    def load_visible_covers(self):
        if self._quitting:
            return
        sources = {self.shown_tracks[row].cover for row in self.table.visible_rows()}
        for source in sources - self.track_model.covers.keys():
            if source:
                self.load_cover(source, lambda path, source=source: self.track_model.set_cover(source, path))

    def render_home(self, tracks):
        choices = tracks[:8]
        self.home_shelf.setVisible(bool(choices))
        small = self.height() < 780
        edge = 112 if small else 148
        self.home_scroll.setFixedHeight(188 if small else 224)
        signature = (small, tuple((t.id, t.title, t.artist, t.cover) for t in choices))
        if signature == self.home_signature:
            return
        self.home_signature = signature
        while self.home_cards_layout.count():
            item = self.home_cards_layout.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        for i, track in enumerate(choices):
            card = QFrame()
            card.setObjectName("card")
            card.setFixedWidth(edge + 16)
            column = QVBoxLayout(card)
            column.setContentsMargins(8, 8, 8, 10)
            column.setSpacing(5)
            cover = button("", lambda checked=False, index=i, items=choices: self.player.play_queue(items, index))
            cover.setObjectName("albumCover")
            cover.setFixedSize(edge, edge)
            cover.setIconSize(QSize(edge, edge))
            cover.setToolTip(f"Reproducir {track.title}")
            cover.setAccessibleName(f"Reproducir {track.title}")
            cover.setIcon(icon("album", "#B3A992", 72))
            column.addWidget(cover)
            title = label(track.title, "trackTitle")
            title.setToolTip(track.title)
            title.setFixedWidth(edge)
            column.addWidget(title)
            artist = ElidedLabel(track.artist)
            artist.setObjectName("muted")
            artist.setToolTip(track.artist)
            artist.setFixedWidth(edge)
            column.addWidget(artist)
            self.home_cards_layout.addWidget(card)
            self.load_cover(track.cover, lambda path, cover=cover: cover.setIcon(QIcon(path)) if path else None)
        self.home_cards_layout.addStretch()

    def refresh_sidebar(self):
        collections = self.db.collections()
        signature = tuple((c["id"], c["title"], c["count"], c.get("cover", "")) for c in collections)
        if signature == self.sidebar_signature:
            return
        self.sidebar_signature = signature
        self.sidebar_collections.clear()
        for collection in collections:
            item = QListWidgetItem(collection["title"])
            item.setIcon(icon("queue" if collection["kind"] == "playlist" else "album", "#B3A992", 28))
            item.setData(Qt.ItemDataRole.UserRole, collection)
            item.setToolTip(f"{collection['title']} · {collection['count']} canciones")
            self.sidebar_collections.addItem(item)
            if collection.get("cover"):
                self.load_cover(collection["cover"], lambda path, item=item: item.setIcon(QIcon(path)) if path else None)
        self.sidebar_hint.setVisible(not collections)

    def open_sidebar_collection(self, item):
        self.show_route("Álbumes")
        self.open_collection_item(item)

    def resizeEvent(self, event):
        super().resizeEvent(event)
        if getattr(self, "_startup_overlay", None):
            self._startup_overlay.setGeometry(self.rect())
            self._startup_overlay.raise_()
        if hasattr(self, "table"):
            compact = self.width() < 1200
            header = self.table.horizontalHeader()
            header.setSectionResizeMode(
                1, QHeaderView.ResizeMode.Stretch if compact else QHeaderView.ResizeMode.Interactive
            )
            self.table.setColumnHidden(2, compact)
            self.table.setColumnHidden(3, compact)
            self.table.setColumnHidden(5, self.width() < 1050)
            self.library_badge.setVisible(not compact and self.route != "Buscar")
            self.sidebar.setFixedWidth(204 if compact else 244)
            if self.route == "Inicio":
                self.render_home(self.shown_tracks)

    def start_taste_experience(self):
        """Run taste onboarding only for a truly empty library.

        Existing users should never be asked to retake the taste test merely because
        the desktop settings database predates onboarding. Their downloaded/imported
        library is a stronger signal and is converted into a small artist seed.
        """
        complete = bool(self.db.setting("taste_onboarding_complete", False))
        if not complete:
            existing = self.db.library_tracks()
            if existing:
                favorites = self.db.favorites()
                weighted = Counter()
                for track in existing:
                    artist = (track.artist or "").strip()
                    if not artist or artist.casefold() == "artista desconocido":
                        continue
                    weighted[artist] += 3 if track.id in favorites else 1
                artists = [artist for artist, _count in weighted.most_common(8)]
                self.db.set_setting("taste_onboarding_complete", True)
                self.db.set_setting("favorite_genres", self.db.setting("favorite_genres", []) or [])
                self.db.set_setting("favorite_artists", artists)
                self._load_taste_cache()
                if artists:
                    self._start_taste_refresh(show_overlay=False)
                return
            dialog = OnboardingDialog(self)
            if dialog.exec() != QDialog.DialogCode.Accepted:
                return
            self.db.set_setting("taste_onboarding_complete", True)
            if dialog.skipped:
                return
            self.db.set_setting("favorite_genres", sorted(dialog.selected_genres, key=str.casefold))
            self.db.set_setting("favorite_artists", sorted(dialog.selected_artists, key=str.casefold))
            self._startup_overlay = StartupOverlay(self)
            self._startup_overlay.cover_parent()
            QTimer.singleShot(18000, self._hide_startup_overlay)
            self._start_taste_refresh(show_overlay=True)
            return
        self._load_taste_cache()
        # The local artist catalog is static and immediate; network recommendations
        # refresh at most once per week and never block a normal desktop launch.
        last = float(self.db.setting("taste_refresh_epoch", 0) or 0)
        if self.home_recommendations and time.time() - last < 7 * 86400:
            return
        self._start_taste_refresh(show_overlay=False)

    def _taste_cache_key(self):
        genres = self.db.setting("favorite_genres", []) or []
        artists = self.db.setting("favorite_artists", []) or []
        return "desktop:taste:" + taste_signature(genres, artists)

    def _load_taste_cache(self):
        cached = self.db.cache_get(self._taste_cache_key(), ttl=None)
        if not isinstance(cached, dict):
            return False
        self.home_recommendations = [Track.from_dict(item) for item in cached.get("tracks", [])]
        self.home_recommendation_albums = list(cached.get("albums", []))
        if self.route == "Inicio":
            self.refresh_tracks()
        return bool(self.home_recommendations or self.home_recommendation_albums)

    def _start_taste_refresh(self, show_overlay=False):
        artists = list(dict.fromkeys(self.db.setting("favorite_artists", []) or []))[:8]
        if not artists:
            self._hide_startup_overlay()
            return
        self.taste_generation += 1
        generation = self.taste_generation
        self._taste_finalized_generation = -1
        self._taste_pending = set(artists)
        self._taste_buckets = {artist: {"tracks": [], "albums": []} for artist in artists}

        def complete_artist(artist, payload=None):
            if generation != self.taste_generation or artist not in self._taste_pending:
                return
            if payload:
                tracks = [Track.from_dict(item) for item in payload.get("tracks", [])]
                self._taste_buckets[artist]["tracks"] = [t for t in tracks if artist_matches(t.artist, artist)][:8]
                self._taste_buckets[artist]["albums"] = [
                    album for album in payload.get("albums", [])
                    if artist_matches(album.get("artist", ""), artist)
                ][:5]
            self._taste_pending.discard(artist)
            self._publish_taste_buckets(generation, persist=False)
            if not self._taste_pending:
                self._finish_taste_refresh(generation, show_overlay)

        for artist in artists:
            def work(cancel, progress, artist=artist):
                tracks = self.catalog.search_tracks(artist, "catalog", cancel)
                if cancel.is_set():
                    return {"tracks": [], "albums": []}
                albums = self.catalog.search_albums(artist, cancel)
                return {"tracks": tracks, "albums": albums}

            self.jobs.submit(
                work,
                lambda payload, artist=artist: complete_artist(artist, payload),
                lambda error, artist=artist: complete_artist(artist, None),
            )
        QTimer.singleShot(15000, lambda: self._finish_taste_refresh(generation, show_overlay))

    def _publish_taste_buckets(self, generation, persist=False):
        """Merge successful artist buckets without letting a slow artist erase fast ones."""
        if generation != self.taste_generation:
            return
        artists = list((self.db.setting("favorite_artists", []) or []))[:8]
        track_buckets = [self._taste_buckets.get(artist, {}).get("tracks", []) for artist in artists]
        album_buckets = [self._taste_buckets.get(artist, {}).get("albums", []) for artist in artists]
        tracks = round_robin(track_buckets, 40)
        albums = round_robin(album_buckets, 24)
        changed = False
        if tracks and [t.id for t in tracks] != [t.id for t in self.home_recommendations]:
            self.home_recommendations = tracks
            changed = True
        album_signature = [(a.get("source"), a.get("id")) for a in albums]
        old_album_signature = [(a.get("source"), a.get("id")) for a in self.home_recommendation_albums]
        if albums and album_signature != old_album_signature:
            self.home_recommendation_albums = albums
            changed = True
        if persist and (self.home_recommendations or self.home_recommendation_albums):
            self.db.cache_put(self._taste_cache_key(), {
                "tracks": [track.to_dict() for track in self.home_recommendations],
                "albums": self.home_recommendation_albums,
            })
            self.db.set_setting("taste_refresh_epoch", time.time())
        if changed and self.route == "Inicio":
            self.refresh_tracks()

    def _finish_taste_refresh(self, generation, show_overlay=False):
        if generation != self.taste_generation:
            return
        first_finish = getattr(self, "_taste_finalized_generation", -1) != generation
        if first_finish:
            self._taste_finalized_generation = generation
        self._publish_taste_buckets(generation, persist=True)
        # The timeout may publish a partial result. Late artist jobs are still
        # allowed to merge into Home/cache when they finish; only the visual
        # handoff itself is one-shot.
        if show_overlay and first_finish:
            self._wait_home_visual_ready(generation)

    # ── Jam (DJ para ti) integration ──────────────────────────────────────

    def toggle_jam(self):
        """Activate or deactivate the DJ jam mix."""
        if self.jam_state.active:
            self.stop_jam()
        else:
            self.start_jam()

    def start_jam(self):
        """Generate a jam mix from local play history and show it."""
        if self.jam_state.loading:
            return
        self.jam_state = DjUiState(active=True, loading=True, reason="dj_reason_learning")
        self._refresh_jam_ui()
        self._generate_jam_mix()

    def stop_jam(self):
        """Deactivate the DJ jam mix and return to normal home view."""
        self.jam_state = DjUiState()
        if self.route == "Jam":
            self.show_route("Inicio")
        self._refresh_jam_ui()

    def _generate_jam_mix(self):
        """Build the jam mix using the recommendation engine, off the UI thread."""
        if self.jam_discover_job:
            self.jam_discover_job.cancel.set()
            self.jam_discover_job = None

        def work(cancel, progress):
            known = self.db.tracks()
            preferred = list(self.db.setting("favorite_artists", []) or [])
            history = []
            with self.db.connect() as db:
                for row in db.execute(
                    "SELECT track_id, played, completion FROM history ORDER BY played DESC"
                ):
                    history.append({
                        "track_id": row[0],
                        "played_at": row[1] * 1000,
                        "completion_ratio": row[2],
                    })
            current = self.player.current
            playable_offline = {
                t.id for t in known
                if t.path and __import__("pathlib").Path(t.path).is_file()
            }

            def discover(seed, limit):
                if cancel.is_set():
                    return []
                results = self.catalog.search_tracks(seed, "catalog", cancel)
                return results[:limit]

            mix, reason = self.jam_engine.recommendations(
                history, known, preferred, set(), current, True, playable_offline,
                discover_fn=discover,
            )
            return mix, reason

        def ready(payload):
            mix, reason = payload
            if self.jam_state.loading:
                self.jam_state = DjUiState(
                    active=True,
                    loading=False,
                    reason=reason,
                    upcoming=list(mix),
                    message=f"Mezcla DJ: {len(mix)} canciones",
                )
                self._refresh_jam_ui()
                if self.route != "Jam":
                    self.show_route("Jam")
                else:
                    self.refresh_tracks()

        def failed(error):
            self.jam_state = DjUiState(active=True, loading=False, message=str(error))
            self._refresh_jam_ui()

        self.jam_discover_job = self.jobs.submit(work, ready, failed)

    def _refresh_jam_ui(self):
        """Update nav/button states to reflect jam active/inactive."""
        jam_btn = self.nav.get("Jam")
        if jam_btn:
            jam_btn.setChecked(self.jam_state.active)
            jam_btn.setIcon(
                icon("queue", "#E0A458" if self.jam_state.active else "#B3A992", 20)
            )
        if hasattr(self, "heading") and self.route == "Jam":
            self.heading.setText("Mezcla DJ")
            self.heading.setVisible(True)
            self.description.setText(self.jam_state.reason or "")
            self.description.setVisible(bool(self.jam_state.reason))
        if self.jam_state.loading and self.route == "Jam":
            self.notify("Preparando mezcla DJ…")

    def _jam_discover_fn(self, seed, limit):
        """Online discovery callback for the jam engine."""
        results = self.catalog.search_tracks(seed, "catalog", None)
        return results[:limit]

    def _wait_home_visual_ready(self, generation):
        if generation != self.taste_generation:
            return
        sources = []
        for track in self.home_recommendations[:8]:
            if track.cover and track.cover not in sources:
                sources.append(track.cover)
        for album in self.home_recommendation_albums[:6]:
            cover = album.get("cover", "")
            if cover and cover not in sources:
                sources.append(cover)
        if not sources:
            QTimer.singleShot(120, self._hide_startup_overlay)
            return
        pending = {source for source in sources}

        def done(source):
            pending.discard(source)
            if not pending:
                QTimer.singleShot(120, self._hide_startup_overlay)

        for source in tuple(pending):
            self.load_cover(source, lambda path, source=source: done(source))
        QTimer.singleShot(6500, self._hide_startup_overlay)

    def _hide_startup_overlay(self):
        overlay = getattr(self, "_startup_overlay", None)
        if overlay:
            overlay.finish()
            self._startup_overlay = None

    def search_changed(self):
        """Update UI only; remote search runs exclusively on Enter/Search button."""
        self.debounce.stop()
        if self.route not in ("Buscar", "Biblioteca", "Favoritos"):
            query = self.search.text()
            self.show_route("Buscar")
            self.search.blockSignals(True)
            self.search.setText(query)
            self.search.blockSignals(False)
            self.search.setFocus()
        if self.route in ("Biblioteca", "Favoritos"):
            self.local_debounce.start()
            return
        if self.route != "Buscar":
            return
        if not self.search.text().strip():
            self.search_generation += 1
            self.screen_warm_timer.stop()
            self.cancel_stream_warm()
            for job in (self.search_job, self.search_tracks_job, self.search_youtube_job,
                        self.search_albums_job, self.search_playlists_job):
                if job:
                    job.cancel.set()
            self._submitted_search_query = ""
            self.remote_tracks, self.remote_albums, self.remote_playlists = [], [], []
            self.remote_catalog_tracks, self.remote_youtube_tracks = [], []
            self.search_loading_tracks = self.search_loading_albums = self.search_loading_playlists = False
            self.search_tabs.hide()
            self.album_list.clear(); self.album_list.hide()
            self.playlist_list.clear(); self.playlist_list.hide()
            self.refresh_tracks()
            self.apply_search_mode()
            return
        # Do not launch a new search while typing. If the user starts editing a
        # submitted query, stop only the stale background work; visible results
        # stay in place until Enter/Search explicitly commits the new query.
        if self.search.text().strip() != self._submitted_search_query:
            self.search_generation += 1
            self.screen_warm_timer.stop()
            self.cancel_stream_warm()
            for job in (self.search_job, self.search_tracks_job, self.search_youtube_job,
                        self.search_albums_job, self.search_playlists_job):
                if job:
                    job.cancel.set()
            self.search_loading_tracks = self.search_loading_albums = self.search_loading_playlists = False
        self.search_tabs.setVisible(bool(self.remote_tracks or self.remote_albums or self.remote_playlists))

    def update_search_tab_labels(self):
        self.search_tracks_tab.setText(f"Canciones ({len(self.remote_tracks)})" if self.remote_tracks else "Canciones")
        self.search_albums_tab.setText(f"Álbumes ({len(self.remote_albums)})" if self.remote_albums else "Álbumes")
        self.search_playlists_tab.setText(f"Playlists ({len(self.remote_playlists)})" if self.remote_playlists else "Playlists")

    @staticmethod
    def _youtube_first_query(query):
        terms = ("asmr", "podcast", "audiolibro", "audiobook", "entrevista",
                 "charla", "conferencia", "talk")
        lowered = query.casefold()
        return any(term in lowered for term in terms)

    @staticmethod
    def _track_signature(track):
        def clean(value):
            return "".join(ch for ch in unicodedata.normalize("NFKD", (value or "").casefold())
                           if ch.isalnum() and not unicodedata.combining(ch))
        return clean(track.title), clean(track.artist)

    def merge_remote_search_tracks(self, query):
        ordered = self.remote_youtube_tracks + self.remote_catalog_tracks
        result, seen_ids, seen_names = [], set(), set()
        for track in ordered:
            signature = self._track_signature(track)
            if track.id in seen_ids or signature in seen_names:
                continue
            result.append(track)
            seen_ids.add(track.id)
            seen_names.add(signature)
            if len(result) >= 60:
                break
        self.remote_tracks = result
        self.update_search_tab_labels()
        self.refresh_tracks()
        self.schedule_visible_stream_warm()

    def set_search_mode(self, mode):
        if mode not in ("tracks", "albums", "playlists"):
            return
        if mode == "albums" and not self.search_albums_tab.isEnabled():
            mode = "tracks"
        self.search_mode = mode
        self.search_tracks_tab.setChecked(mode == "tracks")
        self.search_albums_tab.setChecked(mode == "albums")
        self.search_playlists_tab.setChecked(mode == "playlists")
        if mode == "playlists" and not self.remote_playlists and not self.search_loading_playlists:
            self.start_playlist_search()
        self.apply_search_mode()

    def apply_search_mode(self):
        if not hasattr(self, "search_tabs") or self.route != "Buscar":
            return
        query = self.search.text().strip()
        active = len(query) >= 2
        if self.remote_album or self.remote_playlist:
            self.search_tabs.hide()
            self.album_list.hide()
            self.playlist_list.hide()
            self.album_section_title.hide()
            self.playlist_section_title.hide()
            self.table.setVisible(bool(self.shown_tracks))
            self.list_title.setVisible(bool(self.shown_tracks))
            self.play_all_button.setVisible(bool(self.shown_tracks))
            self.empty_panel.setVisible(not self.shown_tracks)
            return
        self.search_tabs.setVisible(active)
        if self.search_mode == "albums":
            self.table.hide()
            self.list_title.hide()
            self.play_all_button.hide()
            self.playlist_list.hide()
            self.playlist_section_title.hide()
            self.album_section_title.setText("Álbumes")
            self.album_section_title.setVisible(active)
            self.album_list.setVisible(bool(self.remote_albums))
            self.empty_panel.setVisible(active and not self.remote_albums)
            if active and not self.remote_albums:
                self.empty_title.setText("Buscando álbumes…" if self.search_loading_albums else "No encontramos álbumes")
                self.empty.setText("Prueba con el nombre exacto del artista o del álbum." if not self.search_loading_albums else "Consultando el catálogo sin bloquear las canciones.")
                self.empty_import.hide()
        elif self.search_mode == "playlists":
            self.table.hide()
            self.list_title.hide()
            self.play_all_button.hide()
            self.album_list.hide()
            self.album_section_title.hide()
            self.playlist_section_title.setText("Playlists de YouTube")
            self.playlist_section_title.setVisible(active)
            self.playlist_list.setVisible(bool(self.remote_playlists))
            self.empty_panel.setVisible(active and not self.remote_playlists)
            if active and not self.remote_playlists:
                self.empty_title.setText("Buscando playlists…" if self.search_loading_playlists else "No encontramos playlists")
                self.empty.setText("Prueba con otro nombre o pega una URL de playlist de YouTube." if not self.search_loading_playlists else "Consultando YouTube sin bloquear canciones ni álbumes.")
                self.empty_import.hide()
        else:
            self.album_list.hide()
            self.album_section_title.hide()
            self.playlist_list.hide()
            self.playlist_section_title.hide()
            self.table.setVisible(bool(self.shown_tracks))
            self.list_title.setVisible(bool(self.shown_tracks))
            self.play_all_button.setVisible(True)
            self.play_all_button.setEnabled(bool(self.shown_tracks))
            self.empty_panel.setVisible(active and not self.shown_tracks)
            if active and not self.shown_tracks:
                self.empty_title.setText("Buscando canciones…" if self.search_loading_tracks else "No encontramos canciones")
                self.empty.setText("Prueba con otro artista, título o álbum." if not self.search_loading_tracks else "Deezer aparece primero y YouTube refina los resultados en segundo plano.")
                self.empty_import.hide()

    def render_album_results(self, albums, title="Álbumes"):
        signature = (self.route, title, tuple((a.get("source", ""), a.get("id", ""), a.get("title", ""), a.get("artist", ""), a.get("cover", "")) for a in albums))
        if signature == self.album_render_signature:
            if self.route == "Inicio":
                self.album_section_title.setText(title)
                self.album_section_title.setVisible(bool(albums))
                self.album_list.setVisible(bool(albums))
            elif self.route == "Buscar":
                self.apply_search_mode()
            return
        self.album_render_signature = signature
        self.album_list.clear()
        for album in albums:
            item = QListWidgetItem(f"{album.get('title', '')}\n{album.get('artist', '')}")
            item.setData(Qt.ItemDataRole.UserRole, album)
            item.setSizeHint(self.album_list.gridSize())
            item.setTextAlignment(Qt.AlignmentFlag.AlignHCenter | Qt.AlignmentFlag.AlignTop)
            item.setIcon(icon("album", "#B3A992", 96))
            self.album_list.addItem(item)

            def set_album_cover(path, item=item):
                pixmap = QPixmap(path) if path else QPixmap()
                if not pixmap.isNull():
                    complete = pixmap.scaled(
                        148, 148, Qt.AspectRatioMode.KeepAspectRatio,
                        Qt.TransformationMode.SmoothTransformation,
                    )
                    item.setIcon(QIcon(complete))
                self.album_list.viewport().update()

            self.load_cover(album.get("cover", ""), set_album_cover)
        if self.route == "Inicio":
            self.album_section_title.setText(title)
            self.album_section_title.setVisible(bool(albums))
            self.album_list.setVisible(bool(albums))
        elif self.route == "Buscar":
            self.album_section_title.setText(title)
            self.update_search_tab_labels()
            self.apply_search_mode()

    def render_playlist_results(self, playlists):
        self.playlist_list.clear()
        for playlist in playlists:
            count = int(playlist.get("count") or 0)
            suffix = f" · {count} elementos" if count else ""
            item = QListWidgetItem(f"{playlist.get('title', '')}\n{playlist.get('artist', 'YouTube')}{suffix}")
            item.setData(Qt.ItemDataRole.UserRole, playlist)
            item.setSizeHint(self.playlist_list.gridSize())
            item.setTextAlignment(Qt.AlignmentFlag.AlignHCenter | Qt.AlignmentFlag.AlignTop)
            item.setIcon(icon("queue", "#B3A992", 96))
            self.playlist_list.addItem(item)

            def set_cover(path, item=item):
                pixmap = QPixmap(path) if path else QPixmap()
                if not pixmap.isNull():
                    complete = pixmap.scaled(148, 148, Qt.AspectRatioMode.KeepAspectRatio,
                                             Qt.TransformationMode.SmoothTransformation)
                    item.setIcon(QIcon(complete))
                self.playlist_list.viewport().update()

            self.load_cover(playlist.get("cover", ""), set_cover)
        self.update_search_tab_labels()
        self.apply_search_mode()

    def start_playlist_search(self):
        if self.route != "Buscar":
            return
        query = self.search.text().strip()
        if len(query) < 2:
            return
        if self.search_playlists_job:
            self.search_playlists_job.cancel.set()
        generation = self.search_generation
        self.search_loading_playlists = True
        self.apply_search_mode()

        def ready(playlists):
            if generation != self.search_generation or self.route != "Buscar":
                return
            self.search_loading_playlists = False
            self.remote_playlists = list(playlists)
            self.render_playlist_results(self.remote_playlists)

        def failed(message):
            if generation != self.search_generation or self.route != "Buscar":
                return
            self.search_loading_playlists = False
            self.remote_playlists = []
            self.render_playlist_results([])
            self.notify(message)

        self.search_playlists_job = self.jobs.submit(
            lambda cancel, progress: self.catalog.search_playlists(query, cancel), ready, failed,
        )

    def submit_search(self):
        self.debounce.stop()
        self.local_debounce.stop()
        if self.route != "Buscar":
            self.refresh_tracks()
            return
        query = self.search.text().strip()
        if len(query) < 2:
            return
        self.search.clearFocus()
        self.search_generation += 1
        generation = self.search_generation
        self.screen_warm_timer.stop()
        self.cancel_stream_warm()
        for job in (self.search_job, self.search_tracks_job, self.search_youtube_job,
                    self.search_albums_job, self.search_playlists_job):
            if job:
                job.cancel.set()
        self._submitted_search_query = query
        self.db.add_search(query)
        self.search_history.model().setStringList(self.db.searches())
        self.search_tabs.show()
        self.search_albums_tab.setEnabled(True)
        self.search_loading_tracks = True
        self.search_loading_albums = True
        self.search_loading_playlists = False
        self.remote_tracks, self.remote_albums, self.remote_playlists = [], [], []
        self.remote_catalog_tracks, self.remote_youtube_tracks = [], []
        self.render_album_results([], title="Álbumes")
        self.render_playlist_results([])
        self.refresh_tracks()
        pending = {"catalog", "youtube"}
        self._search_pending_track_sources = pending

        def publish_tracks(kind, rows):
            if generation != self.search_generation or self.route != "Buscar":
                return
            tracks = [Track.from_dict(t) for t in rows]
            if kind == "catalog":
                self.remote_catalog_tracks = tracks
            else:
                self.remote_youtube_tracks = tracks
            self._search_pending_track_sources.discard(kind)
            self.search_loading_tracks = bool(self._search_pending_track_sources)
            self.merge_remote_search_tracks(query)

        def tracks_error(kind, error):
            if generation != self.search_generation or self.route != "Buscar":
                return
            self._search_pending_track_sources.discard(kind)
            self.search_loading_tracks = bool(self._search_pending_track_sources)
            self.merge_remote_search_tracks(query)
            if not self.remote_tracks and not self.search_loading_tracks:
                self.notify(error)

        def albums_ready(albums):
            if generation != self.search_generation or self.route != "Buscar":
                return
            self.search_loading_albums = False
            self.remote_albums = list(albums)
            self.render_album_results(self.remote_albums, title="Álbumes")

        def albums_error(error):
            if generation == self.search_generation and self.route == "Buscar":
                self.search_loading_albums = False
                self.remote_albums = []
                self.render_album_results([], title="Álbumes")
                if not self.remote_tracks:
                    self.notify(error)

        self.search_tracks_job = self.jobs.submit(
            lambda cancel, progress: self.catalog.search_tracks(query, "catalog", cancel),
            lambda rows: publish_tracks("catalog", rows), lambda error: tracks_error("catalog", error),
        )
        self.search_albums_job = self.jobs.submit(
            lambda cancel, progress: self.catalog.search_albums(query, cancel),
            albums_ready, albums_error,
        )
        self.search_youtube_job = self.jobs.submit(
            lambda cancel, progress: self.catalog.search_tracks(query, "youtube", cancel),
            lambda rows: publish_tracks("youtube", rows), lambda error: tracks_error("youtube", error),
        )
        if self.search_mode == "playlists":
            self.start_playlist_search()

    def import_spotify_playlist(self, url):
        self.search_generation += 1
        generation = self.search_generation
        if self.search_job:
            self.search_job.cancel.set()
        self.import_loading.show()
        self.notify("Leyendo playlist de Spotify…")

        def ready(playlist):
            self.import_loading.hide()
            if generation != self.search_generation or self.route != "Buscar":
                return
            tracks = self.choose_playlist_tracks(playlist, "Spotify")
            if tracks is None:
                self.notify("Importación de playlist cancelada.")
                return
            if not tracks:
                QMessageBox.information(self, "Playlist vacía", "Selecciona al menos una canción para importarla.")
                return
            existing = self.db.collection_by_source("spotify", playlist.id)
            verb = "Actualizar" if existing else "Agregar"
            amount = f"{len(tracks)} canciones seleccionadas"
            if playlist.total > len(playlist.tracks):
                amount += f" de {playlist.total} expuestas por Spotify"
            answer = QMessageBox.question(self, "Playlist de Spotify",
                f"{playlist.title}\n\n{amount}.\n\n¿{verb} esta playlist en MusicVault?")
            if answer != QMessageBox.StandardButton.Yes:
                return
            key = existing["id"] if existing else self.db.create_collection(playlist.title, "playlist", "spotify", playlist.id,
                playlist.url, playlist.cover)
            self.db.replace_collection(key, playlist.title, tracks, "spotify", playlist.id, playlist.url, playlist.cover)
            self.refresh_sidebar()
            self.remote_tracks = list(tracks)
            self.collection = key
            # La URL se usa únicamente para importar. Si permanece en Buscar,
            # los refrescos posteriores filtran la colección completa por ella.
            self.search.blockSignals(True)
            self.search.clear()
            self.search.blockSignals(False)
            self.heading.setText(playlist.title)
            self.description.hide()
            self.download_all_button.setText("Descargar playlist")
            self.download_all_button.setVisible(bool(tracks))
            self.render_tracks(self.remote_tracks)
            self.notify(f"{len(tracks)} canciones importadas desde Spotify.")

        def error(message):
            self.import_loading.hide()
            if generation != self.search_generation:
                return
            self.message_text.setText(str(message))
            self.message_details.hide()
            self.retry_action = lambda: self.import_spotify_playlist(url)
            self.message_bar.show()
            self.notify(message)

        self.search_job = self.jobs.submit(lambda cancel, progress: self.catalog.spotify_playlist(url, progress), ready, error,
            lambda value: self.notify(value))

    def import_spotify_track(self, url):
        self.show_route("Buscar")
        self.search_generation += 1
        generation = self.search_generation
        if self.search_job:
            self.search_job.cancel.set()
        self.import_loading.show()
        self.notify("Leyendo canción de Spotify…")

        def ready(track):
            self.import_loading.hide()
            if generation != self.search_generation or self.route != "Buscar":
                return
            self.db.save_track(track, restore=True)
            self.remote_tracks = [track]
            self.remote_albums = []
            self.search.blockSignals(True)
            self.search.setText(track.title)
            self.search.blockSignals(False)
            self.heading.setText("Canción de Spotify")
            self.description.hide()
            self.download_all_button.setText("Descargar canción")
            self.download_all_button.show()
            self.render_tracks([track])
            self.notify(f"Canción importada: {track.title}")

        def error(message):
            self.import_loading.hide()
            if generation != self.search_generation:
                return
            self.message_text.setText(str(message))
            self.message_details.hide()
            self.retry_action = lambda: self.import_spotify_track(url)
            self.message_bar.show()
            self.notify(message)

        self.search_job = self.jobs.submit(
            lambda cancel, progress: self.catalog.spotify_track(url), ready, error,
        )

    def choose_playlist_tracks(self, playlist, provider):
        """Fast searchable review used by Spotify/SongMirror imports."""
        dialog = TrackPickerDialog(
            f"Revisar playlist de {provider}", playlist.tracks, self,
            action_text="Importar seleccionadas",
            subtitle="Busca por nombre o artista y desmarca lo que no quieras guardar.",
            default_checked=True,
        )
        if dialog.exec() != QDialog.DialogCode.Accepted:
            return None
        return dialog.selected_tracks()

    def play_row(self, row):
        if 0 <= row < len(self.shown_tracks):
            self.player.play_queue(self.shown_tracks, row)

    def play_all(self):
        if self.shown_tracks:
            self.player.play_queue(self.shown_tracks)

    def selected_tracks(self):
        return [self.shown_tracks[index.row()] for index in self.table.selectionModel().selectedRows()]

    def track_menu(self, position):
        row = self.table.rowAt(position.y())
        if row < 0:
            return
        if not self.table.selectionModel().isRowSelected(row):
            self.table.selectRow(row)
        track = self.shown_tracks[row]
        selected = self.selected_tracks()
        menu = QMenu(self)
        menu.addAction("Reproducir", lambda: self.play_row(row))
        menu.addAction("Añadir a la cola", lambda: [self.player.append(t) for t in selected])
        menu.addAction("Quitar de favoritos" if track.id in self.db.favorites() else "Añadir a favoritos",
                       lambda: self.favorite(track))
        menu.addAction("Añadir a colección…", lambda: self.add_to_collection(selected))
        resolved_selected = [self.db.resolve_track(t) for t in selected]
        missing = [t for t in resolved_selected if not (t.path and Path(t.path).is_file())]
        if missing:
            menu.addAction("Descargar", lambda items=missing: [self.start_download(t) for t in items])
        resolved_track = self.db.resolve_track(track)
        if resolved_track.path and Path(resolved_track.path).is_file():
            menu.addAction("Mostrar carpeta", lambda p=resolved_track.path: QDesktopServices.openUrl(QUrl.fromLocalFile(str(Path(p).parent))))
        if self.collection:
            menu.addAction("Quitar de esta colección", lambda: self.remove_members(selected))
            menu.addAction("Subir en colección", lambda: self.move_member(row, -1))
            menu.addAction("Bajar en colección", lambda: self.move_member(row, 1))
        menu.addSeparator()
        menu.addAction("Quitar de la biblioteca", lambda: self.remove_tracks(selected))
        menu.exec(self.table.viewport().mapToGlobal(position))

    def favorite(self, track):
        self.db.toggle_favorite(track)
        self.refresh_tracks()
        if self.player_dialog:
            self.full_favorite.setIcon(icon("heart","#C96A5E" if track.id in self.db.favorites() else "#F1EAD9"))
        self.mini_favorite.setIcon(icon("heart","#C96A5E" if self.player.current and self.player.current.id in self.db.favorites() else "#F1EAD9"))

    def remove_tracks(self, tracks):
        for track in tracks:
            self.db.remove_track(track.id)
            self.cancel_download(track.id)
            for index in reversed(range(len(self.player.queue))):
                if self.player.queue[index].id == track.id:
                    self.player.remove(index)
        self.refresh_tracks()
        self.refresh_sidebar()
        self.notify("Se quitaron de la biblioteca. Los archivos originales se conservaron.")

    def create_collection(self):
        kind, ok = QInputDialog.getItem(self, "Nueva colección", "Tipo:", ["Playlist", "Álbum personalizado"], 0, False)
        if not ok:
            return
        available = self.db.library_tracks()
        dialog = TrackPickerDialog(
            "Crear playlist" if kind == "Playlist" else "Crear álbum personalizado",
            available, self,
            action_text="Crear",
            subtitle="Ponle un nombre y elige canciones. Puedes buscar por título o artista.",
            default_checked=False,
            ask_name=True,
        )
        if dialog.exec() != QDialog.DialogCode.Accepted:
            return
        title = dialog.playlist_name()
        tracks = dialog.selected_tracks()
        cover = next((track.cover for track in tracks if track.cover), "")
        key = self.db.create_collection(title, "playlist" if kind == "Playlist" else "album", cover=cover)
        if tracks:
            self.db.replace_collection(key, title, tracks, cover=cover)
        self.refresh_sidebar()
        self.refresh_collections()
        self.notify(f"{title}: {len(tracks)} canciones.")

    def refresh_collections(self):
        self.refresh_sidebar()
        self.collections_list.clear()
        collections = self.db.collections()
        explicit_album_titles = {
            collection["title"].strip().casefold()
            for collection in collections if collection["kind"] == "album"
        }
        for collection in collections:
            name = ("PLAYLIST" if collection["kind"] == "playlist" else
                    "ÁLBUM" if collection.get("source") else "ÁLBUM PERSONALIZADO")
            item = QListWidgetItem(f"{collection['title']}\n{name}  ·  {collection['count']} canciones")
            item.setData(Qt.ItemDataRole.UserRole, collection)
            item.setIcon(icon("album","#E0A458",120))
            self.collections_list.addItem(item)
            if collection.get("cover"):
                self.load_cover(collection["cover"], lambda path, item=item: item.setIcon(QIcon(path)) if path else None)
        albums = {}
        for track in self.db.tracks():
            if track.path and track.album != "Sin álbum":
                albums.setdefault((track.artist, track.album), []).append(track)
        for (artist, title), tracks in sorted(albums.items()):
            if title.strip().casefold() in explicit_album_titles:
                continue
            item = QListWidgetItem(f"{title}  —  {artist}\nÁLBUM LOCAL  ·  {len(tracks)} canciones")
            item.setData(Qt.ItemDataRole.UserRole, {"local": [t.to_dict() for t in tracks], "title": title})
            item.setIcon(icon("album","#B3A992",120))
            self.collections_list.addItem(item)
            if tracks[0].cover:
                self.load_cover(tracks[0].cover,lambda path,item=item:item.setIcon(QIcon(path)) if path else None)
        self.count.setText(f"{self.collections_list.count()} colecciones")

    def open_collection_item(self, item):
        data = item.data(Qt.ItemDataRole.UserRole)
        self.heading.setText(data["title"])
        self.heading.show()
        self.home_shelf.hide()
        self.home_greeting.hide()
        self.stack.setCurrentWidget(self.track_page)
        self.album_list.hide()
        self.play_all_button.show()
        self.new_collection_button.hide()
        self.rescan_button.hide()
        self.collection = data.get("id")
        self.local_album = [t["id"] for t in data["local"]] if "local" in data else None
        self.collection_menu_button.setVisible(bool(self.collection))
        self.collection_delete_button.setVisible(bool(self.collection))
        collection_kind = data.get("kind") if self.collection else ""
        self.collection_delete_button.setToolTip("Eliminar playlist" if collection_kind == "playlist" else "Eliminar colección")
        self.download_all_button.setText("Descargar álbum" if collection_kind == "album" else "Descargar playlist")
        self.download_all_button.setVisible(bool(collection_kind))
        self.save_remote_playlist_button.hide()
        self.description.hide()
        self.render_tracks([Track.from_dict(t) for t in data["local"]] if "local" in data else self.db.tracks(collection=self.collection))
        self.main_scroll.verticalScrollBar().setValue(0)
        self.schedule_visible_stream_warm(initial=True)

    def collection_list_menu(self, position):
        item = self.collections_list.itemAt(position)
        if item and item.data(Qt.ItemDataRole.UserRole).get("id"):
            self.open_collection_item(item)
            self.collection_options()

    def collection_options(self):
        if not self.collection:
            return
        name, ok = QInputDialog.getText(self, "Cambiar nombre", "Nombre:", text=self.heading.text())
        if ok and name.strip():
            self.db.rename_collection(self.collection, name)
            self.heading.setText(name)
            self.refresh_sidebar()
            self.refresh_collections()

    def delete_current_collection(self):
        if not self.collection:
            return
        if QMessageBox.question(
            self, "Eliminar playlist", "¿Eliminar esta playlist? Las canciones descargadas se conservarán."
        ) != QMessageBox.StandardButton.Yes:
            return
        self.db.delete_collection(self.collection)
        self.collection = None
        self.refresh_sidebar()
        self.show_route("Álbumes")

    def add_to_collection(self, tracks):
        collections = self.db.collections()
        if not collections:
            self.create_collection()
            collections = self.db.collections()
        if not collections:
            return
        names = [f"{i + 1}. {c['title']}" for i, c in enumerate(collections)]
        selected, ok = QInputDialog.getItem(self, "Añadir canciones", "Colección:", names, 0, False)
        if ok:
            key = collections[names.index(selected)]["id"]
            for track in tracks:
                self.db.add_member(key, track)
            self.refresh_sidebar()
            self.notify(f"{len(tracks)} canciones añadidas a la colección.")

    def remove_members(self, tracks):
        for track in tracks:
            self.db.remove_member(self.collection, track.id)
        self.refresh_tracks()
        self.refresh_sidebar()

    def move_member(self, row, delta):
        tracks = self.db.tracks(collection=self.collection)
        ids = [t.id for t in tracks]
        source = ids.index(self.shown_tracks[row].id)
        target = source + delta
        if 0 <= target < len(ids):
            ids.insert(target, ids.pop(source))
            self.db.reorder_members(self.collection, ids)
            self.refresh_tracks()

    def open_playlist_item(self, item):
        playlist = item.data(Qt.ItemDataRole.UserRole)
        if self.playlist_job:
            self.playlist_job.cancel.set()
        self.search_generation += 1
        generation = self.search_generation
        self.notify("Cargando playlist de YouTube…")

        def ready(tracks):
            if generation != self.search_generation:
                return
            self.remote_playlist, self.remote_album, self.collection = playlist, None, None
            self.remote_tracks = list(tracks)
            self.search_mode = "tracks"
            self.search_tabs.hide()
            self.album_section_title.hide()
            self.playlist_section_title.hide()
            self.heading.setText(playlist.get("title", "Playlist"))
            self.description.setText(playlist.get("artist", "YouTube"))
            self.description.setVisible(bool(playlist.get("artist")))
            self.album_list.hide()
            self.playlist_list.hide()
            self.download_all_button.setText("Descargar playlist")
            self.download_all_button.setVisible(bool(tracks))
            self.save_remote_playlist_button.setVisible(bool(tracks))
            self.render_tracks(self.remote_tracks)
            self.main_scroll.verticalScrollBar().setValue(0)
            self.schedule_visible_stream_warm(initial=True)
            self.notify(f"{len(tracks)} elementos cargados. Reproducir usa streaming; descargar es opcional.")

        self.playlist_job = self.jobs.submit(
            lambda cancel, progress: self.catalog.playlist_tracks(playlist, cancel), ready, self.notify,
        )

    def save_remote_playlist(self, _checked=False, silent=False):
        if not self.remote_playlist or not self.remote_tracks:
            return None
        playlist = self.remote_playlist
        existing = self.db.collection_by_source("youtube", playlist.get("id", ""), kind="playlist")
        key = existing["id"] if existing else self.db.create_collection(
            playlist.get("title", "Playlist de YouTube"), "playlist", "youtube", playlist.get("id", ""),
            playlist.get("url", ""), playlist.get("cover", ""),
        )
        self.db.replace_collection(
            key, playlist.get("title", "Playlist de YouTube"), self.remote_tracks,
            "youtube", playlist.get("id", ""), playlist.get("url", ""), playlist.get("cover", ""),
        )
        self.collection = key
        self.refresh_sidebar()
        if not silent:
            self.notify(f"Playlist agregada a MusicVault: {len(self.remote_tracks)} canciones, sin descargarlas.")
        return key

    def open_album_item(self, item):
        album = item.data(Qt.ItemDataRole.UserRole)
        if self.album_job:
            self.album_job.cancel.set()
        self.search_generation += 1
        generation = self.search_generation
        self.notify("Cargando álbum…")

        def ready(tracks):
            if generation != self.search_generation:
                return
            self.remote_album, self.remote_playlist, self.collection = album, None, None
            self.remote_tracks = tracks
            self.search_mode = "tracks"
            self.search_tabs.hide()
            self.album_section_title.hide()
            self.heading.setText(album["title"])
            self.description.setText(album["artist"])
            self.description.setVisible(bool(album.get("artist")))
            self.album_list.hide()
            self.download_all_button.setText("Descargar álbum")
            self.download_all_button.show()
            self.save_remote_playlist_button.hide()
            self.render_tracks(tracks)
            self.main_scroll.verticalScrollBar().setValue(0)
            self.schedule_visible_stream_warm(initial=True)

        self.album_job = self.jobs.submit(lambda cancel, progress: self.catalog.album_tracks(album), ready, self.notify)

    def download_all(self):
        if self.remote_playlist:
            self.save_remote_playlist(silent=True)
        if self.remote_album:
            album = self.remote_album
            existing = self.db.collection_by_source(album["source"], album["id"], kind="album")
            key = existing["id"] if existing else self.db.create_collection(
                album["title"], "album", album["source"], album["id"], cover=album.get("cover", "")
            )
            self.db.replace_collection(
                key, album["title"], self.shown_tracks, album["source"], album["id"],
                cover=album.get("cover", ""),
            )
            self.refresh_sidebar()
        pending = []
        for track in self.shown_tracks:
            resolved = self.db.resolve_track(track)
            if not resolved.path or not Path(resolved.path).is_file():
                pending.append(track)
        pending = self.db.queue_downloads(pending)
        for track in pending:
            self.start_download(track, prepared=True, defer_refresh=True, silent=True)
        self.schedule_download_refresh()
        if pending:
            kind = "álbum" if self.remote_album else "playlist"
            self.notify(f"{len(pending)} canciones del {kind} añadidas a Descargas.")
        else:
            self.notify("Todas las canciones ya están disponibles en tu biblioteca.")

    def spotify_account_dialog(self):
        try:
            from .spotify_login import SpotifyLoginDialog
        except ImportError as exc:
            QMessageBox.warning(
                self,
                "Navegador integrado no disponible",
                "Falta Qt WebEngine. Vuelve a extraer el paquete portable completo y abre MusicVault.\n\n"
                f"Detalle: {exc}",
            )
            return
        dialog = SpotifyLoginDialog(self, connected=session_configured())

        def captured(cookie):
            def connected(_result):
                try:
                    save_session(cookie)
                except (OSError, ValueError) as exc:
                    dialog.mark_rejected(f"No se pudo guardar la sesión: {exc}")
                    return
                dialog.mark_connected()
                self.notify("Spotify quedó conectado.")
                self.check_spotify_clipboard(force=True)

            def rejected(message):
                dialog.mark_rejected(message)
                self.notify(message)

            self.jobs.submit(
                lambda cancel, progress: validate_session(cookie=cookie),
                connected,
                rejected,
            )

        def remove_account():
            clear_session()
            self._clipboard_spotify = None
            self.hide_spotify_clipboard_prompt()
            self.notify("Se desconectó Spotify.")

        dialog.session_captured.connect(captured)
        dialog.disconnect_requested.connect(remove_account)
        dialog.exec()

    def import_paths(self, paths, play=False, explicit=True):
        self.notify("Leyendo archivos de audio…")

        def ready(result):
            tracks, errors = result
            self.refresh_tracks()
            if self.route == "Álbumes":
                self.refresh_collections()
            self.notify(f"{len(tracks)} canciones importadas." + (f" {len(errors)} archivos no se pudieron leer: {errors[0]}" if errors else ""))
            if play and tracks:
                self.player.play_queue(tracks)

        return self.jobs.submit(lambda cancel, progress: scan(paths, self.db, cancel,
            lambda count, name: progress(f"Importando {count}: {name}"),
            minimum=0 if explicit else self.db.setting("minimum_duration", 0), explicit=explicit),
            ready, self.notify, self.notify)

    def rescan(self):
        roots = self.db.setting("library_roots", [])
        if not roots:
            return
        if hasattr(self, "scan_job") and self.scan_job in self.jobs.active:
            return
        self.scan_job = self.import_paths(roots, explicit=False)

    def dragEnterEvent(self, event):
        if event.mimeData().hasUrls() and all(url.isLocalFile() for url in event.mimeData().urls()):
            event.acceptProposedAction()

    def dropEvent(self, event):
        self.import_paths([url.toLocalFile() for url in event.mimeData().urls() if url.isLocalFile()])
        event.acceptProposedAction()

    def cancel_stream_warm(self, keep_track_id=None):
        self.stream_warm_generation += 1
        if self.stream_warm_job and self.stream_warm_track_id != keep_track_id:
            self.stream_warm_job.cancel.set()
            self.stream_warm_job = None
            self.stream_warm_track_id = None

    def schedule_visible_stream_warm(self, *_args, initial=False):
        if self.route not in ("Inicio", "Buscar") and not (self.remote_album or self.remote_playlist or self.collection):
            return
        if not self.shown_tracks:
            return
        self.screen_warm_timer.setInterval(650 if initial else 420)
        self.screen_warm_timer.start()

    def warm_visible_streams(self):
        if not self.shown_tracks or self._quitting:
            return
        rows = list(self.table.visible_rows())
        if not rows:
            rows = list(range(min(4, len(self.shown_tracks))))
        candidates = []
        for row in rows:
            if not 0 <= row < len(self.shown_tracks):
                continue
            track = self.db.resolve_track(self.shown_tracks[row])
            if track.source == "local" or (track.path and Path(track.path).is_file()) or self.streaming.cached_media(track, refresh=False):
                continue
            if track.id not in {item.id for item in candidates}:
                candidates.append(track)
            if len(candidates) >= 4:
                break
        if not candidates:
            return
        self.cancel_stream_warm()
        generation = self.stream_warm_generation
        self.stream_warm_track_id = candidates[0].id

        def work(cancel, report):
            ready = []
            # Phase 1: fully resolve the most likely row.
            first = candidates[0]
            report(("warming", first.id))
            try:
                url = self.streaming.resolve(first, cancel)
                ready.append((first, url))
                report(("ready", first, url))
            except StreamCancelled:
                return ready
            except Exception:
                pass
            # Phase 2: cheap/stable match for rows 2-4.
            for track in candidates[1:4]:
                if cancel.is_set():
                    return ready
                report(("warming", track.id))
                try:
                    self.streaming.match(track, cancel)
                except StreamCancelled:
                    return ready
                except Exception:
                    continue
            # Phase 3: fully resolve rows 2-3 using the cached match.
            for track in candidates[1:3]:
                if cancel.is_set():
                    return ready
                report(("warming", track.id))
                try:
                    url = self.streaming.resolve(track, cancel)
                    ready.append((track, url))
                    report(("ready", track, url))
                except StreamCancelled:
                    return ready
                except Exception:
                    continue
            return ready

        def progress(payload):
            if generation != self.stream_warm_generation:
                return
            if not isinstance(payload, tuple) or not payload:
                return
            if payload[0] == "warming":
                self.stream_warm_track_id = payload[1]
            elif payload[0] == "ready":
                track, url = payload[1], payload[2]
                self.player.prepare_stream(track, url)

        def finished():
            if generation == self.stream_warm_generation:
                self.stream_warm_job = None
                self.stream_warm_track_id = None

        self.stream_warm_job = self.warm_jobs.submit(work, progress=progress, finished=finished)

    def prewarm_stream(self, track):
        resolved = self.db.resolve_track(track)
        if resolved.source == "local" or (resolved.path and Path(resolved.path).is_file()):
            return
        if self.streaming.cached_media(resolved, refresh=False) or self.player.prepared_stream(resolved):
            return
        self.cancel_stream_warm(keep_track_id=resolved.id)
        if self.stream_warm_job and self.stream_warm_track_id == resolved.id:
            return
        self.stream_warm_track_id = resolved.id
        generation = self.stream_warm_generation

        def work(cancel, report):
            return self.streaming.resolve(resolved, cancel)

        def ready(url):
            if generation == self.stream_warm_generation:
                self.player.prepare_stream(resolved, url)

        def finished():
            if generation == self.stream_warm_generation:
                self.stream_warm_job = None
                self.stream_warm_track_id = None

        self.stream_warm_job = self.warm_jobs.submit(work, ready, lambda _error: None, finished=finished)

    def stream_cached(self, track, path):
        # Streaming cache is an implementation detail: keep it invisible in the UI.
        # A later explicit Download action can promote this cached media without
        # fetching the same bytes again.
        self.streaming.mark_cached(track, path)

    def ensure_playable(self, track):
        expected = self.player.index
        request = self.player.request_generation
        cached = self.streaming.cached_media(track)
        if cached:
            self.player.play_resolved(track, cached, expected_index=expected, request=request)
            return

        # If this exact track is being warmed, keep that work alive. Unrelated
        # speculative work is canceled so foreground playback gets priority.
        self.cancel_stream_warm(keep_track_id=track.id)
        if self.stream_prepare_job:
            self.stream_prepare_job.cancel.set()
        self.stream_prepare_track_id = track.id

        def work(cancel, progress):
            url = self.streaming.resolve(track, cancel)
            return url

        def ready(url):
            self.stream_prepare_job = None
            self.stream_prepare_track_id = None
            if not (self.player.current and self.player.current.id == track.id
                    and self.player.index == expected and self.player.request_generation == request):
                return
            self.player.prepare_stream(track, url)
            record_path = self.streaming.record_path(track)
            self.player.play_resolved(track, url, record_path=record_path,
                                      expected_index=expected, request=request)
            self.schedule_visible_stream_warm(initial=True)

        def failed(message):
            self.stream_prepare_job = None
            self.stream_prepare_track_id = None
            if self.player.current and self.player.current.id == track.id:
                self.player.preparation_failed(track)
                self.notify(f"No se pudo preparar «{track.title}» para streaming. {message}")

        self.stream_prepare_job = self.stream_jobs.submit(work, ready, failed)

    def start_download(self, track, callback=None, prepared=False, defer_refresh=False, silent=False):
        if not prepared:
            stored = self.db.resolve_track(track)
            if stored and stored.path and Path(stored.path).is_file():
                if callback:
                    callback(stored)
                return
        if callback:
            self.download_callbacks.setdefault(track.id, []).append(callback)
        if track.id in self.download_jobs:
            return
        destination = self.db.setting("download_folder", str(data_dir() / "downloads"))
        if not prepared:
            self.db.save_track(track, restore=True)
            self.db.set_download(track, "queued")
        if getattr(self, "error_track_id", None) == track.id:
            self.message_bar.hide()
        completed = []
        persisted_progress = {"value": -1, "time": 0.0}

        def progress(value):
            if completed or (track.id in self.download_jobs and self.download_jobs[track.id].cancel.is_set()):
                return
            if self.download_progress_values.get(track.id) == value:
                return
            self.download_progress_values[track.id] = value
            now = time.monotonic()
            if (persisted_progress["value"] < 0 or value >= persisted_progress["value"] + 5
                    or now - persisted_progress["time"] >= 1.0):
                self.db.set_download(track, "downloading", value)
                persisted_progress.update(value=value, time=now)
            self.track_model.set_progress(track.id, value)
            if self.route == "Descargas":
                self.schedule_download_refresh()

        def work(cancel, report):
            try:
                try:
                    result = self.streaming.promote_cached(track, destination, cancel, report)
                except StreamCancelled:
                    raise DownloadCancelled()
                if result is None:
                    result = download(track, destination, cancel, report)
                # Publish the durable record before signaling the UI, including during shutdown.
                self.db.save_track(result)
                self.db.set_download(result, "complete", 100)
                completed.append(result)
                return result
            except DownloadCancelled:
                cancel.set()
                return None

        def ready(result):
            self.player.downloaded(result)
            callbacks = self.download_callbacks.pop(track.id, [])
            for consumer in callbacks:
                consumer(result)
            self.notify(f"Descarga completa: {result.title}")

        def error(message):
            self.db.set_download(track, "error", error=message)
            pending = self.download_callbacks.pop(track.id, [])
            self.notify("No se completó la descarga. Consulta el aviso o abre Descargas.")
            self.show_download_error(track, lambda result:[consumer(result) for consumer in pending] if pending else None)

        def finished():
            self.download_progress_values.pop(track.id, None)
            job = self.download_jobs.pop(track.id, None)
            if job and job.cancel.is_set() and not completed:
                self.db.set_download(track, "cancelled")
                self.download_callbacks.pop(track.id, None)
            self.schedule_download_refresh()

        self.download_jobs[track.id] = self.jobs.submit(work, ready, error, progress, finished)
        if not defer_refresh:
            self.schedule_download_refresh()
        if not silent:
            self.notify(f"Descarga en cola: {track.title}")

    def cancel_download(self, track_id):
        if track_id in self.download_jobs:
            self.download_jobs[track_id].cancel.set()
            self.download_callbacks.pop(track_id, None)

    def show_download_error(self, track, callback=None):
        self.error_track_id = track.id
        record = self.db.download_record(track.id) or {}
        self.download_error_details = record.get("error", "")
        explanation = explain_download_error(self.download_error_details) or "La descarga anterior no se completó. Pulsa Reintentar."
        self.message_text.setText(f"No se descargó «{track.title}».\n{explanation}")
        self.message_details.setVisible(bool(self.download_error_details))
        self.message_bar.show()
        self.retry_action = lambda: self.start_download(track, callback)

    def show_download_details(self):
        dialog = QMessageBox(self)
        dialog.setWindowTitle("Detalles de la descarga")
        dialog.setIcon(QMessageBox.Icon.Warning)
        dialog.setTextFormat(Qt.TextFormat.PlainText)
        dialog.setText(explain_download_error(self.download_error_details))
        dialog.setDetailedText(self.download_error_details)
        dialog.exec()

    def refresh_downloads(self):
        from .downloader import explain_download_error
        if self.route != "Descargas":
            return
        records = self.db.downloads()
        wanted = {r["track_id"] for r in records}
        for key in self.download_items.keys() - wanted:
            item = self.download_items.pop(key)
            self.downloads_list.takeItem(self.downloads_list.row(item))
        names = {"queued": "En cola", "downloading": "Descargando", "complete": "Completa",
                 "error": "Error", "cancelled": "Cancelada", "interrupted": "Interrumpida"}
        self.downloads_list.setUpdatesEnabled(False)
        for record in records:
            key = record["track_id"]
            item = self.download_items.get(key)
            if item is not None and item.data(Qt.ItemDataRole.UserRole) == record:
                continue
            track = Track.from_dict(json.loads(record["payload"]))
            error = explain_download_error(record["error"])
            if item is None:
                item = QListWidgetItem()
                item.setIcon(icon("download", "#E0A458", 24))
                self.downloads_list.addItem(item)
                self.download_items[key] = item
            item.setText(f"{track.title}  —  {track.artist}\n{names.get(record['status'], record['status'])}  {record['progress']}%" +
                         (f"  ·  {error[:220]}" if error else ""))
            item.setData(Qt.ItemDataRole.UserRole, record)
            item.setToolTip(error)
        self.downloads_list.setUpdatesEnabled(True)
        self.count.setText(f"{len(self.download_jobs)} activas")

    def retry_selected_download(self):
        item=self.downloads_list.currentItem()
        if item:
            record=item.data(Qt.ItemDataRole.UserRole)
            if record['status'] in ('error','cancelled','interrupted'):
                self.start_download(Track.from_dict(json.loads(record['payload'])))

    def cancel_selected_download(self):
        item=self.downloads_list.currentItem()
        if item:self.cancel_download(item.data(Qt.ItemDataRole.UserRole)['track_id'])

    def download_menu(self, position):
        item = self.downloads_list.itemAt(position)
        if not item:
            return
        record = item.data(Qt.ItemDataRole.UserRole)
        track = Track.from_dict(json.loads(record["payload"]))
        menu = QMenu(self)
        if track.id in self.download_jobs:
            menu.addAction("Cancelar", lambda: self.cancel_download(track.id))
        else:
            menu.addAction("Reintentar", lambda: self.start_download(track))
        menu.exec(self.downloads_list.mapToGlobal(position))

    def cycle_repeat(self):
        modes = ["off", "all", "one"]
        self.player.set_repeat(modes[(modes.index(self.player.repeat) + 1) % 3])

    def animate_play_spinner(self):
        self._spinner_angle = (self._spinner_angle + 30) % 360
        base = icon("refresh", "#191207", 22).pixmap(22, 22)
        spun = base.transformed(QTransform().rotate(self._spinner_angle), Qt.TransformationMode.SmoothTransformation)
        self.play_button.setIcon(QIcon(spun))
        if self.player_dialog:
            full = icon("refresh", "#191207", 30).pixmap(30, 30)
            self.full_play.setIcon(QIcon(full.transformed(QTransform().rotate(self._spinner_angle), Qt.TransformationMode.SmoothTransformation)))

    def playback_changed(self):
        if self.player.preparing:
            if not self.play_spinner_timer.isActive():
                self.play_spinner_timer.start()
            self.animate_play_spinner()
            self.play_button.setToolTip("Preparando streaming…")
            self.play_button.setEnabled(False)
        else:
            self.play_spinner_timer.stop()
            self.play_button.setEnabled(True)
            self.play_button.setToolTip("Reproducir o pausar")
            self.play_button.setIcon(icon("pause" if self.player.playing else "play","#191207"))
        self.shuffle_button.setChecked(self.player.shuffle)
        self.repeat_button.setToolTip("Repetir: " + {"off": "no", "all": "todo", "one": "una"}[self.player.repeat])
        self.shuffle_button.setIcon(icon("shuffle","#E0A458" if self.player.shuffle else "#7E7666"))
        repeat_color={"off":"#7E7666","all":"#7DC4E8","one":"#E0A458"}[self.player.repeat]
        self.repeat_button.setIcon(icon("repeat",repeat_color))
        self.volume.blockSignals(True)
        self.volume.setValue(round(self.player.volume))
        self.volume.blockSignals(False)
        if self.player_dialog:
            if not self.player.preparing:
                self.full_play.setIcon(icon("pause" if self.player.playing else "play","#191207",34))
            self.full_play.setEnabled(not self.player.preparing)
            self.full_shuffle.setIcon(self.shuffle_button.icon())
            self.full_repeat.setIcon(self.repeat_button.icon())

    def playback_progress(self, position, duration):
        self.elapsed.setText(clock_text(position))
        self.total.setText(clock_text(duration))
        if not self.seek.isSliderDown():
            self.seek.setValue(round(position / duration * 1000) if duration else 0)
        if self.player_dialog and self.player_dialog.isVisible():
            if not self.full_seek.isSliderDown():
                self.full_seek.setValue(round(position / duration * 1000) if duration else 0)
            self.full_time.setText(f"{clock_text(position)} / {clock_text(duration)}")
            if self.lyric_lines:
                import bisect
                index = bisect.bisect_right(self.lyric_times, position) - 1
                if index >= 0 and index != self.lyrics.currentRow():
                    self.lyrics.setCurrentRow(index)
                    self.lyrics.scrollToItem(self.lyrics.item(index), QAbstractItemView.ScrollHint.PositionAtCenter)

    def load_cover(self, source, callback):
        if self._quitting:
            return
        def deliver(path):
            if not self._quitting:
                try:
                    callback(path)
                except RuntimeError:
                    pass  # Qt receiver was removed by a newer search or route.

        if not source or not source.startswith(("https://", "http://", "//")):
            deliver(source)
            return
        if source in self.cover_cache:
            self.cover_cache.move_to_end(source)
            deliver(self.cover_cache[source])
            return
        since_failure = time.monotonic() - self.cover_failures.get(source, -60)
        if since_failure < 1 or (since_failure < 30 and self.cover_attempts.get(source, 0) >= 3):
            deliver("")
            return
        if since_failure >= 30:
            self.cover_attempts.pop(source, None)
        if source in self.cover_pending:
            self.cover_pending[source].append(deliver)
            return
        self.cover_pending[source] = [deliver]

        def ready(path):
            if path:
                self.cover_failures.pop(source, None)
                self.cover_attempts.pop(source, None)
                self.cover_cache[source] = path
                while len(self.cover_cache) > 256:
                    self.cover_cache.popitem(last=False)
            else:
                self.cover_failures[source] = time.monotonic()
                self.cover_attempts[source] = self.cover_attempts.get(source, 0) + 1
                if len(self.cover_failures) > 256:
                    expired = next(iter(self.cover_failures))
                    self.cover_failures.pop(expired)
                    self.cover_attempts.pop(expired, None)
                if self.cover_attempts.get(source, 0) < 3:
                    QTimer.singleShot(1500, self.load_visible_covers)
            for consumer in self.cover_pending.pop(source, []):
                consumer(path)

        self.cover_jobs.submit(lambda cancel, progress: cover_file(source), ready, lambda error: ready(""))

    def track_changed(self, track):
        self.current_title.setText(track.title if track else "Nada en reproducción")
        self.current_artist.setText(track.artist if track else "Selecciona una canción")
        self.current_title.setToolTip(track.title if track else "")
        self.setWindowTitle(f"{track.title} — MusicVault" if track else "MusicVault")
        self.mini_cover.load("")
        self.mini_favorite.setIcon(icon("heart","#C96A5E" if track and track.id in self.db.favorites() else "#F1EAD9"))
        self.track_model.set_current(track.id if track else None)
        if track:
            self.load_cover(track.cover, lambda path: self.apply_current_cover(track.id, path))
        if self.player_dialog:
            self.update_full_player()
        self.refresh_queue()
        # Warm one successor without turning playback into a permanent download.
        if self.db.setting("prefetch", True) and self.player.playing:
            index = self.player.next_index(automatic=True)
            if index is not None:
                successor = self.player.queue[index]
                resolved = self.db.resolve_track(successor)
                if resolved.source != "local" and not (resolved.path and Path(resolved.path).is_file()):
                    self.prewarm_stream(resolved)

    def apply_current_cover(self, track_id, path):
        if self.player.current and self.player.current.id == track_id:
            self.mini_cover.load(path)
            if self.player_dialog:
                self.record.cover = QPixmap(path)
                self.record.update()

    def open_queue(self):
        if self.queue_dialog:
            self.queue_dialog.show()
            self.queue_dialog.raise_()
            return
        dialog = QDialog(self)
        dialog.setWindowTitle("Cola de reproducción")
        dialog.resize(600, 500)
        layout = QVBoxLayout(dialog)
        layout.addWidget(label("A continuación", "heading"))
        self.queue_list = QListWidget()
        self.queue_list.itemDoubleClicked.connect(lambda item: self.player.play_index(self.queue_list.row(item)))
        layout.addWidget(self.queue_list, 1)
        row = QHBoxLayout()
        row.addWidget(button("↑ Subir", lambda: self.move_queue(-1)))
        row.addWidget(button("↓ Bajar", lambda: self.move_queue(1)))
        row.addWidget(button("Quitar", lambda: self.player.remove(self.queue_list.currentRow())))
        row.addWidget(button("Cerrar", dialog.close))
        layout.addLayout(row)
        self.queue_dialog = dialog
        self.refresh_queue()
        dialog.show()

    def move_queue(self, delta):
        source = self.queue_list.currentRow()
        target = source + delta
        if 0 <= target < len(self.player.queue):
            self.player.move(source, target)
            self.queue_list.setCurrentRow(target)

    def refresh_queue(self):
        if not self.queue_dialog:
            return
        selection = self.queue_list.currentRow()
        self.queue_list.clear()
        for index, track in enumerate(self.player.queue):
            self.queue_list.addItem(f"{'▶  ' if index == self.player.index else ''}{track.title}  —  {track.artist}")
        self.queue_list.setCurrentRow(selection if 0 <= selection < len(self.player.queue) else self.player.index)

    def open_player(self):
        if self.player_dialog:
            self.player_dialog.show()
            self.player_dialog.raise_()
            return
        from .layout import build_full_player
        build_full_player(self)

    def update_full_player(self):
        track = self.player.current
        self.full_title.setText(track.title if track else "Tu próxima canción")
        self.full_artist.setText(f"{track.artist}  ·  {track.album}" if track else "Selecciona música de tu biblioteca")
        self.full_favorite.setIcon(icon("heart","#C96A5E" if track and track.id in self.db.favorites() else "#F1EAD9"))
        from .models import stable_id
        self.catalog_number.setText("VAULT-"+str(int(stable_id(track.id)[:8],16)%10000).zfill(4) if track else "VAULT —")
        self.record.cover = QPixmap()
        self.record.update()
        self.lyrics.clear()
        self.lyric_lines = []
        self.lyric_generation += 1
        generation = self.lyric_generation
        if not track:
            return
        self.load_cover(track.cover, lambda path: self.apply_current_cover(track.id, path))
        self.lyrics.addItem("Buscando letras…")

        def ready(result):
            if generation != self.lyric_generation:
                return
            self.lyrics.clear()
            self.lyric_lines = result["synced"]
            self.lyric_times = [line[0] for line in self.lyric_lines]
            for text in ([line[1] for line in self.lyric_lines] if self.lyric_lines else result["plain"].splitlines()):
                self.lyrics.addItem(text)

        def failed(error):
            if generation == self.lyric_generation:
                self.lyrics.clear()
                self.lyrics.addItem("No se pudieron cargar las letras. " + error)

        self.jobs.submit(lambda cancel, progress: fetch_lyrics(track, self.db), ready, failed)

    def settings(self):
        dialog = QDialog(self)
        dialog.setObjectName("settingsDialog")
        dialog.setWindowTitle("Ajustes de MusicVault")
        dialog.resize(760, 700)
        dialog.setMinimumSize(640, 560)

        root = QVBoxLayout(dialog)
        root.setContentsMargins(22, 18, 22, 18)
        root.setSpacing(14)

        header = QVBoxLayout()
        header.setSpacing(0)
        title = label("Ajustes", "settingsTitle")
        header.addWidget(title)
        root.addLayout(header)

        scroll = QScrollArea()
        scroll.setObjectName("settingsScroll")
        scroll.setWidgetResizable(True)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        page = QWidget()
        page_layout = QVBoxLayout(page)
        page_layout.setContentsMargins(0, 4, 8, 6)
        page_layout.setSpacing(14)

        def section(title_text):
            card = QFrame()
            card.setObjectName("settingsCard")
            box = QVBoxLayout(card)
            box.setContentsMargins(18, 16, 18, 16)
            box.setSpacing(0)
            heading = label(title_text, "settingsSectionTitle")
            box.addWidget(heading)
            box.addSpacing(10)
            page_layout.addWidget(card)
            return box

        def divider(box):
            line = QFrame()
            line.setObjectName("settingsDivider")
            line.setFixedHeight(1)
            box.addWidget(line)

        def setting_row(box, name, control=None, value_text=None, description=None):
            row = QHBoxLayout()
            row.setContentsMargins(0, 8, 0, 8)
            row.setSpacing(18)
            text_box = QVBoxLayout()
            text_box.setSpacing(2)
            name_label = label(name, "settingsLabel")
            text_box.addWidget(name_label)
            if description:
                desc = label(description, "settingsDescription")
                desc.setWordWrap(True)
                text_box.addWidget(desc)
            row.addLayout(text_box, 1)
            if value_text is not None:
                value = label(value_text, "settingsValue")
                value.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
                row.addWidget(value)
            if control is not None:
                row.addWidget(control, 0, Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
            box.addLayout(row)
            return row

        # Reproducción
        playback = section("Reproducción")
        crossfade = QSpinBox()
        crossfade.setRange(0, 12)
        crossfade.setSuffix(" s")
        crossfade.setFixedWidth(118)
        crossfade.setValue(self.player.crossfade)
        crossfade.valueChanged.connect(
            lambda value: (setattr(self.player, "crossfade", value), self.db.set_setting("crossfade", value))
        )
        setting_row(playback, "Crossfade", crossfade)
        divider(playback)

        style = QComboBox()
        style.addItems(["Vinilo", "Circular"])
        style.setFixedWidth(150)
        style.setCurrentIndex(0 if self.db.setting("player_style", "vinyl") == "vinyl" else 1)

        def set_style(index):
            value = "vinyl" if index == 0 else "circular"
            self.db.set_setting("player_style", value)
            if self.player_dialog:
                self.record.style = value
                self.record.update()

        style.currentIndexChanged.connect(set_style)
        setting_row(playback, "Estilo del reproductor", style)
        divider(playback)

        sleep = QSpinBox()
        sleep.setRange(0, 240)
        sleep.setSuffix(" min")
        sleep.setSpecialValueText("Apagado")
        sleep.setFixedWidth(140)
        if self.player.sleep_deadline:
            sleep.setValue(max(1, round((self.player.sleep_deadline - time.monotonic()) / 60)))
        sleep.valueChanged.connect(self.player.set_sleep)
        setting_row(playback, "Temporizador de sueño", sleep)
        divider(playback)

        prefetch = QCheckBox()
        prefetch.setObjectName("settingsSwitch")
        prefetch.setChecked(self.db.setting("prefetch", True))
        prefetch.toggled.connect(lambda value: self.db.set_setting("prefetch", value))
        setting_row(playback, "Preparar siguiente canción", prefetch)
        divider(playback)

        continue_playing = QCheckBox()
        continue_playing.setObjectName("settingsSwitch")
        continue_playing.setChecked(self.db.setting("auto_continue", False))
        continue_playing.toggled.connect(lambda value: self.db.set_setting("auto_continue", value))
        setting_row(playback, "Continuar con música relacionada", continue_playing)

        # Cuenta
        account = section("Cuenta")
        spotify_status = label(
            "Conectado" if session_configured() else "No conectado",
            "settingsValue",
        )
        spotify_button = QPushButton("Administrar" if session_configured() else "Conectar")
        spotify_button.setObjectName("settingsAction")
        spotify_button.setMinimumWidth(116)

        def connect_spotify():
            self.spotify_account_dialog()
            connected = session_configured()
            spotify_status.setText("Conectado" if connected else "No conectado")
            spotify_button.setText("Administrar" if connected else "Conectar")

        spotify_button.clicked.connect(connect_spotify)
        account_row = QHBoxLayout()
        account_row.setContentsMargins(0, 8, 0, 8)
        account_row.setSpacing(12)
        account_row.addWidget(label("Spotify", "settingsLabel"), 1)
        account_row.addWidget(spotify_status)
        account_row.addWidget(spotify_button)
        account.addLayout(account_row)

        # Biblioteca y almacenamiento
        storage = section("Biblioteca y almacenamiento")
        folder_path = self.db.setting("download_folder", str(data_dir() / "downloads"))
        folder_value = label(Path(folder_path).name or folder_path, "settingsValue")
        folder_value.setToolTip(folder_path)
        folder_button = QPushButton("Cambiar")
        folder_button.setObjectName("settingsAction")

        def choose_folder():
            path = QFileDialog.getExistingDirectory(dialog, "Carpeta de descargas", self.db.setting("download_folder", folder_path))
            if path:
                self.db.set_setting("download_folder", path)
                folder_value.setText(Path(path).name or path)
                folder_value.setToolTip(path)
                path_hint.setText(path)

        folder_button.clicked.connect(choose_folder)
        row = QHBoxLayout()
        row.setContentsMargins(0, 8, 0, 8)
        row.setSpacing(12)
        text_box = QVBoxLayout()
        text_box.setSpacing(2)
        text_box.addWidget(label("Carpeta de descargas", "settingsLabel"))
        path_hint = label(folder_path, "settingsDescription")
        path_hint.setWordWrap(True)
        text_box.addWidget(path_hint)
        row.addLayout(text_box, 1)
        row.addWidget(folder_value)
        row.addWidget(folder_button)
        storage.addLayout(row)
        divider(storage)

        roots = QListWidget()
        roots.setObjectName("settingsRoots")
        roots.setMaximumHeight(112)
        roots.addItems(self.db.setting("library_roots", []))
        roots.setVisible(roots.count() > 0)
        roots_title = label("Carpetas escaneadas", "settingsLabel")
        storage.addWidget(roots_title)
        if roots.count() > 0:
            storage.addSpacing(6)
        storage.addWidget(roots)
        remove_root = QPushButton("Dejar de escanear carpeta seleccionada")
        remove_root.setObjectName("settingsGhost")
        remove_root.setEnabled(roots.count() > 0)

        def forget_root():
            item = roots.takeItem(roots.currentRow())
            if item:
                self.db.set_setting("library_roots", [roots.item(i).text() for i in range(roots.count())])
                roots.setVisible(roots.count() > 0)
                remove_root.setEnabled(roots.count() > 0)

        remove_root.clicked.connect(forget_root)
        storage.addSpacing(8)
        storage.addWidget(remove_root, 0, Qt.AlignmentFlag.AlignLeft)
        divider(storage)

        import_library_button = QPushButton("Importar carpeta")
        import_library_button.setObjectName("settingsAction")

        def import_library_folder():
            current_roots = self.db.setting("library_roots", [])
            start_dir = current_roots[0] if current_roots else str(Path.home())
            path = QFileDialog.getExistingDirectory(dialog, "Importar carpeta de música", start_dir)
            if not path:
                return
            roots_now = self.db.setting("library_roots", [])
            if path not in roots_now:
                roots_now = roots_now + [path]
                self.db.set_setting("library_roots", roots_now)
                roots.addItem(path)
            roots.setVisible(roots.count() > 0)
            remove_root.setEnabled(roots.count() > 0)
            self.import_paths([path])

        import_library_button.clicked.connect(import_library_folder)
        setting_row(storage, "Música local", import_library_button)
        divider(storage)

        auto = QCheckBox()
        auto.setObjectName("settingsSwitch")
        auto.setChecked(self.db.setting("auto_scan", True))
        auto.toggled.connect(
            lambda value: (self.db.set_setting("auto_scan", value), self.auto_scan.start() if value else self.auto_scan.stop())
        )
        setting_row(storage, "Actualizar biblioteca automáticamente", auto)
        divider(storage)

        minimum = QSpinBox()
        minimum.setRange(0, 600)
        minimum.setSuffix(" s")
        minimum.setSpecialValueText("Sin mínimo")
        minimum.setFixedWidth(140)
        minimum.setValue(self.db.setting("minimum_duration", 0))
        minimum.valueChanged.connect(lambda value: self.db.set_setting("minimum_duration", value))
        setting_row(storage, "Duración mínima al escanear", minimum)

        # Caché y datos
        data = section("Caché y datos")
        cache_status = label("", "settingsValue")

        def refresh_stream_cache_status():
            used = self.streaming.cache_usage()
            if used >= 1024 ** 3:
                text = f"{used / 1024 ** 3:.2f} GB"
            else:
                text = f"{used / 1024 ** 2:.0f} MB"
            cache_status.setText(text)

        def clear_stream_cache():
            self.streaming.clear_cache()
            refresh_stream_cache_status()
            self.notify("Caché temporal de streaming borrada.")

        refresh_stream_cache_status()
        clear_cache = QPushButton("Limpiar")
        clear_cache.setObjectName("settingsAction")
        clear_cache.clicked.connect(clear_stream_cache)
        cache_row = QHBoxLayout()
        cache_row.setContentsMargins(0, 8, 0, 8)
        cache_row.setSpacing(12)
        cache_text = QVBoxLayout()
        cache_text.setSpacing(2)
        cache_text.addWidget(label("Caché de streaming", "settingsLabel"))
        cache_text.addWidget(label("Máximo 1 GB · caduca tras 24 h sin uso", "settingsDescription"))
        cache_row.addLayout(cache_text, 1)
        cache_row.addWidget(cache_status)
        cache_row.addWidget(clear_cache)
        data.addLayout(cache_row)
        divider(data)

        clear_search = QPushButton("Borrar búsquedas recientes")
        clear_search.setObjectName("settingsGhost")
        clear_search.clicked.connect(lambda: (self.db.clear_searches(), self.notify("Historial de búsqueda borrado.")))
        data.addWidget(clear_search, 0, Qt.AlignmentFlag.AlignLeft)

        # Aplicación
        app_section = section("Aplicación")
        tray = QCheckBox()
        tray.setObjectName("settingsSwitch")
        tray.setChecked(self.db.setting("close_to_tray", True))
        tray.setEnabled(self.tray is not None)
        tray.toggled.connect(lambda value: self.db.set_setting("close_to_tray", value))
        setting_row(app_section, "Seguir reproduciendo al cerrar", tray)
        divider(app_section)

        backup_button = QPushButton("Exportar copia de biblioteca…")
        backup_button.setObjectName("settingsAction")

        def backup():
            path, _ = QFileDialog.getSaveFileName(dialog, "Guardar copia de biblioteca", "MusicVault.sqlite3", "SQLite (*.sqlite3)")
            if path:
                try:
                    if Path(path).resolve() == self.db.path.resolve():
                        raise ValueError("Elige una ruta distinta a la base de datos activa.")
                    self.db.export_backup(path)
                    self.notify("Copia de la biblioteca guardada. Los archivos de música se respaldan por separado.")
                except Exception as exc:
                    self.notify(str(exc))

        backup_button.clicked.connect(backup)
        setting_row(app_section, "Copia de seguridad", backup_button)

        # Acerca de
        about_card = section("Acerca de")
        about_row = QHBoxLayout()
        about_row.setContentsMargins(0, 8, 0, 8)
        about_row.addWidget(label("MusicVault", "settingsLabel"), 1)
        about_row.addWidget(label(f"{__version__} · Windows x64", "settingsValue"))
        about_card.addLayout(about_row)
        about_meta = label("Qt 6 · libmpv · SQLite · yt-dlp · FFmpeg", "settingsDescription")
        about_card.addWidget(about_meta)

        page_layout.addStretch()
        scroll.setWidget(page)
        root.addWidget(scroll, 1)

        footer = QHBoxLayout()
        footer.addStretch()
        done = QPushButton("Listo")
        done.setObjectName("primary")
        done.setMinimumWidth(110)
        done.clicked.connect(dialog.accept)
        footer.addWidget(done)
        root.addLayout(footer)
        dialog.exec()

    def quit_app(self):
        self._quitting = True
        self.close()

    def closeEvent(self, event):
        # Normal X: keep playback alive in the system tray when the setting is
        # enabled. Explicit "Salir" / Ctrl+Q performs the full shutdown path.
        close_to_tray = bool(self.db.setting("close_to_tray", True))
        if not self._quitting and close_to_tray and self.tray is not None:
            event.ignore()
            self._hide_auxiliary_windows_for_tray()
            # Hide asynchronously after Qt finishes dispatching the native close
            # event. This avoids a tiny KWin/Plasma shell being left behind.
            QTimer.singleShot(0, self.hide)
            return

        self._quitting = True
        app = QApplication.instance()

        # Full exit: close every auxiliary top-level window first so no Qt
        # surface can keep the process/window manager entry alive.
        if app:
            for widget in list(app.topLevelWidgets()):
                if widget is self:
                    continue
                try:
                    widget.hide()
                    widget.close()
                except RuntimeError:
                    pass

        self.auto_scan.stop()
        self.debounce.stop()
        self.local_debounce.stop()
        self.table.cover_timer.stop()
        if self.tray:
            self.tray.hide()
            self.tray.setContextMenu(None)
            self.tray = None
        for key in list(self.download_jobs):
            self.cancel_download(key)
        self.cancel_stream_warm()
        if self.stream_prepare_job:
            self.stream_prepare_job.cancel.set()
        self.stream_jobs.close()
        self.warm_jobs.close()
        self.jobs.close()
        self.cover_jobs.close()
        for key in self.download_jobs:
            track = self.db.get_track(key)
            record = next((r for r in self.db.downloads() if r["track_id"] == key), None)
            if track and record and record["status"] != "complete":
                self.db.set_download(track, "cancelled")
        if self.mpris:
            self.mpris.close()
            self.mpris = None
        self.player.close()
        event.accept()
        if app:
            app.quit()

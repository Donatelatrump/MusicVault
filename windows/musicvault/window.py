from collections import OrderedDict
from dataclasses import replace
from pathlib import Path
import json
import time
import unicodedata

from PyQt6.QtCore import Qt, QTimer, QUrl, QSize
from PyQt6.QtGui import QAction, QDesktopServices, QIcon, QKeySequence, QPixmap, QShortcut, QColor
from PyQt6.QtWidgets import (
    QApplication, QAbstractItemView, QCheckBox, QComboBox, QCompleter, QDialog,
    QDoubleSpinBox, QFileDialog, QFormLayout, QFrame, QHBoxLayout, QHeaderView,
    QInputDialog, QLabel, QLineEdit, QListWidget, QListWidgetItem, QMainWindow,
    QMenu, QMessageBox, QPushButton, QScrollArea, QSlider, QSpinBox,
    QStackedWidget, QSystemTrayIcon, QVBoxLayout, QWidget,
)

from . import __version__
from .library import scan
from .models import Track, clock_text, data_dir
from .player import PRESETS
from .services import Catalog, DownloadCancelled, Jobs, cover_file, download, fetch_lyrics
from .spotify import playlist_url, spotify_url_from_text
from .songmirror import load_playlists
from .songmirror_spotify import clear_session, save_session, session_configured, validate_session
from .downloader import explain_download_error
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
        self.route, self.collection, self.remote_album = "Inicio", None, None
        self.local_album = None
        self.shown_tracks, self.remote_tracks, self.remote_albums = [], [], []
        self.search_generation = 0
        self.search_job = None
        self.download_jobs, self.download_callbacks = {}, {}
        self.download_items = {}
        self.download_progress_values = {}
        self.cover_pending, self.cover_cache = {}, OrderedDict()
        self.cover_failures = {}
        self.cover_jobs = Jobs(self)
        self.cover_jobs.pool.setMaxThreadCount(2)
        self.home_signature = None
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
        self._tray()
        self._shortcuts()
        player.track_changed.connect(self.track_changed)
        player.changed.connect(self.playback_changed)
        player.progress.connect(self.playback_progress)
        player.queue_changed.connect(self.refresh_queue)
        player.error.connect(self.notify)
        player.need_track.connect(self.ensure_playable)
        self.refresh_sidebar()
        self.show_route("Inicio")
        self.track_changed(player.current)
        self.playback_changed()
        self.refresh_queue()
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
            menu.addAction("Mostrar MusicVault", self.showNormal)
            menu.addAction("Reproducir / pausar", self.player.toggle)
            menu.addAction("Siguiente", self.player.next)
            menu.addSeparator()
            menu.addAction("Salir", self.quit_app)
            self.tray.setContextMenu(menu)
            self.tray.activated.connect(lambda reason: self.showNormal() if reason == QSystemTrayIcon.ActivationReason.Trigger else None)
            self.tray.show()

    def _shortcuts(self):
        bindings = {"Ctrl+O": self.import_dialog, "Ctrl+F": lambda: (self.show_route("Buscar"), self.search.setFocus()),
                    "Ctrl+Q": self.quit_app, "Ctrl+P": self.player.toggle,
                    "Alt+Right": self.player.next, "Alt+Left": self.player.previous,
                    "Ctrl+L": self.open_queue}
        self.shortcuts = [QShortcut(QKeySequence(key), self, activated=callback) for key, callback in bindings.items()]

    def show_route(self, route):
        self.route, self.collection, self.remote_album = route, None, None
        self.local_album = None
        for name, widget in self.nav.items():
            active = name == route
            widget.setChecked(active)
            glyph = {"Inicio":"home","Buscar":"search","Biblioteca":"library","Álbumes":"album","Descargas":"download","Favoritos":"heart"}[name]
            widget.setIcon(icon(glyph,"#E0A458" if active else "#B3A992",20))
        self.heading.setText(route)
        self.heading.setVisible(route != "Inicio")
        self.home_greeting.setVisible(route == "Inicio")
        self.description.setVisible(route not in ("Inicio", "Biblioteca", "Favoritos"))
        self.home_shelf.setVisible(route == "Inicio")
        self.retry_download_button.setVisible(route == "Descargas")
        self.cancel_download_button.setVisible(route == "Descargas")
        self.favorites_filter.setVisible(route in ("Biblioteca","Favoritos"))
        self.library_filters.setVisible(route in ("Biblioteca", "Favoritos"))
        self.list_title.setText("Tu música" if route == "Inicio" else "Canciones")
        self.search_button.setVisible(route == "Buscar")
        self.search_source.setVisible(route == "Buscar")
        self.search.show()
        self.search.setPlaceholderText("Filtrar tu música" if route in ("Biblioteca", "Favoritos") else "¿Qué quieres escuchar?")
        self.library_badge.setVisible(route != "Buscar" and self.width() >= 1200)
        self.search.blockSignals(True)
        self.search.clear()
        self.search.blockSignals(False)
        self.debounce.stop()
        self.local_debounce.stop()
        if self.search_job:
            self.search_job.cancel.set()
        if hasattr(self, "import_loading"):
            self.import_loading.hide()
        self.search_generation += 1
        self.remote_tracks, self.remote_albums = [], []
        self.download_all_button.hide()
        self.collection_menu_button.hide()
        self.new_collection_button.setVisible(route == "Álbumes")
        self.rescan_button.setVisible(route == "Biblioteca")
        self.play_all_button.setVisible(route in ("Biblioteca","Favoritos","Buscar"))
        self.album_list.hide()
        if route == "Álbumes":
            self.description.setText("Playlists, álbumes personalizados y álbumes de tus archivos.")
            self.stack.setCurrentWidget(self.collections_list)
            self.refresh_collections()
        elif route == "Descargas":
            self.description.setText("Las canciones aparecen en Biblioteca cuando el audio está listo. Selecciona una descarga para reintentar o cancelar.")
            self.stack.setCurrentWidget(self.downloads_list)
            self.refresh_downloads()
        else:
            self.stack.setCurrentWidget(self.track_page)
            descriptions = {
                "Inicio": "Vuelve a tus últimas escuchas o descubre tu biblioteca.",
                "Biblioteca": "Archivos locales bajo tu control. Doble clic para reproducir; clic derecho para organizar.",
                "Favoritos": "Las canciones que quieres tener siempre a mano.",
                "Buscar": "Busca en tu biblioteca y en el catálogo musical. La reproducción descarga el audio completo.",
            }
            self.description.setText(descriptions.get(route, ""))
            self.refresh_tracks()
            if route == "Buscar" and self.db.searches():
                self.notify("Búsquedas recientes: " + " · ".join(self.db.searches()))

    def refresh_tracks(self):
        query = self.search.text()
        if self.remote_album:
            tracks = [self.db.get_track(t.id) or t for t in self.remote_tracks]
        elif self.local_album is not None:
            tracks = [t for key in self.local_album if (t := self.db.get_track(key))]
        elif self.collection:
            tracks = self.db.tracks(query, collection=self.collection)
        elif self.route in ("Biblioteca", "Favoritos"):
            all_tracks = self.db.library_tracks(favorites=self.route == "Favoritos")
            self.refresh_library_filters(all_tracks)
            words = query.casefold().split()
            artist = self.artist_filter.currentData() or ""
            album = self.album_filter.currentData() or ""
            tracks = [
                track for track in all_tracks
                if (not artist or track.artist == artist)
                and (not album or track.album == album)
                and all(word in f"{track.title} {track.artist} {track.album}".casefold() for word in words)
            ]
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
            recent = self.db.library_tracks(query, recent=True)
            seen = {t.id for t in recent}
            tracks = recent + [t for t in self.db.library_tracks(query) if t.id not in seen]
        elif self.route == "Buscar":
            local = self.db.tracks(query)
            seen = {t.id for t in local}
            tracks = local + [self.db.get_track(t.id) or t for t in self.remote_tracks if t.id not in seen] if query else []
        else:
            tracks = self.db.library_tracks(query)
        self.render_tracks(tracks)

    def refresh_library_filters(self, tracks):
        """Refresh facet choices without discarding the listener's selection."""
        artists = tuple(sorted({track.artist.strip() for track in tracks if track.artist.strip()}, key=str.casefold))
        albums = tuple(sorted({track.album.strip() for track in tracks if track.album.strip()}, key=str.casefold))
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

    def render_tracks(self, tracks):
        selected = {t.id for t in self.selected_tracks()}
        prior_ids = [t.id for t in self.shown_tracks]
        self.shown_tracks = tracks
        records = {r["track_id"]: r for r in self.db.downloads()}
        current = self.player.current
        self.track_model.set_tracks(tracks, records, self.download_jobs, current.id if current else None)
        # The table is part of the page, so it exposes all rows to the outer
        # scroll surface rather than keeping an independent vertical scroll.
        header_height = self.table.horizontalHeader().height() or 34
        table_height = header_height + max(1, len(tracks)) * self.table.verticalHeader().defaultSectionSize() + 2
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
        self.table.schedule_covers()

    def load_visible_covers(self):
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
        signature = tuple((c["id"], c["title"], c["count"]) for c in collections)
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
        self.sidebar_hint.setVisible(not collections)

    def open_sidebar_collection(self, item):
        self.show_route("Álbumes")
        self.open_collection_item(item)

    def resizeEvent(self, event):
        super().resizeEvent(event)
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

    def search_changed(self):
        if self.route not in ("Buscar", "Biblioteca", "Favoritos"):
            query = self.search.text()
            self.show_route("Buscar")
            self.search.blockSignals(True)
            self.search.setText(query)
            self.search.blockSignals(False)
            self.search.setFocus()
        self.search_generation += 1
        self.debounce.stop()
        if self.search_job:
            self.search_job.cancel.set()
        if self.remote_album:
            self.remote_album = None
            self.heading.setText("Buscar")
            self.download_all_button.hide()
        if self.route == "Buscar":
            self.remote_tracks, self.remote_albums = [], []
            self.album_list.clear()
            self.album_list.hide()
            if len(self.search.text().strip()) >= 2:
                self.debounce.start()
        self.local_debounce.start()

    def submit_search(self):
        self.debounce.stop()
        self.local_debounce.stop()
        if self.route != "Buscar":
            self.refresh_tracks()
            return
        query = self.search.text().strip()
        if len(query) < 2:
            return
        self.search_generation += 1
        generation = self.search_generation
        if self.search_job:
            self.search_job.cancel.set()
        self.db.add_search(query)
        self.search_history.model().setStringList(self.db.searches())
        self.notify("Consultando catálogo…")

        def ready(result):
            if generation != self.search_generation or self.route != "Buscar":
                return
            self.remote_tracks = [Track.from_dict(t) for t in result["tracks"]]
            self.remote_albums = result["albums"]
            self.album_list.clear()
            for album in self.remote_albums:
                item = QListWidgetItem(f"{album['title']}\n{album['artist']}")
                item.setData(Qt.ItemDataRole.UserRole, album)
                item.setSizeHint(self.album_list.gridSize())
                item.setTextAlignment(Qt.AlignmentFlag.AlignHCenter | Qt.AlignmentFlag.AlignTop)
                # Set decoration geometry before the asynchronous cover arrives;
                # uniform sizing otherwise caches a text-only row and clips it.
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
                    self.album_list.doItemsLayout()
                    self.album_list.viewport().update()

                self.load_cover(album.get("cover", ""), set_album_cover)
            self.album_list.setVisible(bool(self.remote_albums))
            self.refresh_tracks()
            self.notify(result.get("notice", f"{len(self.remote_tracks)} canciones encontradas en el catálogo."))

        source = "youtube" if self.search_source.currentIndex() == 1 else "catalog"
        self.search_job = self.jobs.submit(lambda cancel, progress: self.catalog.search(query, source, cancel), ready,
            lambda error: self.notify(error) if generation == self.search_generation else None)

    def import_spotify_playlist(self, url):
        self.search_generation += 1
        generation = self.search_generation
        if self.search_job:
            self.search_job.cancel.set()
        self.import_loading.show()
        self.notify("Leyendo playlist de Spotify mediante SongMirror…")

        def ready(playlist):
            self.import_loading.hide()
            if generation != self.search_generation or self.route != "Buscar":
                return
            tracks = self.choose_playlist_tracks(playlist, "SongMirror")
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
            answer = QMessageBox.question(self, "Playlist de Spotify mediante SongMirror",
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
            self.description.setText("Playlist de Spotify importada mediante SongMirror. Las canciones se resuelven con tu proveedor al reproducir o descargar.")
            self.download_all_button.setText("Descargar playlist")
            self.download_all_button.setVisible(bool(tracks))
            self.render_tracks(self.remote_tracks)
            self.notify(f"{len(tracks)} canciones importadas mediante SongMirror.")

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
            self.description.setText(
                "Canción importada desde Spotify. Reprodúcela o pulsa Descargar canción para guardar el audio."
            )
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

    def import_songmirror_file(self, path):
        self.search_generation += 1
        generation = self.search_generation
        if self.search_job:
            self.search_job.cancel.set()
        self.import_loading.setToolTip("Leyendo exportación de SongMirror…")
        self.import_loading.setAccessibleName("Cargando exportación de SongMirror")
        self.import_loading.show()
        self.notify("Leyendo exportación de SongMirror…")

        def ready(playlists):
            self.import_loading.hide()
            self.import_loading.setToolTip("Leyendo la playlist de Spotify mediante SongMirror…")
            self.import_loading.setAccessibleName("Cargando playlist desde SongMirror")
            if generation != self.search_generation or self.route != "Buscar":
                return
            selected = playlists[0]
            if len(playlists) > 1:
                names = [f"{playlist.title} · {len(playlist.tracks)} canciones" for playlist in playlists]
                choice, accepted = QInputDialog.getItem(self, "Elegir playlist de SongMirror",
                                                        "El archivo contiene varias playlists:", names, 0, False)
                if not accepted:
                    return
                selected = playlists[names.index(choice)]
            tracks = self.choose_playlist_tracks(selected, "SongMirror")
            if tracks is None:
                self.notify("Importación de playlist cancelada.")
                return
            if not tracks:
                QMessageBox.information(self, "Playlist vacía", "Selecciona al menos una canción para importarla.")
                return
            existing = self.db.collection_by_source("songmirror", selected.id)
            verb = "Actualizar" if existing else "Agregar"
            answer = QMessageBox.question(self, "Playlist de SongMirror",
                f"{selected.title}\n\n{len(tracks)} canciones seleccionadas.\n\n¿{verb} esta playlist en MusicVault?")
            if answer != QMessageBox.StandardButton.Yes:
                return
            key = existing["id"] if existing else self.db.create_collection(selected.title, "playlist", "songmirror", selected.id,
                selected.url, selected.cover)
            self.db.replace_collection(key, selected.title, tracks, "songmirror", selected.id, selected.url, selected.cover)
            self.refresh_sidebar()
            self.remote_tracks = list(tracks)
            self.collection = key
            self.search.blockSignals(True)
            self.search.clear()
            self.search.blockSignals(False)
            self.heading.setText(selected.title)
            self.description.setText("Playlist importada desde una exportación de SongMirror. Las canciones se resuelven con tu proveedor al reproducir o descargar.")
            self.download_all_button.setText("Descargar playlist")
            self.download_all_button.setVisible(True)
            self.render_tracks(self.remote_tracks)
            self.notify(f"{len(tracks)} canciones importadas desde SongMirror.")

        def error(message):
            self.import_loading.hide()
            self.import_loading.setToolTip("Leyendo la playlist de Spotify mediante SongMirror…")
            self.import_loading.setAccessibleName("Cargando playlist desde SongMirror")
            if generation != self.search_generation:
                return
            self.message_text.setText(str(message))
            self.message_details.hide()
            self.retry_action = lambda: self.import_songmirror_file(path)
            self.message_bar.show()
            self.notify(message)

        self.search_job = self.jobs.submit(lambda cancel, progress: load_playlists(path), ready, error)

    def choose_playlist_tracks(self, playlist, provider):
        """Let the listener remove entries before a playlist is persisted."""
        dialog = QDialog(self)
        dialog.setWindowTitle(f"Revisar playlist de {provider}")
        dialog.resize(620, 560)
        outer = QVBoxLayout(dialog)
        outer.setContentsMargins(22, 20, 22, 20)
        outer.setSpacing(12)
        title = label(playlist.title, "section")
        title.setWordWrap(True)
        outer.addWidget(title)
        hint = label("Desmarca las canciones que no quieras guardar. La playlist se puede limpiar antes de importarla.", "muted")
        hint.setWordWrap(True)
        outer.addWidget(hint)
        entries = QListWidget()
        entries.setObjectName("playlistReview")
        for track in playlist.tracks:
            item = QListWidgetItem(f"{track.title}  ·  {track.artist}")
            item.setData(Qt.ItemDataRole.UserRole, track)
            item.setFlags(item.flags() | Qt.ItemFlag.ItemIsUserCheckable)
            item.setCheckState(Qt.CheckState.Checked)
            entries.addItem(item)
        outer.addWidget(entries, 1)
        tools = QHBoxLayout()
        select_all = button("Seleccionar todo", lambda: [entries.item(i).setCheckState(Qt.CheckState.Checked) for i in range(entries.count())])
        clear_all = button("Limpiar selección", lambda: [entries.item(i).setCheckState(Qt.CheckState.Unchecked) for i in range(entries.count())])
        tools.addWidget(select_all)
        tools.addWidget(clear_all)
        tools.addStretch()
        cancel = button("Cancelar", dialog.reject)
        accept = button("Importar seleccionadas", dialog.accept, True)
        tools.addWidget(cancel)
        tools.addWidget(accept)
        outer.addLayout(tools)
        if dialog.exec() != QDialog.DialogCode.Accepted:
            return None
        return tuple(entries.item(i).data(Qt.ItemDataRole.UserRole) for i in range(entries.count())
                     if entries.item(i).checkState() == Qt.CheckState.Checked)

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
        if any(not t.path for t in selected):
            menu.addAction("Descargar", lambda: [self.start_download(t) for t in selected if not t.path])
        if track.path:
            menu.addAction("Mostrar carpeta", lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(str(Path(track.path).parent))))
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
        title, ok = QInputDialog.getText(self, "Crear colección", "Nombre:")
        if not ok or not title.strip():
            return
        kind, ok = QInputDialog.getItem(self, "Tipo de colección", "Tipo:", ["Playlist", "Álbum personalizado"], 0, False)
        if ok:
            self.db.create_collection(title, "playlist" if kind == "Playlist" else "album")
            self.refresh_collections()

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
        collection_kind = data.get("kind") if self.collection else ""
        self.download_all_button.setText("Descargar álbum" if collection_kind == "album" else "Descargar playlist")
        self.download_all_button.setVisible(bool(collection_kind))
        self.description.setText("Doble clic para escuchar. Organiza las canciones desde el menú contextual.")
        self.render_tracks([Track.from_dict(t) for t in data["local"]] if "local" in data else self.db.tracks(collection=self.collection))

    def collection_list_menu(self, position):
        item = self.collections_list.itemAt(position)
        if item and item.data(Qt.ItemDataRole.UserRole).get("id"):
            self.open_collection_item(item)
            self.collection_options()

    def collection_options(self):
        if not self.collection:
            return
        action, ok = QInputDialog.getItem(self, "Editar colección", "Acción:", ["Cambiar nombre", "Eliminar colección"], 0, False)
        if not ok:
            return
        if action == "Cambiar nombre":
            name, ok = QInputDialog.getText(self, "Cambiar nombre", "Nombre:", text=self.heading.text())
            if ok and name.strip():
                self.db.rename_collection(self.collection, name)
                self.heading.setText(name)
                self.refresh_sidebar()
        elif QMessageBox.question(self, "Eliminar colección", "¿Eliminar esta colección? Las canciones se conservarán.") == QMessageBox.StandardButton.Yes:
            self.db.delete_collection(self.collection)
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

    def open_album_item(self, item):
        album = item.data(Qt.ItemDataRole.UserRole)
        self.search_generation += 1
        generation = self.search_generation
        self.notify("Cargando álbum…")

        def ready(tracks):
            if generation != self.search_generation:
                return
            self.remote_album, self.collection = album, None
            self.remote_tracks = tracks
            self.heading.setText(album["title"])
            self.description.setText(album["artist"])
            self.album_list.hide()
            self.download_all_button.setText("Descargar álbum")
            self.download_all_button.show()
            self.render_tracks(tracks)

        self.jobs.submit(lambda cancel, progress: self.catalog.album_tracks(album), ready, self.notify)

    def download_all(self):
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

    def import_dialog(self):
        menu = QMenu(self)
        menu.addAction("Archivos de audio…", self.import_files)
        menu.addAction("Carpeta de música…", self.import_folder)
        menu.addSeparator()
        account_text = ("Administrar cuenta de Spotify para SongMirror…" if session_configured()
                        else "Agregar cuenta de Spotify para SongMirror…")
        spotify_account = menu.addAction(account_text, self.spotify_account_dialog)
        spotify_account.setIcon(icon("user", "#E0A458", 18))
        spotify = menu.addAction("Playlist de Spotify mediante SongMirror…", self.import_spotify_dialog)
        spotify.setIcon(icon("search", "#E0A458", 18))
        trigger = self.sender()
        position = (trigger.mapToGlobal(trigger.rect().bottomLeft()) if isinstance(trigger, QWidget)
                    else self.mapToGlobal(self.rect().center()))
        menu.exec(position)

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
                self.notify("Spotify quedó conectado para importar playlists.")
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
            self.notify("Se eliminó la sesión local de Spotify.")

        dialog.session_captured.connect(captured)
        dialog.disconnect_requested.connect(remove_account)
        dialog.exec()

    def import_spotify_dialog(self):
        url, ok = QInputDialog.getText(self, "Playlist de Spotify mediante SongMirror", "Pega el enlace de la playlist de Spotify:")
        if not ok or not url.strip():
            return
        try:
            _, canonical = playlist_url(url)
        except ValueError as exc:
            QMessageBox.warning(self, "Enlace no válido", str(exc))
            return
        # Aunque se inicie desde Biblioteca, Buscar muestra las pistas recién
        # importadas y conserva la playlist en la barra lateral.
        self.show_route("Buscar")
        self.search.blockSignals(True)
        self.search.setText(canonical)
        self.search.blockSignals(False)
        self.import_spotify_playlist(canonical)

    def import_songmirror_dialog(self):
        path, _ = QFileDialog.getOpenFileName(self, "Importar exportación de SongMirror", str(Path.home()),
            "Exportaciones de SongMirror (*.json *.xml);;JSON (*.json);;XML (*.xml);;Todos los archivos (*)")
        if not path:
            return
        self.show_route("Buscar")
        self.import_songmirror_file(path)

    def import_files(self):
        paths, _ = QFileDialog.getOpenFileNames(self, "Importar música", str(Path.home()),
            "Audio (*.mp3 *.flac *.ogg *.opus *.m4a *.aac *.wav *.aiff *.wma *.ape *.wv);;Todos los archivos (*)")
        if paths:
            self.import_paths(paths)

    def import_folder(self):
        path = QFileDialog.getExistingDirectory(self, "Elegir carpeta de música", str(Path.home()))
        if path:
            roots = self.db.setting("library_roots", [])
            if path not in roots:
                self.db.set_setting("library_roots", roots + [path])
            self.import_paths([path])

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

    def ensure_playable(self, track):
        expected = self.player.index
        request = self.player.request_generation

        def ready(result):
            if (self.player.current and self.player.current.id == result.id and self.player.index == expected
                    and self.player.request_generation == request):
                self.player.play_index(expected)

        record = self.db.download_record(track.id)
        if record and record["status"] in ("error", "cancelled", "interrupted"):
            self.notify("La descarga anterior no se completó. Puedes reintentar desde el aviso.")
            self.show_download_error(track, ready)
            return
        self.start_download(track, ready)

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

        def progress(value):
            if completed or (track.id in self.download_jobs and self.download_jobs[track.id].cancel.is_set()):
                return
            if self.download_progress_values.get(track.id) == value:
                return
            self.download_progress_values[track.id] = value
            self.db.set_download(track, "downloading", value)
            self.track_model.set_progress(track.id, value)
            if self.route == "Descargas":
                self.schedule_download_refresh()

        def work(cancel, report):
            try:
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

    def playback_changed(self):
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
            self.full_play.setIcon(icon("pause" if self.player.playing else "play","#191207",34))
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
        def deliver(path):
            if not self._quitting:
                try:
                    callback(path)
                except RuntimeError:
                    pass  # Qt receiver was removed by a newer search or route.

        if not source or not source.startswith("https://"):
            deliver(source)
            return
        if source in self.cover_cache:
            self.cover_cache.move_to_end(source)
            deliver(self.cover_cache[source])
            return
        if time.monotonic() - self.cover_failures.get(source, -60) < 30:
            deliver("")
            return
        if source in self.cover_pending:
            self.cover_pending[source].append(deliver)
            return
        self.cover_pending[source] = [deliver]

        def ready(path):
            if path:
                self.cover_cache[source] = path
                while len(self.cover_cache) > 256:
                    self.cover_cache.popitem(last=False)
            else:
                self.cover_failures[source] = time.monotonic()
                if len(self.cover_failures) > 256:
                    self.cover_failures.pop(next(iter(self.cover_failures)))
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
        # Prefetch one successor so remote album playback can continue after the current file.
        if self.db.setting("prefetch", True) and self.player.playing:
            index = self.player.next_index(automatic=True)
            if index is not None:
                successor = self.player.queue[index]
                if successor.source != "local" and not successor.path:
                    self.start_download(successor)

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
        dialog.setWindowTitle("Ajustes de MusicVault")
        dialog.resize(670, 720)
        layout = QVBoxLayout(dialog)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        page = QWidget()
        form = QFormLayout(page)
        form.setSpacing(16)
        form.addRow(label("Tu forma de escuchar", "heading"))
        crossfade = QSpinBox()
        crossfade.setRange(0, 12)
        crossfade.setSuffix(" s")
        crossfade.setValue(self.player.crossfade)
        crossfade.valueChanged.connect(lambda value: (setattr(self.player, "crossfade", value), self.db.set_setting("crossfade", value)))
        form.addRow("Crossfade", crossfade)
        style = QComboBox()
        style.addItems(["Vinilo", "Circular"])
        style.setCurrentIndex(0 if self.db.setting("player_style", "vinyl") == "vinyl" else 1)

        def set_style(index):
            value = "vinyl" if index == 0 else "circular"
            self.db.set_setting("player_style", value)
            if self.player_dialog:
                self.record.style = value
                self.record.update()

        style.currentIndexChanged.connect(set_style)
        form.addRow("Estilo del reproductor", style)
        sleep = QSpinBox()
        sleep.setRange(0, 240)
        sleep.setSuffix(" min (0 = apagado)")
        if self.player.sleep_deadline:
            sleep.setValue(max(1, round((self.player.sleep_deadline - time.monotonic()) / 60)))
        sleep.valueChanged.connect(self.player.set_sleep)
        form.addRow("Temporizador de sueño", sleep)
        preset = QComboBox()
        preset.addItems(["Personalizado"] + list(PRESETS))
        form.addRow("Ecualizador", preset)
        bands = []
        band_row = QHBoxLayout()
        current = self.db.setting("equalizer", [0] * 5)
        for index, frequency in enumerate(["60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz"]):
            column = QVBoxLayout()
            slider = QSlider(Qt.Orientation.Vertical)
            slider.setRange(-150, 150)
            slider.setValue(round(current[index] * 10))
            slider.setMinimumHeight(120)
            slider.setAccessibleName("Ecualizador " + frequency)
            column.addWidget(slider, alignment=Qt.AlignmentFlag.AlignHCenter)
            value_label = label(f"{current[index]:g} dB", "muted")
            column.addWidget(value_label)
            column.addWidget(label(frequency))
            slider.valueChanged.connect(lambda value, display=value_label: display.setText(f"{value / 10:g} dB"))
            bands.append(slider)
            band_row.addLayout(column)
        form.addRow(band_row)
        loudness = QSpinBox()
        loudness.setRange(0, 12)
        loudness.setSuffix(" dB")
        loudness.setValue(round(self.db.setting("loudness", 0)))
        stereo = QDoubleSpinBox()
        stereo.setRange(0, 2)
        stereo.setSingleStep(0.1)
        stereo.setValue(self.db.setting("stereo", 1))

        def apply_eq():
            try:
                self.player.apply_equalizer([s.value() / 10 for s in bands], loudness.value(), stereo.value())
            except RuntimeError as exc:
                self.notify(f"No se pudo aplicar el ecualizador: {exc}")

        eq_timer = QTimer(dialog)
        eq_timer.setSingleShot(True)
        eq_timer.setInterval(100)
        eq_timer.timeout.connect(apply_eq)
        for slider in bands:
            slider.valueChanged.connect(lambda value: eq_timer.start())
        loudness.valueChanged.connect(apply_eq)
        stereo.valueChanged.connect(apply_eq)

        def set_preset(name):
            if name in PRESETS:
                for slider, value in zip(bands, PRESETS[name]):
                    slider.setValue(round(value * 10))
                apply_eq()

        preset.currentTextChanged.connect(set_preset)
        form.addRow("Ganancia adicional", loudness)
        form.addRow("Amplitud estéreo (1 = original)", stereo)
        spotify_status = label(
            "Cuenta conectada en este equipo" if session_configured() else "Spotify no está conectado",
            "muted",
        )
        spotify_actions = QHBoxLayout()
        connect_spotify_button = QPushButton(
            "Administrar cuenta" if session_configured() else "Iniciar sesión con Spotify"
        )
        connect_spotify_button.setObjectName("primary")
        spotify_actions.addWidget(connect_spotify_button)
        spotify_actions.addStretch()

        def connect_spotify():
            self.spotify_account_dialog()
            connected = session_configured()
            spotify_status.setText(
                "Cuenta conectada en este equipo" if connected else "Spotify no está conectado"
            )
            connect_spotify_button.setText(
                "Administrar cuenta" if connected else "Iniciar sesión con Spotify"
            )

        connect_spotify_button.clicked.connect(connect_spotify)
        form.addRow("Spotify", spotify_actions)
        form.addRow(spotify_status)
        songmirror_help = label(
            "El inicio de sesión se abre dentro de MusicVault. El navegador es temporal y solo la sesión necesaria para SongMirror se guarda en un archivo local, nunca en la base de datos.",
            "muted",
        )
        songmirror_help.setWordWrap(True)
        form.addRow(songmirror_help)
        folder = label(self.db.setting("download_folder", str(data_dir() / "downloads")), "muted")
        folder.setWordWrap(True)

        def choose_folder():
            path = QFileDialog.getExistingDirectory(dialog, "Carpeta de descargas", folder.text())
            if path:
                self.db.set_setting("download_folder", path)
                folder.setText(path)

        form.addRow("Descargas", folder)
        form.addRow(button("Cambiar carpeta de descargas", choose_folder))
        roots = QListWidget()
        roots.setMaximumHeight(100)
        roots.addItems(self.db.setting("library_roots", []))
        form.addRow("Carpetas de biblioteca", roots)

        def forget_root():
            item = roots.takeItem(roots.currentRow())
            if item:
                self.db.set_setting("library_roots", [roots.item(i).text() for i in range(roots.count())])

        form.addRow(button("Dejar de escanear carpeta seleccionada", forget_root))
        auto = QCheckBox("Actualizar las carpetas cada 5 minutos")
        auto.setChecked(self.db.setting("auto_scan", True))
        auto.toggled.connect(lambda value: (self.db.set_setting("auto_scan", value), self.auto_scan.start() if value else self.auto_scan.stop()))
        form.addRow(auto)
        minimum = QSpinBox()
        minimum.setRange(0, 600)
        minimum.setSuffix(" s")
        minimum.setValue(self.db.setting("minimum_duration", 0))
        minimum.valueChanged.connect(lambda value: self.db.set_setting("minimum_duration", value))
        form.addRow("Duración mínima al escanear", minimum)
        prefetch = QCheckBox("Descargar por adelantado la siguiente canción de la cola")
        prefetch.setChecked(self.db.setting("prefetch", True))
        prefetch.toggled.connect(lambda value: self.db.set_setting("prefetch", value))
        form.addRow(prefetch)
        continue_playing = QCheckBox("Continuar con música relacionada de mi biblioteca al terminar la cola")
        continue_playing.setChecked(self.db.setting("auto_continue", False))
        continue_playing.toggled.connect(lambda value: self.db.set_setting("auto_continue", value))
        form.addRow(continue_playing)
        tray = QCheckBox("Seguir reproduciendo en la bandeja al cerrar la ventana")
        tray.setChecked(self.db.setting("close_to_tray", True))
        tray.setEnabled(self.tray is not None)
        tray.toggled.connect(lambda value: self.db.set_setting("close_to_tray", value))
        form.addRow(tray)
        form.addRow(button("Borrar búsquedas recientes y caché de búsqueda", lambda: (self.db.clear_searches(), self.notify("Historial de búsqueda borrado."))))

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

        form.addRow(button("Exportar copia de biblioteca…", backup))
        about = label(f"MusicVault {__version__} · Windows x64\nQt 6 · libmpv · SQLite · yt-dlp · FFmpeg", "muted")
        about.setWordWrap(True)
        form.addRow(about)
        scroll.setWidget(page)
        layout.addWidget(scroll, 1)
        layout.addWidget(button("Listo", dialog.accept, True))
        dialog.exec()

    def quit_app(self):
        self._quitting = True
        self.close()

    def closeEvent(self, event):
        if not self._quitting and self.tray and self.db.setting("close_to_tray", True):
            self.hide()
            event.ignore()
            return
        self.auto_scan.stop()
        self.debounce.stop()
        self.local_debounce.stop()
        self.table.cover_timer.stop()
        if self.tray:
            self.tray.hide()
        for key in list(self.download_jobs):
            self.cancel_download(key)
        self.jobs.close()
        self.cover_jobs.close()
        # Finish cancellation bookkeeping even if no more queued Qt signals are dispatched.
        for key in self.download_jobs:
            track = self.db.get_track(key)
            record = next((r for r in self.db.downloads() if r["track_id"] == key), None)
            if track and record and record["status"] != "complete":
                self.db.set_download(track, "cancelled")
        if self.mpris:
            self.mpris.close()
        self.player.close()
        event.accept()
        QApplication.instance().quit()

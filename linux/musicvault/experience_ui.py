"""Optimized desktop UI surfaces shared by Linux and Windows."""
from __future__ import annotations

from PyQt6.QtCore import Qt, QSortFilterProxyModel, QTimer, QAbstractListModel, QModelIndex, pyqtSignal, QSize, QPoint, QEvent
from PyQt6.QtGui import QStandardItem, QStandardItemModel, QIcon
from PyQt6.QtWidgets import (
    QDialog, QFrame, QGridLayout, QHBoxLayout, QLabel, QLineEdit, QListView,
    QProgressBar, QPushButton, QScrollArea, QStackedWidget, QVBoxLayout, QWidget,
)

from .experience import GENRE_OPTIONS, suggested_artists
from .widgets import icon


def _label(text, object_name=""):
    item = QLabel(text)
    item.setTextFormat(Qt.TextFormat.PlainText)
    if object_name:
        item.setObjectName(object_name)
    return item


def _button(text, callback=None, primary=False):
    item = QPushButton(text)
    if primary:
        item.setObjectName("primary")
    if callback:
        item.clicked.connect(callback)
    return item


class StartupOverlay(QFrame):
    """Solid first-run handoff. It intentionally never reveals Home underneath."""

    MESSAGES = (
        "Buscando canciones que encajen contigo…",
        "Mezclando tus artistas favoritos…",
        "Preparando álbumes para tu bóveda…",
        "Afinando tus recomendaciones…",
    )

    def __init__(self, parent):
        super().__init__(parent)
        self.setObjectName("startupOverlay")
        self.setStyleSheet("#startupOverlay { background: #0F0E0D; }")
        self.setAttribute(Qt.WidgetAttribute.WA_StyledBackground, True)
        self.setFocusPolicy(Qt.FocusPolicy.StrongFocus)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(48, 48, 48, 48)
        layout.addStretch(2)
        mark = QLabel()
        mark.setPixmap(icon("album", "#E0A458", 54).pixmap(54, 54))
        mark.setAlignment(Qt.AlignmentFlag.AlignCenter)
        layout.addWidget(mark)
        title = _label("Preparando tu bóveda", "heading")
        title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        layout.addWidget(title)
        self.message = _label(self.MESSAGES[0], "muted")
        self.message.setAlignment(Qt.AlignmentFlag.AlignCenter)
        layout.addWidget(self.message)
        layout.addSpacing(14)
        self.progress = QProgressBar()
        self.progress.setObjectName("spotifyLoading")
        self.progress.setRange(0, 0)
        self.progress.setTextVisible(False)
        self.progress.setFixedHeight(6)
        self.progress.setMaximumWidth(360)
        layout.addWidget(self.progress, 0, Qt.AlignmentFlag.AlignHCenter)
        layout.addStretch(3)
        self._index = 0
        self._timer = QTimer(self)
        self._timer.setInterval(900)
        self._timer.timeout.connect(self._next_message)

    def _next_message(self):
        self._index = (self._index + 1) % len(self.MESSAGES)
        self.message.setText(self.MESSAGES[self._index])

    def cover_parent(self):
        self.setGeometry(self.parentWidget().rect())
        self.raise_()
        self.show()
        self.setFocus()
        self._timer.start()

    def finish(self):
        self._timer.stop()
        self.hide()
        self.deleteLater()


class OnboardingDialog(QDialog):
    """Instant local suggestions with optional asynchronous artist portraits."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Bienvenido a MusicVault")
        self.setModal(True)
        self.resize(820, 680)
        self.setMinimumSize(680, 560)
        self.selected_genres = set()
        self.selected_artists = set()
        self._prepared_artists = ()
        from .services import Jobs
        self.portrait_jobs = Jobs(self)
        self.portrait_jobs.pool.setMaxThreadCount(3)
        self._portrait_pending = set()
        self._portrait_icons = {}
        self._portrait_failed = set()
        self._portrait_closed = False
        self._catalog_genres = None
        self._catalog_generation = 0
        self._remote_artists = []
        self._artists_loading = False
        self._artists_more = True
        self.artist_catalog = None
        self.catalog_jobs = Jobs(self)
        self.catalog_jobs.pool.setMaxThreadCount(1)
        self.skipped = False
        self.stack = QStackedWidget()
        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.addWidget(self.stack)
        self._build_genres()
        self._build_artists()
        self.portrait_timer = QTimer(self)
        self.portrait_timer.setSingleShot(True)
        self.portrait_timer.setInterval(80)
        self.portrait_timer.timeout.connect(self._load_artist_portraits)
        self.artist_list.verticalScrollBar().valueChanged.connect(lambda _: self.portrait_timer.start())
        self.artist_list.viewport().installEventFilter(self)
        self.artist_proxy.modelReset.connect(self.portrait_timer.start)
        self.artist_search.textChanged.connect(lambda _: self.portrait_timer.start())
        self.finished.connect(self._close_portraits)

    def eventFilter(self, watched, event):
        if event.type() == QEvent.Type.Resize and watched is self.artist_list.viewport():
            self.portrait_timer.start()
        return super().eventFilter(watched, event)

    def _page_shell(self, step, title, subtitle):
        page = QWidget()
        outer = QVBoxLayout(page)
        outer.setContentsMargins(30, 24, 30, 24)
        outer.setSpacing(10)
        eyebrow = _label(f"PRIMERA ESCUCHA  ·  {step}/2", "eyebrow")
        outer.addWidget(eyebrow)
        heading = _label(title, "heading")
        heading.setWordWrap(True)
        outer.addWidget(heading)
        detail = _label(subtitle, "muted")
        detail.setWordWrap(True)
        outer.addWidget(detail)
        return page, outer

    def _build_genres(self):
        page, outer = self._page_shell(
            1, "¿Qué suena en tu bóveda?",
            "Busca entre miles de estilos. Puedes elegir varios y explorar sus artistas en el siguiente paso.",
        )
        from .onboarding_catalog import genre_options
        from .artist_cards import GenreChipDelegate
        self.genre_search = QLineEdit()
        self.genre_search.setPlaceholderText("Buscar entre todos los géneros")
        self.genre_search.addAction(icon("search", "#B3A992"), QLineEdit.ActionPosition.LeadingPosition)
        outer.addWidget(self.genre_search)
        self.genre_model = QStandardItemModel(self)
        for genre in genre_options():
            item = QStandardItem(genre)
            item.setCheckable(True)
            item.setEditable(False)
            item.setToolTip(genre)
            self.genre_model.appendRow(item)
        self.genre_search.setPlaceholderText(f"Buscar entre {self.genre_model.rowCount():,} géneros")
        self.genre_proxy = QSortFilterProxyModel(self)
        self.genre_model.itemChanged.connect(lambda item: self._genre_toggled(item.text(), item.checkState() == Qt.CheckState.Checked))
        self.genre_proxy.setSourceModel(self.genre_model)
        self.genre_proxy.setFilterCaseSensitivity(Qt.CaseSensitivity.CaseInsensitive)
        self.genre_search.textChanged.connect(self.genre_proxy.setFilterFixedString)
        self.genre_list = QListView()
        self.genre_list.setModel(self.genre_proxy)
        self.genre_list.setViewMode(QListView.ViewMode.IconMode)
        self.genre_list.setResizeMode(QListView.ResizeMode.Adjust)
        self.genre_list.setMovement(QListView.Movement.Static)
        self.genre_list.setGridSize(QSize(240, 48))
        self.genre_list.setUniformItemSizes(True)
        self.genre_list.setItemDelegate(GenreChipDelegate(self.genre_list))
        self.genre_list.setEditTriggers(QListView.EditTrigger.NoEditTriggers)
        self.genre_list.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.genre_list.clicked.connect(self._genre_clicked)
        outer.addWidget(self.genre_list, 1)
        controls = QHBoxLayout()
        skip = _button("Configurar después", self._skip)
        skip.setObjectName("textButton")
        controls.addWidget(skip)
        controls.addStretch()
        self.genre_helper = _label("Selecciona al menos un género", "muted")
        controls.addWidget(self.genre_helper)
        self.genre_next = _button("Elegir artistas", self._go_artists, True)
        self.genre_next.setEnabled(False)
        controls.addWidget(self.genre_next)
        outer.addLayout(controls)
        self.stack.addWidget(page)

    def _genre_clicked(self, index):
        item = self.genre_model.itemFromIndex(self.genre_proxy.mapToSource(index))
        checked = item.checkState() != Qt.CheckState.Checked
        item.setCheckState(Qt.CheckState.Checked if checked else Qt.CheckState.Unchecked)

    def _genre_toggled(self, genre, checked):
        if checked:
            self.selected_genres.add(genre)
        else:
            self.selected_genres.discard(genre)
        count = len(self.selected_genres)
        self._prepared_artists = tuple(suggested_artists(self.selected_genres))
        self.genre_next.setEnabled(bool(count))
        self.genre_helper.setText(
            f"{count} {'género seleccionado' if count == 1 else 'géneros seleccionados'}" if count else "Selecciona al menos un género"
        )

    def _build_artists(self):
        page, outer = self._page_shell(
            2, "¿A quién sí escuchas?",
            "Marca varios artistas. Puedes filtrar la lista o escribir uno que no aparezca.",
        )
        self.artist_search = QLineEdit()
        self.artist_search.setPlaceholderText("Buscar o añadir otro artista")
        self.artist_search.addAction(icon("search", "#B3A992"), QLineEdit.ActionPosition.LeadingPosition)
        outer.addWidget(self.artist_search)
        self.artist_model = QStandardItemModel(self)
        self.artist_proxy = QSortFilterProxyModel(self)
        self.artist_proxy.setSourceModel(self.artist_model)
        self.artist_proxy.setFilterCaseSensitivity(Qt.CaseSensitivity.CaseInsensitive)
        self.artist_proxy.setFilterKeyColumn(0)
        self.artist_list = QListView()
        self.artist_list.setModel(self.artist_proxy)
        self.artist_list.setUniformItemSizes(True)
        from .artist_cards import ArtistCardDelegate
        self.artist_list.setViewMode(QListView.ViewMode.IconMode)
        self.artist_list.setResizeMode(QListView.ResizeMode.Adjust)
        self.artist_list.setMovement(QListView.Movement.Static)
        self.artist_list.setGridSize(QSize(144, 150))
        self.artist_list.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.artist_list.setItemDelegate(ArtistCardDelegate(self.artist_list))
        self.artist_list.clicked.connect(self._artist_clicked)
        self.artist_list.setVerticalScrollMode(QListView.ScrollMode.ScrollPerPixel)
        self.artist_list.setEditTriggers(QListView.EditTrigger.NoEditTriggers)
        self.artist_model.itemChanged.connect(lambda item: self._sync_selected_artists())
        outer.addWidget(self.artist_list, 1)
        self.artist_more = _button("Cargar más artistas", self._load_more_artists)
        outer.addWidget(self.artist_more)
        self.artist_list.verticalScrollBar().valueChanged.connect(self._artist_scroll)
        self.artist_search.textChanged.connect(self.artist_proxy.setFilterFixedString)
        self.artist_search.returnPressed.connect(self._add_custom_artist)
        tools = QHBoxLayout()
        back = _button("← Géneros", lambda: self.stack.setCurrentIndex(0))
        tools.addWidget(back)
        add = _button("Añadir escrito", self._add_custom_artist)
        tools.addWidget(add)
        tools.addStretch()
        self.artist_count = _label("0 artistas seleccionados", "muted")
        tools.addWidget(self.artist_count)
        self.artist_done = _button("Preparar mi inicio", self.accept, True)
        self.artist_done.setEnabled(False)
        tools.addWidget(self.artist_done)
        outer.addLayout(tools)
        self.stack.addWidget(page)

    def _go_artists(self):
        genres = tuple(sorted(self.selected_genres))
        if self._catalog_genres != genres:
            from .onboarding_catalog import OnboardingCatalog
            self._catalog_generation += 1
            self._catalog_genres = genres
            self.artist_catalog = OnboardingCatalog(genres)
            self._remote_artists = []
            self._artists_loading = False
            self._artists_more = True
        self._refill_artists()
        self.artist_search.clear()
        self.stack.setCurrentIndex(1)
        self.artist_search.setFocus()
        self.portrait_timer.start()
        self._load_more_artists()

    def _refill_artists(self):
        selected = set(self.selected_artists)
        self.artist_model.clear()
        suggestions = list(dict.fromkeys([*(self._prepared_artists or suggested_artists(self.selected_genres)), *self._remote_artists]))
        for name in suggestions:
            item = QStandardItem(name)
            item.setEditable(False)
            item.setCheckable(True)
            item.setCheckState(Qt.CheckState.Checked if name in selected else Qt.CheckState.Unchecked)
            self.artist_model.appendRow(item)
        for name in sorted(selected - set(suggestions), key=str.casefold):
            item = QStandardItem(name)
            item.setEditable(False)
            item.setCheckable(True)
            item.setCheckState(Qt.CheckState.Checked)
            self.artist_model.appendRow(item)
        self._update_artist_count()
        self._apply_portrait_icons()

    def _artist_scroll(self, value):
        bar = self.artist_list.verticalScrollBar()
        if value > 0 and bar.maximum() > 0 and value >= bar.maximum()-160 and not self.artist_search.text().strip():
            self._load_more_artists()

    def _load_more_artists(self):
        if self._portrait_closed or self._artists_loading or not self._artists_more or self.artist_catalog is None:
            return
        self._artists_loading = True
        self.artist_more.setText("Cargando artistas…")
        self.artist_more.setEnabled(False)
        generation, catalog = self._catalog_generation, self.artist_catalog
        def ready(page):
            if self._portrait_closed or generation != self._catalog_generation:
                return
            names, self._artists_more = page
            self._artists_loading = False
            known = {self.artist_model.item(row).text().casefold() for row in range(self.artist_model.rowCount())}
            for name in names:
                if name.casefold() in known:
                    continue
                known.add(name.casefold())
                self._remote_artists.append(name)
                item = QStandardItem(name)
                item.setEditable(False); item.setCheckable(True)
                item.setCheckState(Qt.CheckState.Checked if name in self.selected_artists else Qt.CheckState.Unchecked)
                self.artist_model.appendRow(item)
            self.artist_more.setText("Cargar más artistas" if self._artists_more else "No hay más artistas en estos géneros")
            self.artist_more.setEnabled(self._artists_more)
            self._apply_portrait_icons()
            self.portrait_timer.start()
        def failed(error):
            if self._portrait_closed or generation != self._catalog_generation:
                return
            self._artists_loading = False
            self.artist_more.setText("Reintentar carga de artistas")
            self.artist_more.setEnabled(True)
        self.catalog_jobs.submit(lambda cancel, progress: catalog.next(cancel), ready, failed)

    def _artist_clicked(self, proxy_index):
        source = self.artist_proxy.mapToSource(proxy_index)
        item = self.artist_model.itemFromIndex(source)
        item.setCheckState(Qt.CheckState.Unchecked if item.checkState() == Qt.CheckState.Checked else Qt.CheckState.Checked)
        self._sync_selected_artists()

    def _apply_portrait_icons(self):
        self.artist_model.blockSignals(True)
        try:
            for row in range(self.artist_model.rowCount()):
                item = self.artist_model.item(row)
                if item.text() in self._portrait_icons:
                    item.setIcon(self._portrait_icons[item.text()])
        finally:
            self.artist_model.blockSignals(False)

    def _load_artist_portraits(self):
        if self._portrait_closed or self.stack.currentIndex() != 1:
            return
        from .artist_portraits import portrait
        viewport = self.artist_list.viewport()
        # The grid stays virtual; fetch the visible rows and one extra row only.
        first = self.artist_list.indexAt(QPoint(8, 8)).row()
        first = max(0, first)
        columns = max(1, viewport.width() // 144)
        count = columns * (viewport.height() // 150 + 2)
        for row in range(first, min(self.artist_proxy.rowCount(), first+count)):
            name = self.artist_proxy.index(row, 0).data()
            if name in self._portrait_pending or name in self._portrait_icons or name in self._portrait_failed:
                continue
            self._portrait_pending.add(name)
            def ready(path, artist=name):
                self._portrait_pending.discard(artist)
                if self._portrait_closed:
                    return
                image = QIcon(path) if path else QIcon()
                if image.isNull():
                    self._portrait_failed.add(artist)
                    return
                self._portrait_icons[artist] = image
                self._apply_portrait_icons()
                self.artist_list.viewport().update()
            self.portrait_jobs.submit(lambda cancel, progress, artist=name: portrait(artist, cancel),
                                      ready, lambda error, artist=name: ready('', artist))

    def _close_portraits(self):
        self._portrait_closed = True
        self.portrait_timer.stop()
        for job in list(self.catalog_jobs.active):
            job.cancel.set()
        for job in list(self.portrait_jobs.active):
            job.cancel.set()

    def _sync_selected_artists(self):
        self.selected_artists = {
            self.artist_model.item(row).text()
            for row in range(self.artist_model.rowCount())
            if self.artist_model.item(row).checkState() == Qt.CheckState.Checked
        }
        self._update_artist_count()

    def _update_artist_count(self):
        count = len(self.selected_artists)
        self.artist_count.setText(f"{count} {'artista seleccionado' if count == 1 else 'artistas seleccionados'}")
        self.artist_done.setEnabled(bool(count))

    def _add_custom_artist(self):
        value = " ".join(self.artist_search.text().split())
        if not value:
            return
        for row in range(self.artist_model.rowCount()):
            item = self.artist_model.item(row)
            if item.text().casefold() == value.casefold():
                item.setCheckState(Qt.CheckState.Checked)
                self.artist_search.clear()
                self._sync_selected_artists()
                return
        item = QStandardItem(value)
        item.setEditable(False)
        item.setCheckable(True)
        item.setCheckState(Qt.CheckState.Checked)
        self.artist_model.appendRow(item)
        self.artist_search.clear()
        self._sync_selected_artists()

    def _skip(self):
        self.skipped = True
        self.accept()


class TrackPickerModel(QAbstractListModel):
    """Lightweight check model: stores tracks directly instead of one QWidget/item per row."""

    TRACK_ROLE = int(Qt.ItemDataRole.UserRole) + 1
    selection_changed = pyqtSignal()

    def __init__(self, tracks, default_checked=True, parent=None):
        super().__init__(parent)
        self.tracks = tuple(tracks)
        self.checked = [bool(default_checked)] * len(self.tracks)

    def rowCount(self, parent=QModelIndex()):
        return 0 if parent.isValid() else len(self.tracks)

    def data(self, index, role=Qt.ItemDataRole.DisplayRole):
        if not index.isValid() or not 0 <= index.row() < len(self.tracks):
            return None
        track = self.tracks[index.row()]
        if role == Qt.ItemDataRole.DisplayRole:
            return f"{track.title}   ·   {track.artist}"
        if role == Qt.ItemDataRole.ToolTipRole:
            return f"{track.title}\n{track.artist} · {track.album}"
        if role == Qt.ItemDataRole.CheckStateRole:
            return Qt.CheckState.Checked if self.checked[index.row()] else Qt.CheckState.Unchecked
        if role == self.TRACK_ROLE:
            return track
        if role == Qt.ItemDataRole.DecorationRole:
            return icon("album", "#B3A992", 28)
        return None

    def flags(self, index):
        if not index.isValid():
            return Qt.ItemFlag.NoItemFlags
        return (Qt.ItemFlag.ItemIsEnabled | Qt.ItemFlag.ItemIsSelectable |
                Qt.ItemFlag.ItemIsUserCheckable)

    def setData(self, index, value, role=Qt.ItemDataRole.EditRole):
        if role != Qt.ItemDataRole.CheckStateRole or not index.isValid():
            return False
        checked = value == Qt.CheckState.Checked or value == int(Qt.CheckState.Checked.value)
        if self.checked[index.row()] == checked:
            return True
        self.checked[index.row()] = checked
        self.dataChanged.emit(index, index, [Qt.ItemDataRole.CheckStateRole])
        self.selection_changed.emit()
        return True

    def set_checked(self, row, checked):
        if 0 <= row < len(self.checked):
            self.setData(self.index(row, 0), Qt.CheckState.Checked if checked else Qt.CheckState.Unchecked,
                         Qt.ItemDataRole.CheckStateRole)

    def selected_tracks(self):
        return tuple(track for track, checked in zip(self.tracks, self.checked) if checked)

    def selected_count(self):
        return sum(self.checked)


class TrackFilterProxy(QSortFilterProxyModel):
    """Case-insensitive local title/artist filtering without rebuilding the source model."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self._needle = ""

    def set_filter_text(self, value):
        self._needle = " ".join((value or "").casefold().split())
        self.invalidateFilter()

    def filterAcceptsRow(self, source_row, source_parent):
        if not self._needle:
            return True
        source = self.sourceModel()
        track = source.data(source.index(source_row, 0, source_parent), TrackPickerModel.TRACK_ROLE)
        if track is None:
            return False
        haystack = f"{track.title} {track.artist}".casefold()
        return all(word in haystack for word in self._needle.split())


class TrackPickerDialog(QDialog):
    """Fast searchable picker used by playlist creation and Spotify imports."""

    TRACK_ROLE = TrackPickerModel.TRACK_ROLE

    def __init__(self, title, tracks, parent=None, action_text="Crear playlist", subtitle="Selecciona las canciones que quieras incluir.", default_checked=True, ask_name=False, initial_name=""):
        super().__init__(parent)
        self.setWindowTitle(title)
        self.resize(700, 640)
        self.setMinimumSize(600, 500)
        self._tracks = tuple(tracks)
        outer = QVBoxLayout(self)
        outer.setContentsMargins(24, 22, 24, 22)
        outer.setSpacing(12)
        heading = _label(title, "section")
        heading.setWordWrap(True)
        outer.addWidget(heading)
        hint = _label(subtitle, "muted")
        hint.setWordWrap(True)
        outer.addWidget(hint)
        self.name_edit = None
        if ask_name:
            self.name_edit = QLineEdit(initial_name)
            self.name_edit.setPlaceholderText("Nombre de la playlist")
            self.name_edit.setClearButtonEnabled(True)
            outer.addWidget(self.name_edit)
        self.search = QLineEdit()
        self.search.setPlaceholderText("Buscar por canción o artista")
        self.search.setClearButtonEnabled(True)
        self.search.addAction(icon("search", "#B3A992"), QLineEdit.ActionPosition.LeadingPosition)
        outer.addWidget(self.search)
        self.model = TrackPickerModel(self._tracks, default_checked=default_checked, parent=self)
        self.proxy = TrackFilterProxy(self)
        self.proxy.setSourceModel(self.model)
        self.proxy.setFilterCaseSensitivity(Qt.CaseSensitivity.CaseInsensitive)
        self.view = QListView()
        self.view.setModel(self.proxy)
        self.view.setUniformItemSizes(True)
        self.view.setVerticalScrollMode(QListView.ScrollMode.ScrollPerPixel)
        self.view.setEditTriggers(QListView.EditTrigger.NoEditTriggers)
        self.model.selection_changed.connect(self._update_counter)
        outer.addWidget(self.view, 1)
        self.search.textChanged.connect(self.proxy.set_filter_text)
        controls = QHBoxLayout()
        controls.addWidget(_button("Seleccionar todo", lambda: self._set_visible(Qt.CheckState.Checked)))
        controls.addWidget(_button("Limpiar visibles", lambda: self._set_visible(Qt.CheckState.Unchecked)))
        controls.addStretch()
        self.counter = _label("", "muted")
        controls.addWidget(self.counter)
        controls.addWidget(_button("Cancelar", self.reject))
        accept = _button(action_text, self._accept_checked, True)
        controls.addWidget(accept)
        outer.addLayout(controls)
        self._update_counter()

    def _toggle_clicked(self, proxy_index):
        source = self.proxy.mapToSource(proxy_index)
        current = self.model.data(source, Qt.ItemDataRole.CheckStateRole)
        target = Qt.CheckState.Unchecked if current == Qt.CheckState.Checked else Qt.CheckState.Checked
        self.model.setData(source, target, Qt.ItemDataRole.CheckStateRole)

    def _set_visible(self, state):
        checked = state == Qt.CheckState.Checked
        source_rows = [self.proxy.mapToSource(self.proxy.index(row, 0)).row()
                       for row in range(self.proxy.rowCount())]
        if not source_rows:
            return
        changed = False
        for row in source_rows:
            if self.model.checked[row] != checked:
                self.model.checked[row] = checked
                changed = True
        if changed:
            self.model.dataChanged.emit(self.model.index(min(source_rows), 0),
                                        self.model.index(max(source_rows), 0),
                                        [Qt.ItemDataRole.CheckStateRole])
            self.model.selection_changed.emit()

    def _update_counter(self):
        self.counter.setText(f"{self.model.selected_count()} seleccionadas")

    def _accept_checked(self):
        if self.name_edit is not None and not self.name_edit.text().strip():
            self.name_edit.setFocus()
            return
        self.accept()

    def selected_tracks(self):
        return self.model.selected_tracks()

    def playlist_name(self):
        return self.name_edit.text().strip() if self.name_edit else ""

"""Optimized desktop UI surfaces shared by Linux and Windows."""
from __future__ import annotations

from PyQt6.QtCore import Qt, QSortFilterProxyModel, QTimer, QAbstractListModel, QModelIndex, pyqtSignal
from PyQt6.QtGui import QStandardItem, QStandardItemModel
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
    """Two-step local-only genre/artist onboarding with an optimized artist list."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Bienvenido a MusicVault")
        self.setModal(True)
        self.resize(820, 680)
        self.setMinimumSize(680, 560)
        self.selected_genres = set()
        self.selected_artists = set()
        self._prepared_artists = ()
        self.skipped = False
        self.stack = QStackedWidget()
        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.addWidget(self.stack)
        self._build_genres()
        self._build_artists()

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
            "Elige uno o varios géneros. La lista de artistas ya vive dentro de MusicVault, así que el siguiente paso aparece sin esperar a Internet.",
        )
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        surface = QWidget()
        grid = QGridLayout(surface)
        grid.setContentsMargins(0, 8, 0, 8)
        grid.setHorizontalSpacing(10)
        grid.setVerticalSpacing(10)
        self.genre_buttons = {}
        for index, genre in enumerate(GENRE_OPTIONS):
            chip = QPushButton(genre)
            chip.setObjectName("chip")
            chip.setCheckable(True)
            chip.setMinimumHeight(42)
            chip.toggled.connect(lambda checked, name=genre: self._genre_toggled(name, checked))
            self.genre_buttons[genre] = chip
            grid.addWidget(chip, index // 3, index % 3)
        scroll.setWidget(surface)
        outer.addWidget(scroll, 1)
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
        self.artist_list.setVerticalScrollMode(QListView.ScrollMode.ScrollPerPixel)
        self.artist_list.setEditTriggers(QListView.EditTrigger.NoEditTriggers)
        self.artist_model.itemChanged.connect(lambda item: self._sync_selected_artists())
        outer.addWidget(self.artist_list, 1)
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
        self._refill_artists()
        self.artist_search.clear()
        self.stack.setCurrentIndex(1)
        self.artist_search.setFocus()

    def _refill_artists(self):
        selected = set(self.selected_artists)
        self.artist_model.clear()
        suggestions = self._prepared_artists or tuple(suggested_artists(self.selected_genres))
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

    def _artist_clicked(self, proxy_index):
        source = self.artist_proxy.mapToSource(proxy_index)
        item = self.artist_model.itemFromIndex(source)
        item.setCheckState(Qt.CheckState.Unchecked if item.checkState() == Qt.CheckState.Checked else Qt.CheckState.Checked)
        self._sync_selected_artists()

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

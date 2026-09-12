"""Virtual track rows: no per-cell widgets or artwork requests outside the viewport."""
from collections import OrderedDict
from pathlib import Path

from PyQt6.QtCore import QAbstractTableModel, QModelIndex, Qt, QSize, QTimer, pyqtSignal, QPoint
from PyQt6.QtGui import QColor, QImageReader, QPixmap
from PyQt6.QtWidgets import QTableView

from .models import clock_text


class TrackModel(QAbstractTableModel):
    headers = ("#", "Título", "Artista", "Álbum", "Duración", "Estado")

    def __init__(self, parent=None):
        super().__init__(parent)
        self.page_scroll = None
        self.tracks, self.rows, self.states = [], {}, {}
        self.current_id = None
        self.covers = OrderedDict()

    def rowCount(self, parent=QModelIndex()):
        return 0 if parent.isValid() else len(self.tracks)

    def columnCount(self, parent=QModelIndex()):
        return 0 if parent.isValid() else len(self.headers)

    def headerData(self, section, orientation, role=Qt.ItemDataRole.DisplayRole):
        if orientation == Qt.Orientation.Horizontal and role == Qt.ItemDataRole.DisplayRole:
            return self.headers[section]

    def data(self, index, role=Qt.ItemDataRole.DisplayRole):
        if not index.isValid() or index.row() >= len(self.tracks):
            return None
        track = self.tracks[index.row()]
        column = index.column()
        if role == Qt.ItemDataRole.DisplayRole:
            return ("▶" if track.id == self.current_id else str(index.row() + 1), track.title,
                    track.artist, track.album, clock_text(track.duration), self.states[track.id][0])[column]
        if role == Qt.ItemDataRole.UserRole:
            return track.id == self.current_id
        if role == Qt.ItemDataRole.DecorationRole and column == 1:
            if track.cover in self.covers:
                self.covers.move_to_end(track.cover)
                return self.covers[track.cover]
        if role == Qt.ItemDataRole.ForegroundRole:
            return QColor(self.states[track.id][1] if column == 5 else
                          "#E0A458" if track.id == self.current_id else "#B3A992")
        if role == Qt.ItemDataRole.ToolTipRole:
            if column == 5:
                return self.states[track.id][2] or self.states[track.id][0]
            return f"{track.title}\n{track.artist} · {track.album}"
        if role == Qt.ItemDataRole.TextAlignmentRole and column in (0, 4):
            return Qt.AlignmentFlag.AlignCenter

    def set_tracks(self, tracks, records, active_downloads, current_id):
        same_order = [t.id for t in tracks] == [t.id for t in self.tracks]
        if not same_order:
            self.beginResetModel()
        self.tracks = tracks
        self.current_id = current_id
        self.rows = {track.id: row for row, track in enumerate(tracks)}
        self.states = {}
        for track in tracks:
            record = records.get(track.id, {})
            local = bool(track.path and Path(track.path).is_file())
            failed = record.get("status") in ("error", "cancelled", "interrupted")
            state = ("En tu biblioteca" if local else
                     f"{record.get('progress', 0)}% · descargando" if track.id in active_downloads else
                     "No descargada" if failed else "En línea" if track.source != "local" else "No disponible")
            self.states[track.id] = (state, "#7E8F6E" if local else "#C96A5E" if failed else "#B3A992", record.get("error", ""))
        if not same_order:
            self.endResetModel()
        elif tracks:
            self.dataChanged.emit(self.index(0, 0), self.index(len(tracks) - 1, 5))

    def set_current(self, track_id):
        previous, self.current_id = self.current_id, track_id
        for key in {previous, track_id}:
            row = self.rows.get(key)
            if row is not None:
                self.dataChanged.emit(self.index(row, 0), self.index(row, 5))

    def set_progress(self, track_id, value):
        row = self.rows.get(track_id)
        if row is not None:
            self.states[track_id] = (f"{value}% · descargando", "#E0A458", "")
            self.dataChanged.emit(self.index(row, 5), self.index(row, 5))

    def set_cover(self, source, path):
        if not path or source in self.covers:
            return
        reader = QImageReader(path)
        reader.setAutoTransform(True)
        size = reader.size()
        if size.isValid():
            reader.setScaledSize(size.scaled(QSize(96, 96), Qt.AspectRatioMode.KeepAspectRatio))
        artwork = QPixmap.fromImage(reader.read())
        if artwork.isNull():
            return
        self.covers[source] = artwork
        while len(self.covers) > 128:
            self.covers.popitem(last=False)
        # Repaint visible cells only; row identity is resolved by data(), even after a search.
        if self.tracks:
            self.dataChanged.emit(self.index(0, 1), self.index(len(self.tracks) - 1, 1), [Qt.ItemDataRole.DecorationRole])


class TrackTable(QTableView):
    visible_rows_changed = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.cover_timer = QTimer(self)
        self.cover_timer.setSingleShot(True)
        self.cover_timer.setInterval(60)
        self.cover_timer.timeout.connect(self.visible_rows_changed)
        self.verticalScrollBar().valueChanged.connect(self.schedule_covers)

    def schedule_covers(self, *_):
        self.cover_timer.start()

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self.schedule_covers()

    def showEvent(self, event):
        super().showEvent(event)
        self.schedule_covers()

    def visible_rows(self):
        if not self.isVisible() or not self.model().rowCount():
            return range(0)
        if self.page_scroll:
            viewport = self.page_scroll.viewport()
            top = self.mapFromGlobal(viewport.mapToGlobal(QPoint(0, 0))).y() - self.horizontalHeader().height()
            bottom = self.mapFromGlobal(viewport.mapToGlobal(QPoint(0, viewport.height()))).y() - self.horizontalHeader().height()
            row_height = self.verticalHeader().defaultSectionSize()
            first = max(0, top // row_height)
            last = min(self.model().rowCount() - 1, max(0, bottom // row_height))
            return range(first, min(self.model().rowCount(), last + 4))
        first = max(0, self.rowAt(0))
        last = self.rowAt(self.viewport().height() - 1)
        if last < 0:
            last = min(self.model().rowCount() - 1, first + self.viewport().height() // 60)
        return range(first, min(self.model().rowCount(), last + 4))

    def wheelEvent(self, event):
        if self.page_scroll:
            delta = event.pixelDelta().y() or event.angleDelta().y()
            if delta:
                bar = self.page_scroll.verticalScrollBar()
                bar.setValue(bar.value() - delta)
            event.accept()
            return
        super().wheelEvent(event)

    def scrollToBottom(self):
        if self.page_scroll:
            bar = self.page_scroll.verticalScrollBar()
            bar.setValue(bar.maximum())
            return
        super().scrollToBottom()

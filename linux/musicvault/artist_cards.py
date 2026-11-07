"""Virtual artist cards: circular portraits, check state and keyboard access."""
from PyQt6.QtCore import Qt, QRectF, QEvent, QSize
from PyQt6.QtGui import QColor, QPainter, QPainterPath, QPen
from PyQt6.QtWidgets import QStyledItemDelegate, QStyle


class ArtistCardDelegate(QStyledItemDelegate):
    def sizeHint(self, option, index):
        return QSize(144, 150)

    def paint(self, painter, option, index):
        painter.save()
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        rect = QRectF(option.rect).adjusted(5, 5, -5, -5)
        checked = index.data(Qt.ItemDataRole.CheckStateRole) == Qt.CheckState.Checked.value
        painter.setPen(QPen(QColor('#E0A458' if checked else '#352F26'), 1))
        painter.setBrush(QColor('#382C1C' if checked else '#1C1915'))
        painter.drawRoundedRect(rect, 10, 10)
        circle = QRectF(rect.center().x()-39, rect.top()+10, 78, 78)
        clip = QPainterPath(); clip.addEllipse(circle)
        painter.save(); painter.setClipPath(clip)
        decoration = index.data(Qt.ItemDataRole.DecorationRole)
        pixmap = decoration.pixmap(160, 160) if decoration else None
        if pixmap is not None and not pixmap.isNull():
            size = min(pixmap.width(), pixmap.height())
            source = QRectF((pixmap.width()-size)/2, (pixmap.height()-size)/2, size, size)
            painter.drawPixmap(circle, pixmap, source)
        else:
            painter.fillRect(circle, QColor('#4A3C2A'))
            painter.setPen(QColor('#F1EAD9'))
            font = painter.font(); font.setPointSize(18); font.setBold(True); painter.setFont(font)
            initials = ''.join(word[0] for word in index.data().split()[:2]).upper()
            painter.drawText(circle, Qt.AlignmentFlag.AlignCenter, initials)
        painter.restore()
        painter.setPen(QColor('#F1EAD9'))
        font = painter.font(); font.setPointSize(10); font.setBold(checked); painter.setFont(font)
        text_rect = QRectF(rect.left()+7, circle.bottom()+6, rect.width()-14, 42)
        painter.drawText(text_rect, Qt.AlignmentFlag.AlignHCenter | Qt.AlignmentFlag.AlignTop | Qt.TextFlag.TextWordWrap, index.data())
        if checked:
            badge = QRectF(circle.right()-16, circle.bottom()-17, 22, 22)
            painter.setPen(Qt.PenStyle.NoPen); painter.setBrush(QColor('#E0A458')); painter.drawEllipse(badge)
            painter.setPen(QColor('#191207')); painter.drawText(badge, Qt.AlignmentFlag.AlignCenter, '✓')
        if option.state & QStyle.StateFlag.State_HasFocus:
            painter.setPen(QPen(QColor('#E0A458'), 1, Qt.PenStyle.DotLine))
            painter.setBrush(Qt.BrushStyle.NoBrush); painter.drawRoundedRect(rect.adjusted(2, 2, -2, -2), 8, 8)
        painter.restore()

    def editorEvent(self, event, model, option, index):
        # Mouse clicks toggle once through the view's clicked signal.
        if event.type() == QEvent.Type.KeyPress and event.key() in (Qt.Key.Key_Space, Qt.Key.Key_Select):
            checked = index.data(Qt.ItemDataRole.CheckStateRole) == Qt.CheckState.Checked.value
            model.setData(index, Qt.CheckState.Unchecked if checked else Qt.CheckState.Checked, Qt.ItemDataRole.CheckStateRole)
            return True
        return False


class GenreChipDelegate(QStyledItemDelegate):
    def sizeHint(self, option, index):
        return QSize(240, 48)

    def paint(self, painter, option, index):
        painter.save(); painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        rect = QRectF(option.rect).adjusted(4, 5, -4, -5)
        checked = index.data(Qt.ItemDataRole.CheckStateRole) == Qt.CheckState.Checked.value
        painter.setPen(QPen(QColor('#E0A458' if checked else '#352F26'), 1))
        painter.setBrush(QColor('#382C1C' if checked else '#1C1915'))
        painter.drawRoundedRect(rect, 18, 18)
        painter.setPen(QColor('#E0A458' if checked else '#F1EAD9'))
        text = option.fontMetrics.elidedText(index.data(), Qt.TextElideMode.ElideRight, int(rect.width())-20)
        painter.drawText(rect, Qt.AlignmentFlag.AlignCenter, text)
        painter.restore()

    def editorEvent(self, event, model, option, index):
        if event.type() == QEvent.Type.KeyPress and event.key() in (Qt.Key.Key_Space, Qt.Key.Key_Select):
            checked = index.data(Qt.ItemDataRole.CheckStateRole) == Qt.CheckState.Checked.value
            model.setData(index, Qt.CheckState.Unchecked if checked else Qt.CheckState.Checked, Qt.ItemDataRole.CheckStateRole)
            return True
        return False

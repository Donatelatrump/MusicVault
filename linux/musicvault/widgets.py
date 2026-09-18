from functools import lru_cache
from pathlib import Path
import math

from PyQt6.QtCore import Qt, QRectF, QTimer, QPointF, QSize
from PyQt6.QtGui import QColor, QPainter, QPen, QPixmap, QFont, QFontDatabase, QIcon, QPainterPath
from PyQt6.QtWidgets import QLabel, QWidget, QStyledItemDelegate, QStyle


def load_fonts():
    for path in (Path(__file__).parent / "assets/fonts").glob("*.ttf"):
        QFontDatabase.addApplicationFont(str(path))


@lru_cache(maxsize=128)
def icon(name, color="#F1EAD9", size=24):
    pixmap = QPixmap(size * 2, size * 2)
    pixmap.fill(Qt.GlobalColor.transparent)
    p = QPainter(pixmap)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    p.scale(size * 2 / 24, size * 2 / 24)
    p.setPen(QPen(QColor(color), 1.7, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap, Qt.PenJoinStyle.RoundJoin))
    p.setBrush(Qt.BrushStyle.NoBrush)
    if name in ("play", "next", "previous"):
        if name == "previous":
            p.translate(24, 0)
            p.scale(-1, 1)
        path = QPainterPath()
        path.moveTo(7, 5); path.lineTo(18, 12); path.lineTo(7, 19); path.closeSubpath()
        p.fillPath(path, QColor(color))
        if name != "play":
            p.drawLine(19, 5, 19, 19)
    elif name == "pause":
        p.fillRect(QRectF(6, 5, 4, 14), QColor(color)); p.fillRect(QRectF(14, 5, 4, 14), QColor(color))
    elif name == "heart":
        path = QPainterPath(QPointF(12, 20))
        path.cubicTo(9, 17, 3, 13, 3, 8); path.cubicTo(3, 2, 10, 2, 12, 7)
        path.cubicTo(14, 2, 21, 2, 21, 8); path.cubicTo(21, 13, 15, 17, 12, 20)
        p.drawPath(path)
    elif name == "search":
        p.drawEllipse(QRectF(4, 3, 12, 12)); p.drawLine(14, 14, 21, 21)
    elif name == "user":
        p.drawEllipse(QRectF(8, 3, 8, 8))
        p.drawArc(QRectF(4, 12, 16, 10), 0, 180 * 16)
    elif name == "home":
        path = QPainterPath(QPointF(3, 11)); path.lineTo(12, 3); path.lineTo(21, 11)
        path.moveTo(5, 9); path.lineTo(5, 21); path.lineTo(10, 21); path.lineTo(10, 15)
        path.lineTo(14, 15); path.lineTo(14, 21); path.lineTo(19, 21); path.lineTo(19, 9)
        p.drawPath(path)
    elif name in ("album", "library"):
        p.drawRoundedRect(QRectF(4, 4, 16, 16), 2, 2)
        if name == "album":
            p.drawEllipse(QRectF(8, 8, 8, 8)); p.drawEllipse(QRectF(11, 11, 2, 2))
        else:
            p.drawLine(8, 8, 8, 16); p.drawLine(12, 8, 12, 16); p.drawLine(15, 8, 17, 16)
    elif name in ("download", "down"):
        p.drawLine(12, 3, 12, 15); p.drawLine(7, 10, 12, 15); p.drawLine(12, 15, 17, 10)
        if name == "download":
            p.drawLine(4, 17, 4, 21); p.drawLine(4, 21, 20, 21); p.drawLine(20, 21, 20, 17)
    elif name in ("shuffle", "repeat"):
        path = QPainterPath(QPointF(3, 7))
        if name == "shuffle":
            path.cubicTo(12, 7, 12, 17, 21, 17); path.moveTo(3, 17); path.cubicTo(12, 17, 12, 7, 21, 7)
        else:
            path.lineTo(17, 7); path.cubicTo(23, 7, 23, 17, 17, 17); path.lineTo(4, 17)
            path.moveTo(6, 17); path.cubicTo(0, 17, 0, 7, 6, 7)
        p.drawPath(path)
        p.drawLine(17, 3, 21, 7); p.drawLine(21, 7, 17, 11)
        if name == "shuffle":
            p.drawLine(17, 13, 21, 17); p.drawLine(21, 17, 17, 21)
    elif name == "settings":
        for x, y in ((5, 8), (12, 16), (19, 10)):
            p.drawLine(x, 3, x, 21); p.setBrush(QColor("#1E1B18")); p.drawEllipse(QRectF(x-2.5, y-2.5, 5, 5))
    elif name == "volume":
        path = QPainterPath(QPointF(3, 9))
        path.lineTo(7, 9); path.lineTo(12, 5); path.lineTo(12, 19)
        path.lineTo(7, 15); path.lineTo(3, 15); path.closeSubpath()
        p.drawPath(path)
        p.drawArc(QRectF(10, 6, 9, 12), -70 * 16, 140 * 16)
        p.drawArc(QRectF(9, 2, 14, 20), -60 * 16, 120 * 16)
    elif name == "refresh":
        p.drawArc(QRectF(4, 4, 16, 16), 40 * 16, 290 * 16)
        p.drawLine(20, 3, 20, 9); p.drawLine(14, 9, 20, 9)
    elif name == "queue":
        for y in (6, 12, 18):
            p.drawLine(7, y, 21, y); p.drawPoint(3, y)
    elif name == "more":
        p.setBrush(QColor(color))
        for y in (5, 12, 19): p.drawEllipse(QRectF(11, y-1, 2, 2))
    elif name == "plus":
        p.drawLine(12, 5, 12, 19); p.drawLine(5, 12, 19, 12)
    elif name == "close":
        p.drawLine(6, 6, 18, 18); p.drawLine(6, 18, 18, 6)
    elif name == "trash":
        p.drawRoundedRect(QRectF(7, 8, 10, 12), 1.5, 1.5)
        p.drawLine(5, 7, 19, 7); p.drawLine(9, 4, 15, 4); p.drawLine(10, 11, 10, 17); p.drawLine(14, 11, 14, 17)
    elif name == "fullscreen":
        for x, y, dx, dy in ((4,4,5,5),(20,4,-5,5),(4,20,5,-5),(20,20,-5,-5)):
            p.drawLine(x,y,x+dx,y); p.drawLine(x,y,x,y+dy)
    p.end()
    pixmap.setDevicePixelRatio(2)
    return QIcon(pixmap)

STYLE = """
QWidget { background: transparent; color: #F1EAD9; font-family: 'Inter'; font-size: 13px; }
QMainWindow, QDialog { background: #0F0E0D; }
QLabel { background: transparent; }
QLabel#eyebrow { color: #E0A458; font-family: 'IBM Plex Mono'; font-size: 11px; letter-spacing: 1px; }
QLabel#heading { font-family: 'Space Grotesk'; font-size: 34px; font-weight: 700; letter-spacing: -0.5px; }
QLabel#section { font-family: 'Space Grotesk'; font-size: 21px; font-weight: 650; letter-spacing: -0.25px; }
QLabel#brand { font-family: 'Space Grotesk'; font-size: 21px; font-weight: 700; }
QLabel#muted { color: #B3A992; }
QLabel#mono { color: #7E7666; font-family: 'IBM Plex Mono'; font-size: 11px; }
QFrame#navigation { background: #171514; border-top: 1px solid #322C25; }
QFrame#message { background: #292420; border: 1px solid #7a4a32; border-radius: 10px; }
QFrame#message QLabel { color: #D96A55; }
QFrame#clipboardPrompt { background: #29251F; border: 1px solid #74522D; border-radius: 10px; }
QFrame#clipboardPrompt QLabel { color: #F1EAD9; }
QFrame#card { background: #201D1A; border: none; border-radius: 8px; }
QFrame#card:hover { background: #302A24; }
QFrame#sidebar { background: #171514; border: none; border-radius: 10px; }
QFrame#mainPanel { background: qlineargradient(x1:0,y1:0,x2:0,y2:1, stop:0 #332A21, stop:0.19 #211D19, stop:0.55 #191715, stop:1 #171514); border-radius: 10px; }
QFrame#emptyPanel { background: #211E1A; border: 1px solid #382F26; border-radius: 10px; }
QLabel#trackTitle { font-weight: 600; }
QListWidget#sidebarCollections::item { border: none; padding: 8px 6px; border-radius: 5px; color: #B3A992; }
QListWidget#sidebarCollections::item:hover { background: #292420; color: #F1EAD9; }
QPushButton#textButton { background: transparent; border: none; color: #B3A992; font-size: 11px; padding: 3px; }
QPushButton#textButton:hover { color: #F1EAD9; }
QPushButton#albumCover { background: #292420; border: none; border-radius: 5px; padding: 0; }
QPushButton#albumCover:hover { border: 2px solid #E0A458; }
QLineEdit#globalSearch { background: #2B2722; border: 1px solid #514433; border-radius: 20px; padding: 11px 16px; font-size: 13px; }
QLineEdit#globalSearch:hover { border-color: #776043; }
QLineEdit#globalSearch:focus { background: #302B25; border: 1px solid #E0A458; }
QProgressBar#spotifyLoading { background: #3A332B; border: none; border-radius: 3px; }
QProgressBar#spotifyLoading::chunk { background: #E0A458; border-radius: 3px; }

QFrame#transport { background: #0F0E0D; border: none; border-top: 1px solid #28221C; }
QPushButton { background: #28231d; border: 1px solid #3b332a; border-radius: 7px; padding: 9px 14px; }
QPushButton:hover { background: #3a3023; border-color: #b97f35; }
QPushButton:pressed { background: #534029; }
QPushButton:checked { background: #3a2e1d; color: #e0a458; border-color: #74522d; }
QPushButton:disabled { color: #746c61; border-color: #29251f; }
QPushButton#primary { background: #e0a458; color: #19130b; border: none; font-weight: 700; }
QPushButton#nav { padding: 10px 12px; border: none; background: transparent; color: #B3A992; text-align: left; border-radius: 5px; font-weight: 650; }
QPushButton#nav:hover { background: #292420; color: #F1EAD9; }
QPushButton#nav:checked { background: #382D20; color: #F1EAD9; border: none; }
QPushButton#icon { background: transparent; border: none; padding: 8px; border-radius: 20px; }
QPushButton#icon:hover { background: #292420; }
QPushButton#playCircle { background: #E0A458; border: none; border-radius: 19px; padding: 0; }
QFrame#transport QPushButton#playCircle { background: #E0A458; border: none; border-radius: 19px; }
QPushButton#fullPlay { background: #E0A458; border: none; border-radius: 32px; padding: 0; }
QPushButton#chip { background: #1E1B18; border: 1px solid #322C25; border-radius: 17px; padding: 7px 16px; }
QPushButton#chip:checked { background: #3A2E1D; color: #E0A458; border-color: #E0A458; }
QLineEdit, QSpinBox, QDoubleSpinBox, QComboBox { background: #211e1a; border: 1px solid #40382d; border-radius: 6px; padding: 9px; selection-background-color: #8c622e; }
QLineEdit:focus { border-color: #e0a458; }
QTableView, QListWidget, QListView, QTextBrowser { background: transparent; border: none; alternate-background-color: #121110; }
QTableView::item, QListWidget::item, QListView::item { padding: 8px; border: none; }
QTableView::item:selected, QListWidget::item:selected, QListView::item:selected { background: #493520; color: #ffe2b9; }
QHeaderView::section { background: transparent; color: #B3A992; border: none; border-right: 1px solid #2F2923; border-bottom: 1px solid #3A332B; padding: 8px 6px; font-size: 11px; font-weight: 500; }
QFrame#filterBar { background: #1D1A17; border: 1px solid #382F26; border-radius: 8px; }
QFrame#searchTabs { background: #1A1715; border: 1px solid #382F26; border-radius: 9px; }
QFrame#searchTabs QPushButton { border: none; border-radius: 7px; background: transparent; color: #9B9284; padding: 8px 18px; }
QFrame#searchTabs QPushButton:checked { background: #3A2E1D; color: #F1EAD9; }
QTableView::item:hover { background: #2C2721; }
QSlider::groove:horizontal { height: 5px; background: #4a3c2b; border-radius: 2px; }
QSlider::sub-page:horizontal { background: #e0a458; border-radius: 2px; }
QSlider::handle:horizontal { width: 13px; margin: -5px 0; border-radius: 6px; background: #f6c98d; }
QSlider::groove:vertical { width: 5px; background: #4a3c2b; }
QSlider::handle:vertical { height: 14px; margin: 0 -5px; border-radius: 6px; background: #e0a458; }
QMenu { background: #26211b; border: 1px solid #5c4730; padding: 6px; }
QMenu::item { padding: 8px 22px; }
QMenu::item:selected { background: #4e3921; }
/* Spotify usa desplazamiento directo; las barras no forman parte de la UI. */
QScrollBar:vertical, QScrollBar:horizontal { background: transparent; width: 0px; height: 0px; margin: 0; }
QScrollBar::handle:vertical, QScrollBar::handle:horizontal { background: transparent; }
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical,
QScrollBar::add-line:horizontal, QScrollBar::sub-line:horizontal,
QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical,
QScrollBar::add-page:horizontal, QScrollBar::sub-page:horizontal { background: transparent; border: none; }
QTabWidget::pane { border: 1px solid #3b332a; }
QTabBar::tab { background: #211d18; padding: 10px 20px; }
QTabBar::tab:selected { color: #e0a458; border-bottom: 2px solid #e0a458; }
QToolTip { color: #f0e9df; background: #30261c; border: 1px solid #74522d; }
QStatusBar { color: #B3A992; font-size: 10px; background: #121110; }
QScrollArea, QScrollArea > QWidget > QWidget { border: none; background: transparent; }
"""


class ElidedLabel(QLabel):
    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setFont(self.font())
        painter.setPen(self.palette().color(self.foregroundRole()))
        painter.drawText(self.contentsRect(), Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter,
                         self.fontMetrics().elidedText(self.text(), Qt.TextElideMode.ElideRight, self.contentsRect().width()))


class TrackDelegate(QStyledItemDelegate):
    def paint(self, painter, option, index):
        painter.save()
        if option.state & QStyle.StateFlag.State_Selected:
            painter.fillRect(option.rect, QColor("#493520"))
        elif option.state & QStyle.StateFlag.State_MouseOver:
            painter.fillRect(option.rect, QColor("#2C2721"))
        x, y = option.rect.x() + 6, option.rect.y()
        artwork = index.data(Qt.ItemDataRole.DecorationRole)
        target = QRectF(x, y + 8, 44, 44)
        clip = QPainterPath()
        clip.addRoundedRect(target, 4, 4)
        painter.setClipPath(clip)
        if artwork is not None:
            edge = min(artwork.width(), artwork.height())
            painter.drawPixmap(target, artwork, QRectF((artwork.width()-edge)/2, (artwork.height()-edge)/2, edge, edge))
        else:
            painter.fillRect(target, QColor("#3A3026"))
            icon("album", "#B3A992", 28).paint(painter, x+8, y+16, 28, 28)
        painter.setClipping(False)
        x += 58
        width = max(0, option.rect.right() - x - 8)
        show_artist = not option.widget or option.widget.isColumnHidden(2)
        painter.setFont(QFont("Inter", 10, QFont.Weight.DemiBold))
        painter.setPen(QColor("#E0A458" if index.data(Qt.ItemDataRole.UserRole) else "#F1EAD9"))
        painter.drawText(QRectF(x, y + (10 if show_artist else 19), width, 21), Qt.AlignmentFlag.AlignVCenter,
                         painter.fontMetrics().elidedText(index.data() or "", Qt.TextElideMode.ElideRight, width))
        if show_artist:
            painter.setFont(QFont("Inter", 9))
            painter.setPen(QColor("#B3A992"))
            painter.drawText(QRectF(x, y+31, width, 19), Qt.AlignmentFlag.AlignVCenter,
                             painter.fontMetrics().elidedText(index.siblingAtColumn(2).data() or "", Qt.TextElideMode.ElideRight, width))
        painter.restore()


class StatusDelegate(QStyledItemDelegate):
    """Text status plus the same thin download bar used across the app."""
    def paint(self, painter, option, index):
        painter.save()
        if option.state & QStyle.StateFlag.State_Selected:
            painter.fillRect(option.rect, QColor("#493520"))
        elif option.state & QStyle.StateFlag.State_MouseOver:
            painter.fillRect(option.rect, QColor("#2C2721"))
        model = index.model()
        downloading_role = getattr(model, "DOWNLOADING_ROLE", int(Qt.ItemDataRole.UserRole) + 21)
        progress_role = getattr(model, "DOWNLOAD_PROGRESS_ROLE", int(Qt.ItemDataRole.UserRole) + 20)
        downloading = bool(index.data(downloading_role))
        progress = max(0, min(100, int(index.data(progress_role) or 0)))
        text_rect = QRectF(option.rect.x() + 8, option.rect.y() + 7, max(0, option.rect.width() - 16), option.rect.height() - 16)
        painter.setFont(QFont("Inter", 9))
        painter.setPen(index.data(Qt.ItemDataRole.ForegroundRole) or QColor("#B3A992"))
        painter.drawText(text_rect, Qt.AlignmentFlag.AlignVCenter | Qt.AlignmentFlag.AlignLeft, index.data() or "")
        if downloading:
            x = option.rect.x() + 8
            y = option.rect.bottom() - 6
            width = max(0, option.rect.width() - 16)
            painter.setPen(Qt.PenStyle.NoPen)
            painter.setBrush(QColor("#3A332B"))
            painter.drawRoundedRect(QRectF(x, y, width, 3), 1.5, 1.5)
            painter.setBrush(QColor("#E0A458"))
            painter.drawRoundedRect(QRectF(x, y, width * (progress / 100.0), 3), 1.5, 1.5)
        painter.restore()


class DownloadItemDelegate(QStyledItemDelegate):
    """Adds a thin determinate bar to active download rows."""
    def paint(self, painter, option, index):
        super().paint(painter, option, index)
        record = index.data(Qt.ItemDataRole.UserRole) or {}
        if record.get("status") not in ("queued", "downloading"):
            return
        progress = max(0, min(100, int(record.get("progress", 0) or 0)))
        painter.save()
        x, y = option.rect.x() + 44, option.rect.bottom() - 5
        width = max(0, option.rect.width() - 56)
        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(QColor("#3A332B"))
        painter.drawRoundedRect(QRectF(x, y, width, 3), 1.5, 1.5)
        painter.setBrush(QColor("#E0A458"))
        painter.drawRoundedRect(QRectF(x, y, width * progress / 100.0, 3), 1.5, 1.5)
        painter.restore()


class Cover(QLabel):
    def __init__(self, size=64, parent=None):
        super().__init__(parent)
        self.setFixedSize(size, size)
        self.size = size
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setStyleSheet("background: #3a2e1d; color: #e0a458; border-radius: 8px; font-size: 26px;")
        self.setText("♪")

    def load(self, path):
        pixmap = QPixmap(path) if path and Path(path).is_file() else QPixmap()
        if pixmap.isNull():
            self.clear()
            self.setText("♪")
        else:
            self.setPixmap(pixmap.scaled(self.size, self.size, Qt.AspectRatioMode.KeepAspectRatioByExpanding,
                                         Qt.TransformationMode.SmoothTransformation))


class Record(QWidget):
    """The original HV-01 turntable: artwork platter, tonearm, speed keys and RPM fader."""
    def __init__(self, player, style="vinyl", parent=None):
        super().__init__(parent)
        self.player, self.style = player, style
        self.angle, self.rpm, self.arm = 0.0, 33, 1.0
        self.cover = QPixmap()
        self.setMinimumSize(300, 300)
        self.setMouseTracking(True)
        self.timer = QTimer(self)
        self.timer.timeout.connect(self.advance)
        self.timer.setInterval(33)
        self.player.changed.connect(self.sync_animation)
        self.targets = []
        self.fader = QRectF()
        self.dragging = False

    def sync_animation(self):
        target = 0 if self.player.playing else 1
        if self.isVisible() and (self.player.playing or abs(target - self.arm) > 0.001):
            self.timer.start()
        else:
            self.timer.stop()
        self.update()

    def showEvent(self, event):
        super().showEvent(event)
        self.sync_animation()

    def hideEvent(self, event):
        self.timer.stop()
        super().hideEvent(event)

    def advance(self):
        if not self.isVisible():
            return
        target = 0 if self.player.playing else 1
        if self.player.playing:
            self.angle = (self.angle + self.rpm * 360 / 60 * 0.033) % 360
        self.arm += (target - self.arm) * 0.12
        if self.player.playing or abs(target-self.arm) > 0.001:
            self.update()
        else:
            self.timer.stop()

    def paintEvent(self, event):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        side = min(self.width(), self.height()) - 20
        origin = QPointF((self.width()-side)/2, (self.height()-side)/2)
        p.translate(origin)
        scale = side/420
        p.scale(scale, scale)
        self.origin, self.scale = origin, scale
        self.targets = []
        if self.style == "circular":
            for i in range(144):
                a = i * math.tau / 144
                length = 8 + 14*(1+math.sin(i*.7+math.radians(self.angle))) if self.player.playing else 8
                p.setPen(QPen(QColor("#E0A458" if i%3 else "#B97F35"), 2))
                p.drawLine(QPointF(210+math.cos(a)*161, 210+math.sin(a)*161),
                           QPointF(210+math.cos(a)*(161+length), 210+math.sin(a)*(161+length)))
            self._platter(p, QPointF(210,210), 140, False)
            p.end()
            return
        p.setBrush(QColor("#1E1B18")); p.setPen(QPen(QColor("#322C25"), 1))
        p.drawRoundedRect(QRectF(0,0,420,420),20,20)
        p.setBrush(QColor("#292420")); p.drawEllipse(QRectF(14,14,26,26))
        p.setPen(QPen(QColor("#7E7666"),1.5)); p.drawArc(QRectF(21,21,12,12),-45*16,270*16); p.drawLine(27,19,27,26)
        p.setPen(Qt.PenStyle.NoPen); p.setBrush(QColor("#7E8F6E" if self.player.playing else "#363028")); p.drawEllipse(QRectF(47,25,5,5))
        p.setPen(QColor("#7E7666")); p.setFont(QFont("IBM Plex Mono",9)); p.drawText(QRectF(61,16,300,23),Qt.AlignmentFlag.AlignVCenter,"VAULT HV-01  ·  SIDE A")
        center, radius = QPointF(151,210), 134
        p.setBrush(QColor("#26221E")); p.setPen(QPen(QColor("#141210"),1)); p.drawEllipse(center,radius+7,radius+7)
        self._platter(p,center,radius,True)
        # Pivot and cartridge geometry follows the original deck's parked/playing arm.
        pivot = QPointF(306,98)
        angle = math.radians(136 - 40*self.arm)
        end = pivot+QPointF(math.cos(angle)*108, math.sin(angle)*108)
        p.setPen(QPen(QColor("#10100F"),8)); p.drawLine(pivot+QPointF(3,4),end+QPointF(3,4))
        p.setPen(QPen(QColor("#7E7666"),6)); p.drawLine(pivot,end)
        p.setPen(QPen(QColor("#B3A992"),2)); p.drawLine(pivot,end)
        p.setBrush(QColor("#363028")); p.setPen(QPen(QColor("#7E7666"),1)); p.drawEllipse(pivot,16,16)
        p.setBrush(QColor("#1E1B18")); p.drawEllipse(pivot,8,8)
        p.save(); p.translate(end); p.rotate(math.degrees(angle)-90)
        p.setBrush(QColor("#292420")); p.setPen(QPen(QColor("#B97F35"),1)); p.drawRoundedRect(QRectF(-7,-4,14,25),3,3)
        p.setPen(QPen(QColor("#E0A458"),2)); p.drawLine(0,20,0,26); p.restore()
        for i,speed in enumerate((33,45,78)):
            rect=QRectF(366,137+i*31,36,25)
            selected=abs(self.rpm-speed)<1.5
            p.setBrush(QColor("#3A2E1D" if selected else "#292420")); p.setPen(QPen(QColor("#E0A458" if selected else "#322C25"),1))
            p.drawRoundedRect(rect,7,7); p.setPen(QColor("#E0A458" if selected else "#7E7666"))
            p.setFont(QFont("IBM Plex Mono",10)); p.drawText(rect,Qt.AlignmentFlag.AlignCenter,str(speed))
            self.targets.append((rect,lambda speed=speed:self.set_rpm(speed)))
        for i,(name,action) in enumerate((("previous",self.player.previous),("pause" if self.player.playing else "play",self.player.toggle),("next",self.player.next))):
            rect=QRectF(366,244+i*31,36,25)
            p.setBrush(QColor("#292420")); p.setPen(QPen(QColor("#322C25"),1)); p.drawRoundedRect(rect,5,5)
            icon(name,"#E0A458" if i==1 else "#7E7666",16).paint(p,375,int(rect.y()+4),18,18)
            self.targets.append((rect,action))
        self.fader=QRectF(22,369,282,30)
        p.setPen(QPen(QColor("#141210"),8,Qt.PenStyle.SolidLine,Qt.PenCapStyle.RoundCap)); p.drawLine(22,384,304,384)
        p.setPen(QPen(QColor("#363028"),1))
        for speed in (33,45,78):
            x=22+(speed-16)/62*282; p.drawLine(QPointF(x,377),QPointF(x,391))
        x=22+(self.rpm-16)/62*282
        p.setPen(QPen(QColor("#B97F35"),3)); p.drawLine(QPointF(22,384),QPointF(x,384))
        p.setPen(Qt.PenStyle.NoPen); p.setBrush(QColor("#E0A458")); p.drawEllipse(QPointF(x,384),6,6)
        p.setBrush(QColor("#141210")); p.drawEllipse(QPointF(x,384),2,2)
        p.setPen(QColor("#E0A458")); p.setFont(QFont("IBM Plex Mono",11)); p.drawText(QRectF(319,369,89,30),Qt.AlignmentFlag.AlignCenter,f"{round(self.rpm)} RPM")
        p.end()

    def _platter(self,p,center,radius,spin):
        p.save(); p.translate(center)
        if spin:p.rotate(self.angle)
        circle=QRectF(-radius,-radius,radius*2,radius*2)
        clip=QPainterPath(); clip.addEllipse(circle)
        p.setClipPath(clip)
        if not self.cover.isNull():
            source=self.cover.rect(); edge=min(source.width(),source.height())
            crop=QRectF((source.width()-edge)/2,(source.height()-edge)/2,edge,edge)
            p.drawPixmap(circle,self.cover,crop)
        else:
            p.fillRect(circle,QColor("#141210"))
            p.setPen(QPen(QColor("#292420"),1)); p.setBrush(Qt.BrushStyle.NoBrush)
            for r in range(24,int(radius),5):p.drawEllipse(QPointF(0,0),r,r)
            p.setBrush(QColor("#3A2E1D")); p.setPen(Qt.PenStyle.NoPen); p.drawEllipse(QPointF(0,0),radius*.32,radius*.32)
            p.setPen(QColor("#E0A458")); p.setFont(QFont("IBM Plex Mono",9)); p.drawText(QRectF(-50,-24,100,48),Qt.AlignmentFlag.AlignCenter,"MUSIC\nVAULT")
        p.setClipping(False); p.setBrush(Qt.BrushStyle.NoBrush); p.setPen(QPen(QColor("#363028"),2)); p.drawEllipse(circle)
        if spin:
            p.setBrush(QColor("#121110")); p.setPen(QPen(QColor("#B3A992"),1)); p.drawEllipse(QPointF(0,0),5,5)
        p.restore()

    def set_rpm(self,value):
        self.rpm=max(16,min(78,value)); self.player.db.set_setting("rpm",self.rpm); self.update()

    def mousePressEvent(self,event):
        if self.style != "vinyl" or not hasattr(self,"origin"):
            return
        position=(event.position()-self.origin)/self.scale
        for rect,action in self.targets:
            if rect.contains(position):action();self.update();return
        if self.fader.contains(position):
            self.dragging=True; self.set_rpm(16+(position.x()-22)/282*62)

    def mouseMoveEvent(self,event):
        if self.dragging:self.set_rpm(16+((event.position()-self.origin).x()/self.scale-22)/282*62)

    def mouseReleaseEvent(self,event):
        self.dragging=False

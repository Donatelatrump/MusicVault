"""Private, in-app Spotify sign-in used by the embedded SongMirror reader."""

from PyQt6.QtCore import QUrl, pyqtSignal
from PyQt6.QtWebEngineCore import QWebEnginePage, QWebEngineProfile
from PyQt6.QtWebEngineWidgets import QWebEngineView
from PyQt6.QtWidgets import (
    QDialog, QFrame, QHBoxLayout, QLabel, QPushButton, QVBoxLayout,
)

from .songmirror_spotify import captured_session_value


_SPOTIFY_WEB = "https://open.spotify.com/"


class _LoginPage(QWebEnginePage):
    """Keep sign-in popups in the temporary login surface."""

    def createWindow(self, _window_type):
        return self


class SpotifyLoginDialog(QDialog):
    """Capture only Spotify's SongMirror session cookie from an ephemeral profile."""

    session_captured = pyqtSignal(str)
    disconnect_requested = pyqtSignal()

    def __init__(self, parent=None, connected=False):
        super().__init__(parent)
        self.setWindowTitle("Cuenta de Spotify para SongMirror")
        self.resize(660, 360)
        self.setMinimumSize(620, 340)
        self._connected = bool(connected)
        self._checking = False
        self._seen_values = set()

        outer = QVBoxLayout(self)
        outer.setContentsMargins(22, 20, 22, 20)
        outer.setSpacing(12)

        eyebrow = QLabel("SONGMIRROR · SOLO LECTURA")
        eyebrow.setObjectName("eyebrow")
        outer.addWidget(eyebrow)
        heading = QLabel("Conectar Spotify")
        heading.setObjectName("section")
        outer.addWidget(heading)
        explanation = QLabel(
            "Inicia sesión dentro de MusicVault. El navegador es temporal: no guarda tu "
            "contraseña ni su historial y, al terminar, MusicVault conserva únicamente la "
            "cookie de sesión que necesita el lector de playlists."
        )
        explanation.setObjectName("muted")
        explanation.setWordWrap(True)
        outer.addWidget(explanation)

        self.status_panel = QFrame()
        self.status_panel.setObjectName("emptyPanel")
        status_layout = QVBoxLayout(self.status_panel)
        status_layout.setContentsMargins(16, 14, 16, 14)
        status_layout.setSpacing(4)
        self.status = QLabel()
        self.status.setObjectName("trackTitle")
        self.detail = QLabel()
        self.detail.setObjectName("muted")
        self.detail.setWordWrap(True)
        status_layout.addWidget(self.status)
        status_layout.addWidget(self.detail)
        outer.addWidget(self.status_panel)

        # An unnamed profile is off-the-record. Cookies and cache therefore live
        # only for the lifetime of this dialog and never reach the user's disk.
        # Create the view first so Qt destroys its page before the sibling
        # profile when the dialog itself is torn down.
        self.browser = QWebEngineView(self)
        self.profile = QWebEngineProfile(self)
        self.profile.setHttpCacheType(QWebEngineProfile.HttpCacheType.MemoryHttpCache)
        self.profile.setPersistentCookiesPolicy(
            QWebEngineProfile.PersistentCookiesPolicy.NoPersistentCookies
        )
        self.profile.cookieStore().cookieAdded.connect(self._cookie_added)
        self.browser.setPage(_LoginPage(self.profile, self.browser))
        self.browser.setMinimumHeight(460)
        self.browser.hide()
        outer.addWidget(self.browser, 1)

        actions = QHBoxLayout()
        self.disconnect_button = QPushButton("Desconectar")
        self.disconnect_button.clicked.connect(self._disconnect)
        self.disconnect_button.setEnabled(self._connected)
        close_button = QPushButton("Cerrar")
        close_button.clicked.connect(self.reject)
        self.login_button = QPushButton(
            "Usar otra cuenta" if self._connected else "Iniciar sesión con Spotify"
        )
        self.login_button.setObjectName("primary")
        self.login_button.clicked.connect(self.start_login)
        actions.addWidget(self.disconnect_button)
        actions.addStretch()
        actions.addWidget(close_button)
        actions.addWidget(self.login_button)
        outer.addLayout(actions)
        self._show_account_state()

        self.browser.loadStarted.connect(self._load_started)
        self.browser.loadFinished.connect(self._load_finished)
        self.browser.renderProcessTerminated.connect(self._render_failed)

    def _show_account_state(self):
        if self._connected:
            self.status.setText("Cuenta conectada")
            self.detail.setText("La sesión está guardada de forma privada en este equipo.")
        else:
            self.status.setText("Spotify no está conectado")
            self.detail.setText("Inicia sesión para importar playlists privadas o públicas.")

    def start_login(self):
        self._checking = False
        self._seen_values.clear()
        self.status.setText("Abriendo Spotify…")
        self.detail.setText("Completa el inicio de sesión en esta ventana.")
        self.login_button.setEnabled(False)
        self.browser.show()
        self.resize(1000, 760)
        if self._connected:
            # "Usar otra cuenta" must not silently reopen the account that was
            # just authenticated inside this temporary browser.
            self.profile.cookieStore().deleteAllCookies()
            self.browser.load(QUrl(_SPOTIFY_WEB))
        else:
            # Re-emit an already captured cookie when retrying after a transient
            # validation/network failure.
            self.profile.cookieStore().loadAllCookies()
            self.browser.load(QUrl(_SPOTIFY_WEB))

    def _load_started(self):
        if not self._checking:
            self.status.setText("Esperando el inicio de sesión…")

    def _load_finished(self, succeeded):
        if not succeeded and not self._checking:
            self.status.setText("No se pudo abrir Spotify")
            self.detail.setText("Revisa tu conexión e inténtalo de nuevo.")
            self.login_button.setEnabled(True)

    def _render_failed(self, *_args):
        self.status.setText("El navegador de Spotify se detuvo")
        self.detail.setText("Cierra esta ventana y vuelve a intentarlo.")
        self.login_button.setEnabled(True)

    def _cookie_added(self, cookie):
        value = captured_session_value(cookie.name(), cookie.value(), cookie.domain())
        if not value or value in self._seen_values or self._checking:
            return
        self._seen_values.add(value)
        self._checking = True
        self.status.setText("Comprobando la sesión…")
        self.detail.setText("MusicVault está verificando que Spotify aceptó la cuenta.")
        self.session_captured.emit(value)

    def mark_connected(self):
        self._connected = True
        self._checking = False
        self.browser.hide()
        self.resize(660, 360)
        self.disconnect_button.setEnabled(True)
        self.login_button.setEnabled(True)
        self.login_button.setText("Usar otra cuenta")
        self.status.setText("Cuenta conectada correctamente")
        self.detail.setText("Ya puedes importar playlists de Spotify mediante SongMirror.")

    def mark_rejected(self, message):
        self._checking = False
        self.status.setText("Spotify rechazó la sesión")
        self.detail.setText(str(message))
        self.login_button.setEnabled(True)
        self.login_button.setText("Volver a intentarlo")

    def _disconnect(self):
        self.disconnect_requested.emit()
        self.profile.cookieStore().deleteAllCookies()
        self.browser.hide()
        self.resize(660, 360)
        self._connected = False
        self.disconnect_button.setEnabled(False)
        self.login_button.setText("Iniciar sesión con Spotify")
        self._show_account_state()

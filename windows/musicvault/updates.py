"""Optional GitHub release updates: automatic daily checks, verified downloads."""
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
from urllib.parse import unquote, urlsplit
from urllib.request import Request, urlopen

from PyQt6.QtCore import QObject, QTimer, Qt, pyqtSignal
from PyQt6.QtWidgets import (QWidget, QVBoxLayout, QHBoxLayout, QLabel, QPushButton,
                            QCheckBox, QProgressBar, QMessageBox, QApplication)
from . import __version__, __version_code__
from .models import data_dir
from .portable import ROOT

REPOSITORY = 'Donatelatrump/MusicVault'
MANIFEST = 'musicvault-windows-update.json'
PACKAGE = 'com.musicvault.windows'
API = f'https://api.github.com/repos/{REPOSITORY}/releases'
PREFIX = f'https://github.com/{REPOSITORY}/releases/download/'
MAX_INSTALLER = 2 * 1024**3


@dataclass(frozen=True)
class Release:
    versionCode: int
    versionName: str
    notes: str
    url: str
    sha256: str
    size: int


def trusted_url(url):
    if not isinstance(url,str):return False
    try:
        parsed = urlsplit(url)
        path = unquote(parsed.path)
        return (url.startswith(PREFIX) and parsed.scheme == 'https' and parsed.hostname == 'github.com'
                and not parsed.username and not parsed.password and parsed.port is None
                and not parsed.query and not parsed.fragment and '\\' not in path
                and '..' not in path.split('/') and len(path.split('/')) == 7)
    except (ValueError, TypeError):
        return False


def parse_manifest(payload, installed=__version_code__):
    if not isinstance(payload,dict):return None
    if payload.get('schemaVersion') != 1 or payload.get('packageName') != PACKAGE:
        return None
    code = payload.get('versionCode')
    if type(code) is not int or code <= installed:
        return None
    version = payload.get('versionName', '')
    if not isinstance(version, str) or not re.fullmatch(r'\d+\.\d+\.\d+', version):
        return None
    assets=payload.get('assets',[])
    if not isinstance(assets,list):return None
    for asset in assets:
        if not isinstance(asset,dict):continue
        url, digest, size = asset.get('url', ''), asset.get('sha256', ''), asset.get('size', 0)
        if (asset.get('arch') == 'x86_64' and asset.get('kind') == 'installer'
                and trusted_url(url) and url.endswith('.exe') and isinstance(digest,str) and re.fullmatch('[a-f0-9]{64}', digest)
                and type(size) is int and 0 < size <= MAX_INSTALLER):
            return Release(code, version, str(payload.get('notes',''))[:4000], url, digest, size)
    return None


def json_request(url, maximum=2*1024*1024):
    from .services import image_tls_context
    request = Request(url, headers={'User-Agent':f'MusicVault-Windows/{__version__}', 'Accept':'application/vnd.github+json'})
    with urlopen(request, timeout=15, context=image_tls_context()) as response:
        body = response.read(maximum+1)
    if len(body)>maximum:
        raise ValueError('La respuesta de actualización es demasiado grande.')
    return json.loads(body)


def find_update(cancel=None, installed=__version_code__):
    found=[]
    # A platform-specific manifest avoids confusing Android and Windows tags.
    for page in range(1,4):
        if cancel and cancel.is_set():return None
        releases=json_request(API+f'?per_page=30&page={page}')
        if not isinstance(releases,list):raise ValueError('GitHub no devolvió la lista de versiones.')
        for release in releases:
            if release.get('draft') or release.get('prerelease'):continue
            assets=release.get('assets',[])
            manifest=next((a for a in assets if a.get('name')==MANIFEST),None)
            if not manifest or not trusted_url(manifest.get('browser_download_url','')):continue
            if cancel and cancel.is_set():return None
            candidate=parse_manifest(json_request(manifest['browser_download_url'],128*1024),installed)
            if not candidate:continue
            # The offered executable must actually belong to this stable release.
            if any(a.get('browser_download_url')==candidate.url and a.get('size')==candidate.size for a in assets):
                found.append(candidate)
        if len(releases)<30:break
    return max(found,key=lambda r:r.versionCode,default=None)


def verify_installer(path, release):
    if not path.is_file() or path.stat().st_size!=release.size:return False
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()==release.sha256


def download_installer(release, folder, cancel, progress):
    from .services import image_tls_context
    if not trusted_url(release.url):raise ValueError('Origen de actualización no permitido.')
    folder.mkdir(parents=True,exist_ok=True)
    destination=folder/f'MusicVault-{release.versionCode}-{release.sha256[:12]}-Setup.exe'
    if verify_installer(destination,release):progress(100);return str(destination)
    temporary=destination.with_suffix('.partial')
    digest=hashlib.sha256();total=0
    try:
        request=Request(release.url,headers={'User-Agent':f'MusicVault-Windows/{__version__}'})
        with urlopen(request,timeout=30,context=image_tls_context()) as response, temporary.open('wb') as output:
            while True:
                if cancel.is_set():raise RuntimeError('Descarga cancelada.')
                block=response.read(1024*1024)
                if not block:break
                total+=len(block)
                if total>release.size:raise ValueError('El instalador no coincide con el tamaño publicado.')
                digest.update(block);output.write(block);progress(min(99,int(total*100/release.size)))
        if total!=release.size or digest.hexdigest()!=release.sha256:
            raise ValueError('El instalador no pasó la verificación SHA-256. Vuelve a descargarlo.')
        os.replace(temporary,destination);progress(100);return str(destination)
    finally:
        temporary.unlink(missing_ok=True)


def launch_after_exit(installer, root=ROOT):
    """The OS worker lives outside the installation so its files can be replaced."""
    installer=Path(installer).resolve();root=Path(root).resolve()
    def literal(value):return "'"+str(value).replace("'","''")+"'"
    # The launcher also holds a file handle until the Python GUI exits. Waiting
    # for this exact app's parent is safe only if it is our packaged launcher.
    parent=None
    try:
        import ctypes as c
        kernel=c.WinDLL('kernel32',use_last_error=True)
        kernel.OpenProcess.argtypes=[c.c_ulong,c.c_int,c.c_ulong];kernel.OpenProcess.restype=c.c_void_p
        kernel.QueryFullProcessImageNameW.argtypes=[c.c_void_p,c.c_ulong,c.c_wchar_p,c.POINTER(c.c_ulong)]
        kernel.CloseHandle.argtypes=[c.c_void_p]
        handle=kernel.OpenProcess(0x1000,False,os.getppid())
        if handle:
            try:
                name=c.create_unicode_buffer(32768);size=c.c_ulong(len(name))
                if kernel.QueryFullProcessImageNameW(handle,0,name,c.byref(size)) and Path(name.value).resolve()==root/'MusicVault.exe':parent=os.getppid()
            finally:kernel.CloseHandle(handle)
    except OSError:
        pass
    pids=[os.getpid()]+([parent] if parent else [])
    script=installer.parent/f'install-after-exit-{os.getpid()}-{time.time_ns()}.ps1'
    with installer.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
    script.write_text("$ErrorActionPreference = 'Stop'\ntry {\n"
        +f"foreach ($appPid in @({','.join(map(str,pids))})) {{\n"
        +"  $appProcess = Get-Process -Id $appPid -ErrorAction SilentlyContinue\n"
        +"  if ($appProcess) { $appProcess | Wait-Process -Timeout 120 }\n}\n"
        +f"if ((Get-FileHash -LiteralPath {literal(installer)} -Algorithm SHA256).Hash.ToLowerInvariant() -ne '{digest}') {{ throw 'El instalador no paso la verificacion de integridad.' }}\n"
        +f"Start-Process -FilePath {literal(installer)} -ArgumentList ('/D=' + {literal(root)}) -Wait\n"
        +"} catch {\n"
        +f"  $_ | Out-File -LiteralPath {literal(installer.parent/'install-error.log')} -Encoding utf8\n"
        +"  Add-Type -AssemblyName System.Windows.Forms\n"
        +"  [System.Windows.Forms.MessageBox]::Show('No se pudo iniciar la actualizacion. Puedes ejecutar manualmente el instalador descargado. Consulta install-error.log en la carpeta de actualizaciones.', 'MusicVault')\n}\n",encoding='utf-8-sig')
    powershell=Path(os.environ.get('SystemRoot','C:/Windows'))/'System32/WindowsPowerShell/v1.0/powershell.exe'
    subprocess.Popen([str(powershell),'-NoProfile','-NonInteractive','-WindowStyle','Hidden',
                      '-ExecutionPolicy','Bypass','-File',str(script)],
                     creationflags=subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP,
                     close_fds=True,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)


class UpdateManager(QObject):
    changed=pyqtSignal()

    def __init__(self,window):
        super().__init__(window)
        self.window=window;self.db=window.db;self.busy=False;self.downloading=False;self.progress=0;self.job=None
        self.available=None;self.status='Busca nuevas versiones en GitHub.'
        cached=self.db.setting('windows_update_available')
        if cached:
            try:self.available=parse_manifest(cached)
            except (TypeError,ValueError):pass
        self.timer=QTimer(self);self.timer.setInterval(60*60*1000);self.timer.timeout.connect(self.auto_check);self.timer.start()
        QTimer.singleShot(5000,self.auto_check)
        QApplication.instance().applicationStateChanged.connect(self.application_state)

    def application_state(self,state):
        if state==Qt.ApplicationState.ApplicationActive:self.auto_check()

    def auto_check(self):
        if self.window._quitting or os.environ.get('MUSICVAULT_DISABLE_UPDATES')=='1' or not self.db.setting('windows_auto_updates',True):return
        now=time.time()
        if now-float(self.db.setting('windows_update_last_success',0))<86400:return
        if now-float(self.db.setting('windows_update_last_attempt',0))<60:return
        self.check(False)

    def check(self,manual=True):
        if self.busy or self.window._quitting:return
        self.busy=True;self.status='Buscando actualizaciones en GitHub…';self.changed.emit()
        self.db.set_setting('windows_update_last_attempt',time.time())
        def ready(release):
            if self.window._quitting:return
            self.busy=False;self.available=release
            self.db.set_setting('windows_update_last_success',time.time())
            if release:
                self.status=f'Disponible: MusicVault {release.versionName}'
                self.db.set_setting('windows_update_available',self.manifest(release))
            else:
                self.status='Tienes la versión más reciente de MusicVault para Windows.'
                self.db.set_setting('windows_update_available',None)
            self.changed.emit()
            dismissed=int(self.db.setting('windows_update_dismissed',0))
            if release and (manual or release.versionCode>dismissed):self.offer()
        def failed(error):
            if self.window._quitting:return
            self.busy=False;self.status='No se pudo consultar GitHub. Comprueba la conexión y vuelve a intentarlo.';self.changed.emit()
        self.job=self.window.jobs.submit(lambda cancel,progress:find_update(cancel),ready,failed)

    @staticmethod
    def manifest(release):
        return {'schemaVersion':1,'packageName':PACKAGE,'versionCode':release.versionCode,'versionName':release.versionName,
                'notes':release.notes,'assets':[{'arch':'x86_64','kind':'installer','url':release.url,'sha256':release.sha256,'size':release.size}]}

    def offer(self):
        release=self.available
        if not release or self.downloading:return
        box=QMessageBox(self.window);box.setWindowTitle('Actualización de MusicVault')
        box.setTextFormat(Qt.TextFormat.PlainText);box.setText(f'MusicVault {release.versionName} está disponible.')
        box.setInformativeText(release.notes or 'Descarga e instala la nueva versión conservando tu biblioteca.')
        update=box.addButton('Actualizar',QMessageBox.ButtonRole.AcceptRole)
        box.addButton('Ahora no',QMessageBox.ButtonRole.RejectRole);box.exec()
        if box.clickedButton() is update:self.download()
        else:self.db.set_setting('windows_update_dismissed',release.versionCode)

    def download(self):
        if self.busy or not self.available:return
        release=self.available;self.busy=self.downloading=True;self.progress=0
        self.status=f'Descargando MusicVault {release.versionName}…';self.changed.emit()
        base=Path(os.environ.get('LOCALAPPDATA',data_dir()))/'MusicVault/updates'
        def ready(path):
            if self.window._quitting:return
            self.busy=self.downloading=False;self.status='Descarga verificada. Lista para instalar.';self.changed.emit()
            choice=QMessageBox.question(self.window,'Instalar actualización',
                'Se guardará tu biblioteca y se cerrará MusicVault para abrir el instalador. ¿Instalar ahora?')
            if choice!=QMessageBox.StandardButton.Yes:return
            # Revalidate immediately before starting executable code.
            if not verify_installer(Path(path),release):failed('El instalador cambió después de descargarlo.');return
            try:launch_after_exit(path)
            except OSError as error:failed(error);return
            self.window.quit_app()
        def failed(error):
            if self.window._quitting:return
            self.busy=self.downloading=False;self.status=str(error);self.changed.emit()
        def progress(value):self.progress=value;self.changed.emit()
        self.job=self.window.jobs.submit(lambda cancel,progress:download_installer(release,base,cancel,progress),ready,failed,progress)

    def widget(self,parent):
        surface=QWidget(parent);layout=QVBoxLayout(surface);layout.setContentsMargins(0,0,0,0)
        installed=QLabel(f'Versión instalada: {__version__} · Windows x64');layout.addWidget(installed)
        automatic=QCheckBox('Buscar actualizaciones automáticamente en GitHub')
        automatic.setChecked(self.db.setting('windows_auto_updates',True))
        automatic.toggled.connect(lambda value:self.db.set_setting('windows_auto_updates',value));layout.addWidget(automatic)
        status=QLabel();status.setTextFormat(Qt.TextFormat.PlainText);status.setWordWrap(True);layout.addWidget(status)
        bar=QProgressBar();bar.setRange(0,100);layout.addWidget(bar)
        row=QHBoxLayout();check=QPushButton('Buscar actualizaciones');check.setObjectName('settingsAction');check.clicked.connect(lambda:self.check(True));row.addWidget(check)
        install=QPushButton('Actualizar');install.setObjectName('primary');install.clicked.connect(self.download);row.addWidget(install)
        cancel=QPushButton('Cancelar descarga');cancel.clicked.connect(lambda:self.job.cancel.set() if self.job else None);row.addWidget(cancel);row.addStretch();layout.addLayout(row)
        def refresh():
            status.setText(self.status if not self.available or self.busy or self.status.startswith('Disponible:') else f'Disponible: MusicVault {self.available.versionName}\n'+self.status)
            check.setEnabled(not self.busy);install.setVisible(self.available is not None);install.setEnabled(not self.busy)
            bar.setVisible(self.downloading);bar.setValue(self.progress);cancel.setVisible(self.downloading)
        # Context-bound Qt receivers disconnect automatically with the settings widget.
        self.changed.connect(refresh)
        def detach():
            try:self.changed.disconnect(refresh)
            except (TypeError,RuntimeError):pass
        surface.destroyed.connect(detach)
        refresh();return surface

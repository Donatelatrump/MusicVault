"""Supervise a private userspace WARP proxy; no host routes or TUN device needed."""
import os
from pathlib import Path
import signal
import subprocess
import threading
import time
from urllib.request import ProxyHandler, build_opener

PROXY = 'http://127.0.0.1:40000'


class WarpProxy:
    def __init__(self, directory=None):
        self.directory = Path(directory or Path(__file__).resolve().parents[1] / '.data/warp')
        self.process = None
        self.log = None
        self.closing = threading.Event()

    def __enter__(self):
        config = self.directory / 'wireproxy.conf'
        binary = self.directory / 'bin/wireproxy'
        if not config.is_file() or not binary.is_file():
            raise RuntimeError('Falta configurar WARP. Ejecuta primero warp_setup.py.')
        config.chmod(0o600)
        self.log = (self.directory / 'wireproxy.log').open('wb')
        os.chmod(self.directory / 'wireproxy.log', 0o600)
        self.process = subprocess.Popen([str(binary), '-c', str(config)], stdout=self.log, stderr=self.log)
        try:
            opener = build_opener(ProxyHandler({'https': PROXY}))
            verified = False
            for attempt in range(3):
                if self.process.poll() is not None:
                    raise RuntimeError('El proxy WARP terminó durante el arranque.')
                try:
                    with opener.open('https://www.cloudflare.com/cdn-cgi/trace', timeout=8) as response:
                        trace = dict(line.split('=', 1) for line in response.read(8192).decode().splitlines() if '=' in line)
                    if trace.get('warp') == 'on':
                        verified = True
                        break
                except OSError:
                    time.sleep(0.5)
            if not verified:
                raise RuntimeError('Cloudflare no confirmó warp=on. No se iniciará el bot por una salida directa.')
            print('WARP_EGRESS_VERIFIED warp=on', flush=True)
            threading.Thread(target=self._watch, daemon=True).start()
            return self
        except BaseException:
            self.close()
            raise

    def _watch(self):
        self.process.wait()
        if not self.closing.is_set():
            print('WARP_PROXY_STOPPED: deteniendo el bot para evitar una salida directa.', flush=True)
            os.kill(os.getpid(), signal.SIGINT)

    def close(self):
        self.closing.set()
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        if self.log:
            self.log.close()

    def __exit__(self, *_):
        self.close()

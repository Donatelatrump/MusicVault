"""Resolve the application downloader and its JavaScript runtime for desktop launches."""
from functools import lru_cache
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys


def downloader_command():
    override = os.environ.get("MUSICVAULT_YTDLP")
    if override:
        return [override]
    bundled = Path(__file__).parent / "vendor/yt-dlp"
    if bundled.is_file():
        return [sys.executable, str(bundled)]
    binary = shutil.which("yt-dlp")
    if not binary:
        raise RuntimeError("No se encuentra el motor de descargas yt-dlp.")
    return [binary]


@lru_cache(maxsize=1)
def javascript_runtime():
    candidates = []
    for name in ("deno", "node"):
        found = shutil.which(name)
        if found:
            candidates.append((name, Path(found)))
    # Desktop entries do not load shell profiles or nvm's PATH.
    candidates.extend(("deno", path) for path in (Path.home()/".deno/bin/deno", Path("/usr/bin/deno")))
    candidates.extend(("node", path) for path in sorted((Path.home()/".nvm/versions/node").glob("v*/bin/node"), reverse=True))
    candidates.append(("node", Path("/usr/bin/node")))
    for name, path in candidates:
        if not path.is_file():
            continue
        try:
            result = subprocess.run([str(path), "--version"], capture_output=True, text=True, timeout=3)
            match = re.search(r"(\d+)\.(\d+)\.(\d+)", result.stdout)
            version = tuple(map(int, match.groups())) if match else (0, 0, 0)
            if result.returncode == 0 and version >= ((2, 3, 0) if name == "deno" else (22, 0, 0)):
                return f"{name}:{path}"
        except (OSError, subprocess.TimeoutExpired):
            continue
    return None


def youtube_arguments():
    runtime = javascript_runtime()
    if not runtime:
        raise RuntimeError("Las descargas de YouTube necesitan Node.js 22 o superior, o Deno 2.3 o superior. Instala uno y vuelve a intentar.")
    return ["--js-runtimes", runtime]


def explain_download_error(output):
    if not output:
        return ""
    lowered = output.casefold()
    if any(marker in lowered for marker in (
        "failed to resolve", "temporary failure in name resolution", "name or service not known",
        "getaddrinfo failed", "nameresolutionerror", "nodename nor servname provided",
    )):
        return "No se pudo encontrar YouTube en la red (DNS). Comprueba tu conexión y pulsa Reintentar."
    if any(marker in lowered for marker in ("timed out", "timeout", "network is unreachable", "connection refused", "connection reset")):
        return "Se interrumpió la conexión con YouTube. Comprueba tu conexión y pulsa Reintentar."
    if "HTTP Error 403" in output:
        return "YouTube rechazó la descarga (403). No se guardó ningún audio. Reintenta desde Descargas; si persiste, actualiza el motor de descargas."
    if "Sign in" in output or "sign in" in output:
        return "YouTube pide iniciar sesión para este contenido. No se descargó ningún archivo."
    # Stored records may already contain the translated message from an older release.
    first = output.split("\n\nDetalle técnico:", 1)[0].strip()
    if len(first) < 260 and not any(marker in first for marker in ("ERROR:", "HTTPSConnection", "Traceback", "No se pudo descargar:")):
        if first.startswith(("No se ", "Se interrumpió", "YouTube ", "Las descargas ", "Instala ", "La descarga ")):
            return first
    return "No se pudo completar la descarga. Consulta los detalles o pulsa Reintentar."

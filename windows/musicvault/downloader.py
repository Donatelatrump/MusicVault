"""Utilidades de descarga directa vía HTTP (yt-dlp eliminado, NewPipe solo)."""
from pathlib import Path
import os
import shutil


def downloader_command():
    raise RuntimeError("yt-dlp fue eliminado. YouTube se resuelve exclusivamente mediante NewPipe.")


def javascript_runtime():
    return None


def clear_javascript_runtime_cache():
    pass


def youtube_arguments():
    return []


def explain_download_error(output):
    if not output:
        return ""
    return "No se pudo completar la descarga. Consulta los detalles o pulsa Reintentar."

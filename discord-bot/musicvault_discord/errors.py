"""Actionable playback errors without exposing stream URLs or credentials."""
import re


def playback_error_message(error):
    text = str(error).lower().replace('’', "'")
    if "sign in to confirm" in text and "not a bot" in text:
        return "YouTube bloqueó la IP de este alojamiento y pidió verificar la cuenta. No se pudo preparar el audio."
    if "tardó demasiado" in text or "timed out" in text or "timeout" in text:
        return "El proveedor tardó demasiado en preparar el audio. Inténtalo de nuevo."
    if "video unavailable" in text or "private video" in text:
        return "Ese vídeo no está disponible o es privado. Prueba otro enlace."
    return "No pude reproducir esa canción. Paso a la siguiente."


def safe_diagnostic(error):
    text = re.sub(r'https?://\S+', '[URL]', str(error))
    text = re.sub(r'[A-Za-z0-9_-]{20,30}\.[A-Za-z0-9_-]{6}\.[A-Za-z0-9_-]{25,100}', '[TOKEN]', text)
    return text[-1200:]

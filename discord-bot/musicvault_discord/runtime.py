"""Resolve user-space tools so KataBump does not need apt/root access."""
import os
from pathlib import Path
import shutil


def prepare_runtime():
    from dotenv import load_dotenv
    load_dotenv(Path(__file__).resolve().parents[1] / ".env")
    from .media import configure_media_proxy
    configure_media_proxy()
    if not os.environ.get("MUSICVAULT_FFMPEG"):
        executable = shutil.which("ffmpeg")
        if not executable:
            from imageio_ffmpeg import get_ffmpeg_exe
            executable = get_ffmpeg_exe()
        os.environ["MUSICVAULT_FFMPEG"] = executable
    # The binaries package avoids needing a second language server on KataBump.
    try:
        import nodejs_wheel
    except ImportError:
        return
    directory = Path(nodejs_wheel.__file__).parent / "bin"
    os.environ["PATH"] = str(directory) + os.pathsep + os.environ.get("PATH", "")

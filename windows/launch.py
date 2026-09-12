"""Entry point for the embedded Windows Python distribution."""
import sys
from pathlib import Path
import traceback

root = Path(__file__).resolve().parent
sys.path.insert(0, str(root))
try:
    from musicvault.__main__ import main
    raise SystemExit(main())
except Exception:
    (root / "Data").mkdir(exist_ok=True)
    (root / "Data/startup-error.log").write_text(traceback.format_exc(), encoding="utf-8")
    import ctypes
    ctypes.windll.user32.MessageBoxW(None, "No se pudo abrir MusicVault. Consulta Data/startup-error.log.", "MusicVault", 16)
    raise SystemExit(1)

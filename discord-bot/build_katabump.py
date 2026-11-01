"""Build a small deployable ZIP using an explicit allowlist, with no credentials."""
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

base = Path(__file__).resolve().parent
root = base.parent
output = base / "dist/musicvault-katabump.zip"
output.parent.mkdir(exist_ok=True)
with ZipFile(output, "w", ZIP_DEFLATED) as archive:
    for name in ("main.py", "preflight.py", "warp_main.py", "warp_setup.py", "warp_media_probe.py", "requirements.txt", ".env.example", "README.md"):
        archive.write(base / name, name)
    for source in sorted((base / "musicvault_discord").glob("*.py")):
        archive.write(source, "musicvault_discord/" + source.name)
    # These existing modules are independent of Qt; keep the exact repository sources.
    for name in ("__init__.py", "models.py", "database.py", "downloader.py", "streaming.py",
                 "vendor/yt-dlp", "vendor/LICENSE.yt-dlp"):
        archive.write(root / "linux/musicvault" / name, "musicvault_core/musicvault/" + name)
    archive.write(root / "linux/THIRD_PARTY_NOTICES.md", "MUSICVAULT_THIRD_PARTY_NOTICES.md")
print(f"{output} ({output.stat().st_size / 1024 / 1024:.2f} MiB)")

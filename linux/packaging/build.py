#!/usr/bin/env python3
"""Build an amd64 .deb and relocatable source tarball without root or network."""
import argparse
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.9.4"


def copy_app(destination):
    shutil.copytree(ROOT / "musicvault", destination / "musicvault",
                    ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    shutil.copy2(ROOT / "README.md", destination / "README.md")
    shutil.copy2(ROOT / "VERIFICATION.md", destination / "VERIFICATION.md")
    shutil.copy2(ROOT / "THIRD_PARTY_NOTICES.md", destination / "THIRD_PARTY_NOTICES.md")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "dist")
    args = parser.parse_args()
    if platform.machine() != "x86_64":
        parser.error("Build this distribution on Linux x86_64.")
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="musicvault-build-") as directory:
        stage = Path(directory)
        portable = stage / f"MusicVault-{VERSION}-linux-x86_64"
        copy_app(portable)
        shutil.copy2(ROOT / "run.sh", portable / "MusicVault")
        (portable / "MusicVault").chmod(0o755)
        archive = args.output / f"MusicVault-{VERSION}-linux-x86_64.tar.gz"
        with tarfile.open(archive, "w:gz") as tar:
            tar.add(portable, arcname=portable.name)
        print(archive)
        if not shutil.which("dpkg-deb"):
            raise SystemExit("dpkg-deb is required to produce the .deb package.")
        package = stage / "deb"
        share = package / "usr/share/musicvault"
        copy_app(share)
        (package / "usr/bin").mkdir(parents=True)
        shutil.copy2(ROOT / "run.sh", package / "usr/bin/musicvault")
        (package / "usr/bin/musicvault").chmod(0o755)
        applications = package / "usr/share/applications"
        applications.mkdir(parents=True)
        shutil.copy2(ROOT / "packaging/com.musicvault.MusicVault.desktop", applications)
        icons = package / "usr/share/icons/hicolor/256x256/apps"
        icons.mkdir(parents=True)
        shutil.copy2(ROOT / "musicvault/assets/musicvault.png", icons / "com.musicvault.MusicVault.png")
        control = package / "DEBIAN"
        control.mkdir()
        size = sum(p.stat().st_size for p in package.rglob("*") if p.is_file()) // 1024
        (control / "control").write_text(f"""Package: musicvault
Version: {VERSION}
Section: sound
Priority: optional
Architecture: amd64
Maintainer: MusicVault contributors <musicvault@localhost>
Installed-Size: {size}
Depends: python3 (>= 3.10), python3-pyqt6, python3-pyqt6.qtwebengine, python3-mutagen, python3-dbus, python3-gi, libmpv2, ffmpeg, yt-dlp
Recommends: nodejs (>= 22) | deno (>= 2.3)
Description: Native desktop music library for Linux
 Local and streaming playback, editable queues, YouTube playlists, downloads and MPRIS controls.
""")
        deb = args.output / f"musicvault_{VERSION}_amd64.deb"
        subprocess.run(["dpkg-deb", "--root-owner-group", "--build", str(package), str(deb)], check=True)
        print(deb)


if __name__ == "__main__":
    main()

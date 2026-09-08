#!/usr/bin/env python3
"""Vendor the official Python zip executable and verify its release SHA-256 checksum."""
import hashlib
import json
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def read(url):
    with urlopen(Request(url, headers={"User-Agent": "MusicVault-build/0.6.1"}), timeout=40) as response:
        return response.read(20 * 1024 * 1024)


def main():
    release = json.loads(read("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"))
    version = release["tag_name"]
    base = f"https://github.com/yt-dlp/yt-dlp/releases/download/{version}/"
    checksums = read(base + "SHA2-256SUMS").decode()
    expected = next(line.split()[0] for line in checksums.splitlines() if line.split()[-1].lstrip("*") == "yt-dlp")
    body = read(base + "yt-dlp")
    actual = hashlib.sha256(body).hexdigest()
    if actual != expected:
        raise RuntimeError("La suma de comprobación de yt-dlp no coincide con la publicación oficial.")
    destination = ROOT / "musicvault/vendor"
    destination.mkdir(parents=True, exist_ok=True)
    temporary = destination / "yt-dlp.part"
    temporary.write_bytes(body)
    temporary.replace(destination / "yt-dlp")
    license_text = read("https://raw.githubusercontent.com/yt-dlp/yt-dlp/" + version + "/LICENSE").decode()
    (destination / "LICENSE.yt-dlp").write_text(license_text)
    (ROOT / "packaging/downloader.lock.json").write_text(json.dumps({"version": version, "sha256": actual, "url": base + "yt-dlp"}, indent=2) + "\n")
    print(f"yt-dlp {version}, SHA-256 verificado: {actual}")


if __name__ == "__main__":
    main()

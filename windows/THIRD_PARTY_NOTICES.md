# Avisos de terceros

## SongMirror

El lector integrado de playlists de Spotify en `musicvault/songmirror_spotify.py`
adapta partes del backend de sesión web de
[SongMirror](https://github.com/ahnafnafee/songmirror).

Copyright (c) 2026 Ahnaf An Nafee.

## SpotifyScraper

El intercambio local de la cookie `sp_dc` por un token temporal adapta partes de
[SpotifyScraper](https://github.com/AliAkhtari78/SpotifyScraper), dependencia que
utiliza SongMirror para esta operación.

Copyright (c) 2025 Ali Akhtari.

Ambos componentes se distribuyen bajo la siguiente licencia MIT:

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Componentes del paquete Windows

El paquete incluye Python (licencia PSF, `python/LICENSE.txt`), PyQt6 y
PyQt6-WebEngine (GPLv3/comercial), Qt (licencias incluidas en los paquetes),
Mutagen (GPLv2 o posterior), libmpv y sus dependencias, FFmpeg (build GPLv3),
yt-dlp (Unlicense y licencias de sus dependencias) y Node.js (MIT y avisos
incluidos). Los metadatos y licencias de las ruedas se conservan en
`python/Lib/site-packages/*.dist-info/`; los avisos de Node y FFmpeg están
en `licenses/`.

Fuentes y recetas de construcción de los binarios:
- Python: https://www.python.org/downloads/release/python-31210/
- PyQt: https://www.riverbankcomputing.com/software/pyqt/download
- Qt: https://download.qt.io/archive/qt/
- Mutagen: https://github.com/quodlibet/mutagen
- libmpv: https://github.com/shinchiro/mpv-winbuild-cmake/tree/20260903
- mpv: https://github.com/mpv-player/mpv/commit/69e63f425a
- FFmpeg: https://www.gyan.dev/ffmpeg/builds/ y https://github.com/FFmpeg/FFmpeg/tree/n8.1.2
- yt-dlp: https://github.com/yt-dlp/yt-dlp/tree/2026.08.19
- Node.js: https://nodejs.org/dist/v22.16.0/

Las versiones y hashes exactos de los archivos distribuidos se registran en
`dependencies.lock.json`. El código de MusicVault adaptado se incluye en
`musicvault/` dentro del propio paquete.

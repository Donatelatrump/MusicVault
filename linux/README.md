# MusicVault para Linux x86_64

Aplicación de escritorio nativa en español, con Qt 6, libmpv y SQLite. No requiere Android, Java, emulador ni navegador. Esta versión utiliza Python 3 para la lógica y bibliotecas nativas para la interfaz y el audio.

## Ejecutar desde este repositorio

Todos los comandos siguientes se ejecutan desde la carpeta `linux/` (`cd linux` desde la raíz del repositorio).

```bash
./run.sh --doctor
./run.sh
```

El lanzador usa `/usr/bin/python3` para encontrar los paquetes instalados por la distribución, independientemente de pyenv o de un entorno virtual activo. Puedes elegir otro intérprete con `MUSICVAULT_PYTHON=/ruta/python`.


La versión 0.8.0 sincroniza la experiencia con Android: onboarding local por géneros/artistas, recomendaciones balanceadas y precargadas, búsqueda separada Canciones/Álbumes con respuestas independientes, creación/importación de playlists con buscador, carátula automática, eliminación directa, caché de álbumes y listas nativas acotadas para evitar tirones.

La versión 0.8.0 conserva la distribución inspirada en Spotify de escritorio: barra lateral permanente con playlists, búsqueda superior, accesos rápidos, estantes de portadas y controles inferiores con una barra de progreso amplia. Conserva la paleta carbón, ámbar y marfil, las fuentes originales y la tornamesa HV-01. Se adapta desde 920 × 680 hasta ventanas amplias. La biblioteca muestra únicamente archivos locales disponibles; las descargas fallidas permanecen en Descargas y requieren pulsar Reintentar. Un archivo descargado se reutiliza aunque se reproduzca desde resultados antiguos del catálogo.

En Debian 12/13 o Ubuntu 24.04/26.04, instala las dependencias si el diagnóstico indica que faltan:

```bash
sudo apt install python3 python3-pyqt6 python3-pyqt6.qtwebengine python3-mutagen python3-dbus python3-gi libmpv2 ffmpeg yt-dlp
```

En otras distribuciones hacen falta sus paquetes equivalentes: Python ≥ 3.10, PyQt6 y PyQt6-WebEngine ≥ 6.4, Mutagen, dbus-python, PyGObject/GLib, libmpv con ABI 2, FFmpeg y yt-dlp. La compatibilidad con esas distribuciones depende de sus paquetes; la verificación local se realizó en Ubuntu 26.04 x86_64 con Qt 6.10.2 y Python 3.14.4.

Para descargar de YouTube también se necesita **Node.js ≥22 o Deno ≥2.3**. El diagnóstico `--doctor` comprueba el motor disponible. La app encuentra Node instalado con nvm aunque se abra desde el menú del escritorio. Se incluye yt-dlp 2026.08.19 con EJS para no depender de la versión antigua de APT. Estos requisitos siguen la [guía EJS oficial de yt-dlp](https://github.com/yt-dlp/yt-dlp/wiki/EJS).

Para actualizar el motor incluido durante el desarrollo: `python3 packaging/fetch_downloader.py`. Descarga el ejecutable Python oficial, comprueba SHA-256 y registra versión, URL y hash en `packaging/downloader.lock.json`. Después se vuelve a generar el paquete. `MUSICVAULT_YTDLP=/ruta/yt-dlp` permite elegir explícitamente otro ejecutable.

## Rendimiento de la interfaz

- Tabla con modelo virtual: no crea seis objetos por canción. Las portadas se solicitan para las filas visibles y se conservan hasta 128 miniaturas de 96 píxeles.
- Solicitudes de portada compartidas, caché de rutas limitada y dos trabajadores dedicados para no ocupar los de búsqueda y descargas.
- Filtro local con espera de 140 ms y catálogo con 600 ms; las búsquedas anteriores se cancelan al cambiar de consulta o sección.
- La selección se conserva al actualizar; cambiar de canción repinta solo las filas implicadas. El progreso de descarga actualiza su elemento sin reconstruir la lista.
- La animación del vinilo detiene su temporizador cuando está oculto o termina de volver a reposo. Las letras ocultas no se desplazan en segundo plano.

Consulta los resultados y límites de la [verificación local](VERIFICATION.md).

## Instalar o distribuir

Generar los paquetes sin root ni conexión a Internet:

```bash
python3 packaging/build.py
```

Produce:

- `dist/musicvault_0.8.0_amd64.deb`: instala el lanzador, icono y entrada del menú de aplicaciones. Declara las dependencias para que APT pueda resolverlas.
- `dist/MusicVault-0.8.0-linux-x86_64.tar.gz`: distribución reubicable del código y recursos, con un lanzador `MusicVault`. **Requiere las dependencias del sistema; no es un binario autocontenido ni un AppImage.**

Instalar el paquete Debian/Ubuntu:

```bash
sudo apt install ./dist/musicvault_0.8.0_amd64.deb
musicvault
```

Extraer y ejecutar la distribución reubicable:

```bash
tar -xzf dist/MusicVault-0.8.0-linux-x86_64.tar.gz
./MusicVault-0.8.0-linux-x86_64/MusicVault
```

El paquete no incluye ejecutables de audio compilados en este equipo: utiliza las bibliotecas de la distribución de destino. No hay dependencia de una versión de glibc impuesta por un ejecutable compilado aquí.

## Funciones

- Importación de archivos o carpetas, arrastrar y soltar, etiquetas y portadas incrustadas. MP3, FLAC, Ogg, Opus, M4A, AAC, WAV, AIFF y otros formatos reconocidos por Mutagen/libmpv.
- Escaneo al arrancar y cada cinco minutos de las carpetas elegidas. Duración mínima configurable. Quitar una canción no elimina el archivo ni permite que un escaneo la recupere; una importación explícita sí puede recuperarla.
- Biblioteca, búsqueda local, favoritos, historial de reproducción, playlists y álbumes personalizados: crear, renombrar, añadir, quitar y ordenar canciones.
- Reproducción local completa; cola editable, anterior/siguiente, seek, aleatorio y repetición de una canción o de toda la cola. La cola se restaura pausada al arrancar.
- Crossfade de 0–12 segundos con dos instancias reales de libmpv. Pausar durante la transición pausa ambos audios. Las pistas muy cortas y las pendientes de descarga usan transición normal.
- Ecualizador de cinco bandas, ocho presets, ganancia y amplitud estéreo, con limitador. Los efectos afectan el audio de MusicVault.
- Temporizador de sueño. Continuación opcional con canciones relacionadas de la biblioteca; prioriza álbum, artista y favoritos.
- Búsqueda en Deezer con alternativas iTunes y MusicBrainz; búsqueda explícita en YouTube. Álbumes completos de Deezer/iTunes. Historial con autocompletado y caché de 30 minutos; resultados guardados como alternativa sin conexión.
- Importación de playlists de Spotify mediante el lector de SongMirror integrado: en **Importar música → Playlist de Spotify mediante SongMirror…** pega el enlace, revisa las canciones y desmarca las que no quieras antes de guardarla. No requiere instalar SongMirror, Docker ni una cuenta Premium. Con una cuenta conectada, MusicVault también detecta enlaces de canciones o playlists de Spotify copiados al portapapeles y muestra una opción para importarlos.
- Biblioteca con filtros por artista y álbum, orden por fecha/título/artista/álbum y columnas redimensionables que conservan su ancho.
- Al descargar un álbum completo se guarda también como álbum en **Álbumes**; cada canción terminada aparece individualmente en **Biblioteca**.
- Descargas mediante yt-dlp/FFmpeg, progreso individual, cancelación, reintento y recuperación de registros interrumpidos. Las solicitudes simultáneas de una canción comparten el mismo trabajo. Los archivos se validan y publican al terminar; las descargas parciales se limpian al cancelar.
- Reproducir un resultado remoto descarga el audio completo antes de reproducirlo. Precarga opcional de la siguiente canción y descarga de álbum completo. Deezer/iTunes/MusicBrainz proporcionan metadatos; para esos resultados yt-dlp busca el audio por artista y título. La coincidencia depende del proveedor.
- Portadas en el reproductor y álbumes del catálogo, con caché en disco. Letras LRCLib y archivos `.lrc`/`.txt` vecinos al audio; letras sincronizadas con desplazamiento y seek al hacer doble clic.
- Mini reproductor, reproductor ampliado con pantalla completa y estilos vinilo/circular. El círculo es una animación decorativa, no un analizador de frecuencias. El control RPM cambia la animación del disco.
- Bandeja del sistema cuando el escritorio la ofrece. MPRIS 2 para controles de GNOME/KDE, teclas multimedia y `playerctl`; no requiere un widget móvil.

## Datos y portabilidad de la biblioteca

Por defecto se guardan en `${XDG_DATA_HOME:-~/.local/share}/musicvault/`: base SQLite, descargas y caché de portadas. Las letras remotas se guardan en la base de datos. La carpeta de descargas se puede cambiar en Ajustes. Los archivos importados permanecen en su ubicación original.

Para llevar los datos en una carpeta elegida:

```bash
./run.sh --data-dir /ruta/MusicVault-datos
```

Los audios importados y descargados se referencian mediante rutas absolutas. Mover solamente la base de datos no traslada esos archivos ni reescribe sus rutas. La exportación de Ajustes respalda la base de datos; copia la música por separado y conserva sus rutas, o vuelve a importarla en el destino.

Las bases Android/iOS y sus URI no se importan automáticamente. Para llevar música de Android, copia los archivos de audio a Linux y usa Importar. El código móvil original se conserva en `../android/app/` e `../ios/`.

### Usar SongMirror con una playlist de Spotify

1. En MusicVault abre **Importar música → Agregar cuenta de Spotify para SongMirror…**.
2. Pulsa **Iniciar sesión con Spotify** y completa el acceso en el navegador integrado. Una cuenta gratuita funciona.
3. MusicVault comprueba la sesión y guarda solamente el valor que necesita SongMirror en un archivo privado con permisos `0600`; no forma parte de las copias de la base de datos.
4. Usa **Importar música → Playlist de Spotify mediante SongMirror…** y pega el enlace.

El conector de solo lectura está incluido en MusicVault, por lo que no hace falta instalar SongMirror ni mantener un servidor local. El navegador integrado usa un perfil temporal y descarta sus datos al cerrar; MusicVault no lee ni almacena la contraseña. Cada laptop conserva solamente su propia cookie de sesión y el usuario puede eliminarla desde **Importar música → Administrar cuenta de Spotify para SongMirror… → Desconectar**.

## Atajos

| Atajo | Acción |
| --- | --- |
| Ctrl+O | Importar música |
| Ctrl+F | Buscar |
| Ctrl+P | Reproducir/pausar |
| Alt+→ / Alt+← | Siguiente/anterior |
| Ctrl+L | Cola |
| Ctrl+Q | Salir, incluyendo la bandeja |

## Desarrollo y verificación

```bash
sudo apt install python3-pytest dbus-x11 libglib2.0-bin
/usr/bin/python3 -m pytest -q tests
dbus-run-session -- env MUSICVAULT_TEST_DBUS=1 QT_QPA_PLATFORM=offscreen \
  /usr/bin/python3 -m pytest -q tests
QT_QPA_PLATFORM=offscreen ./run.sh --smoke-test \
  --data-dir /tmp/musicvault-smoke --screenshot /tmp/musicvault.png
```

Las pruebas de audio usan libmpv real con salida `null` y archivos WAV generados para comprobar decodificación, pausa, seek, final de pista, crossfade, ecualizador y cola. Las de red/descarga usan respuestas y procesos controlados: prueban el comportamiento del cliente sin descargar música externa. La prueba MPRIS llama al servicio desde otro proceso en un bus privado. El diagnóstico y la prueba de arranque no acreditan sonido audible en una tarjeta física ni compatibilidad con todas las distribuciones.

## Estructura

| Archivo | Responsabilidad |
| --- | --- |
| `musicvault/database.py` | Persistencia y copias SQLite |
| `musicvault/library.py` | Escaneo, etiquetas y portadas locales |
| `musicvault/player.py` | Audio libmpv, crossfade, cola y efectos |
| `musicvault/services.py` | Trabajos en segundo plano, catálogos, letras y descargas |
| `musicvault/window.py`, `layout.py`, `widgets.py` | Interfaz Qt de escritorio |
| `musicvault/track_model.py` | Tabla virtual y caché de miniaturas |
| `musicvault/mpris.py` | Integración multimedia D-Bus |
| `packaging/build.py` | Paquete amd64 y archivo reubicable |

Referencias técnicas: [API de libmpv](https://github.com/mpv-player/mpv/blob/master/include/mpv/client.h), [manual de mpv](https://mpv.io/manual/stable/), [MPRIS 2](https://specifications.freedesktop.org/mpris/latest/Player_Interface.html), [Qt](https://doc.qt.io/qt-6/).

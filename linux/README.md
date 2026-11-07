# MusicVault para Linux x86_64

La versión 0.9.7 simplifica la importación: Spotify se administra únicamente desde Ajustes, se elimina el formulario manual de playlists porque los enlaces se autodetectan, y la importación de música local se mueve a Ajustes → Biblioteca y almacenamiento mediante selección de una carpeta completa.
La versión 0.9.7 rediseña por completo Ajustes, elimina el ecualizador y conserva las mejoras de interfaz de 0.9.2: convierte Play/Pausa en un control circular real, simplifica la barra lateral eliminando el encabezado redundante “TU BIBLIOTECA” y el acceso separado “Descargas”, mueve crear colección junto a “TUS COLECCIONES”, elimina textos explicativos persistentes, simplifica Buscar a campo + botón, desactiva la búsqueda remota mientras se escribe, unifica el estilo de botones/listas desplegables/menús y permite saltar con un clic a cualquier punto de la barra de reproducción.

Aplicación de escritorio nativa en español, con Qt 6, libmpv y SQLite. No requiere Android, Java, emulador ni navegador. Esta versión utiliza Python 3 para la lógica y bibliotecas nativas para la interfaz y el audio.

## Ejecutar desde este repositorio

Todos los comandos siguientes se ejecutan desde la carpeta `linux/` (`cd linux` desde la raíz del repositorio).

```bash
./run.sh --doctor
./run.sh
```

El lanzador usa `/usr/bin/python3` para encontrar los paquetes instalados por la distribución, independientemente de pyenv o de un entorno virtual activo. Puedes elegir otro intérprete con `MUSICVAULT_PYTHON=/ruta/python`.


La versión 0.9.7 incorpora el modelo de reproducción de Android: streaming inmediato para resultados remotos, caché temporal de 24 h/1 GB reutilizable al descargar, preparación secuencial de las canciones visibles, búsqueda unificada Canciones/Álbumes/Playlists, playlists de YouTube reproducibles e importables sin descarga y menús donde Descargar es siempre una acción explícita. El onboarding ya no pregunta gustos cuando existe música local o descargada: usa la biblioteca como señal inicial para las recomendaciones.

La versión 0.9.7 conserva la distribución inspirada en Spotify de escritorio: barra lateral permanente con playlists, búsqueda superior, accesos rápidos, estantes de portadas y controles inferiores con una barra de progreso amplia. Conserva la paleta carbón, ámbar y marfil, las fuentes originales y la tornamesa HV-01. Se adapta desde 920 × 680 hasta ventanas amplias. La biblioteca muestra únicamente archivos locales disponibles; los estados de descarga y reintento siguen disponibles desde las acciones y avisos correspondientes, sin ocupar una sección separada en la barra lateral. Un archivo descargado se reutiliza aunque se reproduzca desde resultados antiguos del catálogo.

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
- La búsqueda remota solo se ejecuta al pulsar Enter o Buscar; cambiar el texto únicamente cancela trabajos anteriores y prepara el estado local de la interfaz.
- La selección se conserva al actualizar; cambiar de canción repinta solo las filas implicadas. El progreso de descarga actualiza su elemento sin reconstruir la lista.
- La animación del vinilo detiene su temporizador cuando está oculto o termina de volver a reposo. Las letras ocultas no se desplazan en segundo plano.

Consulta los resultados y límites de la [verificación local](VERIFICATION.md).

## Instalar o distribuir

Generar los paquetes sin root ni conexión a Internet:

```bash
python3 packaging/build.py
```

Produce:

- `dist/musicvault_0.9.7_amd64.deb`: instala el lanzador, icono y entrada del menú de aplicaciones. Declara las dependencias para que APT pueda resolverlas.
- `dist/MusicVault-0.9.7-linux-x86_64.tar.gz`: distribución reubicable del código y recursos, con un lanzador `MusicVault`. **Requiere las dependencias del sistema; no es un binario autocontenido ni un AppImage.**

Instalar el paquete Debian/Ubuntu:

```bash
sudo apt install ./dist/musicvault_0.9.7_amd64.deb
musicvault
```

Extraer y ejecutar la distribución reubicable:

```bash
tar -xzf dist/MusicVault-0.9.7-linux-x86_64.tar.gz
./MusicVault-0.9.7-linux-x86_64/MusicVault
```

El paquete no incluye ejecutables de audio compilados en este equipo: utiliza las bibliotecas de la distribución de destino. No hay dependencia de una versión de glibc impuesta por un ejecutable compilado aquí.

## Funciones

- Importación de archivos o carpetas, arrastrar y soltar, etiquetas y portadas incrustadas. MP3, FLAC, Ogg, Opus, M4A, AAC, WAV, AIFF y otros formatos reconocidos por Mutagen/libmpv.
- Escaneo al arrancar y cada cinco minutos de las carpetas elegidas. Duración mínima configurable. Quitar una canción no elimina el archivo ni permite que un escaneo la recupere; una importación explícita sí puede recuperarla.
- Biblioteca, búsqueda local, favoritos, historial de reproducción, playlists y álbumes personalizados: crear, renombrar, añadir, quitar y ordenar canciones.
- Reproducción local completa; cola editable, anterior/siguiente, seek, aleatorio y repetición de una canción o de toda la cola. La cola se restaura pausada al arrancar.
- Crossfade de 0–12 segundos con dos instancias reales de libmpv. Pausar durante la transición pausa ambos audios. Las pistas muy cortas y las pendientes de descarga usan transición normal.
- Temporizador de sueño. Continuación opcional con canciones relacionadas de la biblioteca; prioriza álbum, artista y favoritos.
- Búsqueda en Deezer con alternativas iTunes/MusicBrainz y refinamiento de YouTube; pestañas **Canciones | Álbumes | Playlists**. Las playlists de YouTube pueden abrirse, reproducirse por streaming y guardarse en MusicVault sin descargar sus canciones. Historial con autocompletado y caché de resultados.
- Importación de Spotify mediante detección automática de enlaces copiados. La cuenta se conecta o administra desde **Ajustes → Cuenta**; al copiar una canción o playlist compatible, MusicVault ofrece importarla sin un formulario manual separado.
- Biblioteca con filtros por artista y álbum, orden por fecha/título/artista/álbum y columnas redimensionables que conservan su ancho.
- Al descargar un álbum completo se guarda también como álbum en **Álbumes**; cada canción terminada aparece individualmente en **Biblioteca**.
- Descargas explícitas mediante yt-dlp/FFmpeg, progreso individual, cancelación, reintento y recuperación de registros interrumpidos. Las solicitudes simultáneas de una canción comparten el mismo trabajo y una descarga puede promocionarse desde la caché temporal de streaming sin redescargar el audio. Los archivos se validan y publican al terminar.
- Reproducir un resultado remoto usa streaming: MusicVault resuelve el audio de YouTube, lo entrega directamente a libmpv y prepara secuencialmente las canciones más probables mientras el usuario mira la pantalla. La caché temporal conserva hasta 1 GB durante 24 h; si después eliges **Descargar**, se reutiliza el audio cacheado cuando está completo en vez de volver a pedirlo. Deezer/iTunes/MusicBrainz siguen aportando metadatos y yt-dlp resuelve la fuente reproducible.
- Portadas en el reproductor y álbumes del catálogo, con caché en disco. Letras LRCLib y archivos `.lrc`/`.txt` vecinos al audio; letras sincronizadas con desplazamiento y seek al hacer doble clic.
- Mini reproductor, reproductor ampliado con pantalla completa y estilos vinilo/circular. El círculo es una animación decorativa, no un analizador de frecuencias. El control RPM cambia la animación del disco.
- Bandeja del sistema cuando el escritorio la ofrece. MPRIS 2 para controles de GNOME/KDE, teclas multimedia y `playerctl`; no requiere un widget móvil.

## Datos y portabilidad de la biblioteca

Por defecto se guardan en `${XDG_DATA_HOME:-~/.local/share}/musicvault/`: base SQLite, descargas, caché de portadas y caché temporal de streaming. Esta última se limita a 1 GB y caduca tras 24 h sin uso; puede limpiarse desde Ajustes. Las letras remotas se guardan en la base de datos. La carpeta de descargas se puede cambiar en Ajustes. Los archivos importados permanecen en su ubicación original.

Para llevar los datos en una carpeta elegida:

```bash
./run.sh --data-dir /ruta/MusicVault-datos
```

Los audios importados y descargados se referencian mediante rutas absolutas. Mover solamente la base de datos no traslada esos archivos ni reescribe sus rutas. La exportación de Ajustes respalda la base de datos; copia la música por separado y conserva sus rutas, o vuelve a importarla en el destino.

Las bases Android/iOS y sus URI no se importan automáticamente. Para llevar música de Android, copia los archivos de audio a Linux y usa Importar. El código móvil original se conserva en `../android/app/` e `../ios/`.

### Importar desde Spotify

1. En MusicVault abre **Ajustes → Cuenta → Spotify → Conectar**.
2. Pulsa **Iniciar sesión con Spotify** y completa el acceso en el navegador integrado. Una cuenta gratuita funciona.
3. MusicVault comprueba la sesión y guarda solamente la cookie de sesión necesaria en un archivo privado con permisos `0600`; no forma parte de las copias de la base de datos.
4. Copia el enlace de una canción o playlist de Spotify; MusicVault lo detectará y ofrecerá importarlo.

El conector de solo lectura está incluido en MusicVault y no requiere instalar servicios adicionales ni mantener un servidor local. El navegador integrado usa un perfil temporal y descarta sus datos al cerrar; MusicVault no lee ni almacena la contraseña. Cada laptop conserva solamente su propia cookie de sesión y el usuario puede eliminarla desde **Ajustes → Cuenta → Spotify → Administrar → Desconectar**.

## Atajos

| Atajo | Acción |
| --- | --- |
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

Las pruebas de audio usan libmpv real con salida `null` y archivos WAV generados para comprobar decodificación, pausa, seek, final de pista, crossfade y cola. Las de red/descarga usan respuestas y procesos controlados: prueban el comportamiento del cliente sin descargar música externa. La prueba MPRIS llama al servicio desde otro proceso en un bus privado. El diagnóstico y la prueba de arranque no acreditan sonido audible en una tarjeta física ni compatibilidad con todas las distribuciones.

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

## JAMS compartidas, amigos e identidad de instalacion

Disponible desde **JAMS / Amigos**. Consulta [la implementacion y verificacion](../DESKTOP_JAMS.md) para compatibilidad con Android, dependencias y limites. Desde esta carpeta ejecuta `python packaging/setup_jams.py` para instalar el cliente Firebase fijado.

El onboarding incluye el catalogo completo de generos de Android (2,234 opciones combinadas), buscador local y artistas por genero con paginas de 20 resultados. Las fotos y las paginas consultadas se guardan en cache; la seleccion se conserva al filtrar y cargar mas artistas.

Los nombres de artista de la tabla y del reproductor abren su perfil: foto, canciones destacadas, busqueda de canciones, albumes paginados y pistas de cada album. Desde el perfil puedes reproducir, descargar, anadir a cola y administrar Me gusta. Tambien esta disponible en el menu contextual de las canciones.

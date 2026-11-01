# MusicVault para Discord

Bot con búsqueda por nombre y enlaces individuales de YouTube. Reutiliza los módulos de streaming, yt-dlp, modelos y caché SQLite de MusicVault; no necesita Qt ni la biblioteca personal de escritorio.

Comandos: `/reproducir consulta`, `/elegir numero`, `/cola`, `/pausa`, `/seguir`, `/saltar`, `/salir`.
La búsqueda por nombre muestra cinco resultados: selecciona uno en el menú o usa `/elegir numero` en los dos minutos siguientes. Cada lista pertenece a quien la pidió. Los enlaces individuales de YouTube se añaden directamente.

La cola admite 100 canciones pendientes, se pierde al reiniciar y se desconecta tras tres minutos sin canciones. Los controles requieren estar en el mismo canal de voz. Solo admite canales de voz normales y, por defecto, una sesión de música simultánea para limitar consumo en el plan gratuito. No importa playlists ni Spotify en esta primera versión. Los enlaces directos muestran el ID del vídeo como título.

## Instancia activa y respaldo local

La instancia activa vuelve a ejecutarse en KataBump mediante `warp_main.py`, con salida WARP verificada. Se probó la extracción y descarga/decodificación real del enlace que fallaba por la salida directa. El proxy y las credenciales WARP residen en el servidor; no depende de que este equipo permanezca encendido. Sigue siendo necesaria la renovación del plan gratuito cada cuatro días.

El servicio local `musicvault-discord.service` quedó detenido y deshabilitado para evitar dos instancias con el mismo token. Se conserva como respaldo. Para utilizarlo, detén primero la instancia de KataBump y ejecuta `systemctl --user start musicvault-discord`. Para volver al servidor, detén el local antes de arrancar KataBump. Registros locales: `journalctl --user -u musicvault-discord -f`. La configuración privada está en `.env` con permisos 0600.

## Subir a KataBump

1. Crea un servidor **Python** gratuito en https://dashboard.katabump.com y selecciona **Python 3.12** en Startup.
2. Genera el paquete desde este repositorio con `python3 discord-bot/build_katabump.py`.
3. En Files de https://control.katabump.com sube `dist/musicvault-katabump.zip` y usa Unarchive. `main.py` y `requirements.txt` deben quedar directamente en `/home/container`.
4. En Startup, configura **PY FILE = main.py**. KataBump instala `requirements.txt` automáticamente.
5. Crea `.env` en el panel a partir de `.env.example`. Pon el token de tu bot en `DISCORD_TOKEN` y el ID de tu servidor en `DISCORD_GUILD_ID` para sincronizar los comandos en ese servidor. No incluyas el archivo `.env` en Git ni en el ZIP.
6. En https://discord.com/developers/applications crea una aplicación y su bot. En OAuth2 / URL Generator selecciona `bot` y `applications.commands`, con permisos View Channels, Send Messages, Connect y Speak. Abre el enlace de invitación y añade el bot a tu servidor. No necesita Message Content Intent.
7. Para comprobar herramientas sin token, selecciona temporalmente `preflight.py` como PY FILE y pulsa Start; el diagnóstico termina al completarse. Luego restablece **PY FILE = main.py**.
8. Pulsa Start. Entra a un canal de voz y ejecuta `/reproducir artista canción`.

El paquete instala FFmpeg y Node.js en el espacio del usuario mediante wheels de Python; no requiere `apt` ni permisos de administrador. Estas dependencias incluyen binarios de terceros. El servidor debe permitir conexiones salientes HTTPS y de voz UDP a Discord.

El plan gratuito publica **308 MB RAM, 716 MB de almacenamiento y 25% CPU**. Hay que **renovarlo manualmente cada cuatro días**. Tras caducar, KataBump suspende el servidor y anuncia la eliminación de sus archivos un día después. Recursos y condiciones pueden cambiar. Un bot musical puede superar la RAM al resolver/transcodificar; la reproducción y el consumo deben comprobarse en el servidor real antes de darlo por operativo. YouTube también puede rechazar las IP del alojamiento; no se garantiza la disponibilidad de las fuentes.

Fuentes de despliegue: [guía Python](https://docs.katabump.com/guides/python/), [límites](https://docs.katabump.com/faq/), [renovación](https://docs.katabump.com/hosting/renew-your-server/).

## Desarrollo local

Desde `discord-bot/`, crea un venv e instala `pip install -r requirements.txt`. Copia `.env.example` a `.env`, configura el token y ejecuta `python main.py`. En el repositorio usa los proveedores de Linux o Windows; el ZIP incluye los de Linux. Datos independientes en `.data/`; se puede cambiar con `MUSICVAULT_BOT_DATA_DIR`. `MUSICVAULT_MAX_PLAYERS` permite aumentar sesiones si el alojamiento dispone de recursos.

Pruebas sin Discord: `python -m unittest discover -s tests -v`.
Diagnóstico sin iniciar sesión: `python main.py --check`.

El ZIP contiene exclusivamente archivos permitidos por `build_katabump.py`: no incluye tokens, bases de datos, música, venv ni perfiles de navegador.


## WARP en el contenedor de KataBump

Se comprobó que el contenedor no dispone de `/dev/net/tun` ni capacidades de red. La alternativa usa un proxy HTTP en `127.0.0.1:40000`, con WireGuard en espacio de usuario: `wgcf` 2.3.1 registra el dispositivo WARP y `wireproxy` 1.1.3 transporta sus conexiones. Son clientes comunitarios, no el cliente oficial de Cloudflare. `warp_setup.py` descarga versiones fijadas y comprueba sus SHA-256 antes de ejecutarlas. Credenciales y binarios se guardan en `.data/warp`, excluida del ZIP y de Git; claves y registros de configuración tienen permisos privados.

1. Sube y extrae el paquete del bot. Selecciona temporalmente **PY FILE = warp_setup.py** y arranca una vez. Espera `WARP_SETUP_OK`.
2. Selecciona **PY FILE = warp_main.py** para el arranque normal.
3. El supervisor exige `warp=on` antes de iniciar Discord. Si el proxy termina, detiene el bot. El proxy escucha únicamente en localhost.
4. `MUSICVAULT_MEDIA_PROXY` configura las variables de proxy que hereda yt-dlp y el argumento explícito `-http_proxy` de FFmpeg. Ambos deben usar la misma salida, porque las URLs de audio pueden depender de ella. El transporte de Discord continúa directo.

Se verificaron extracción y descarga/decodificación real del enlace `Mmw-PpWhKFg` desde KataBump por WARP. Es una comprobación puntual: YouTube puede volver a restringir una IP de salida. El plan gratuito sigue necesitando renovación manual cada cuatro días.

Fuentes: [wgcf](https://github.com/ViRb3/wgcf), [wireproxy](https://github.com/windtf/wireproxy), [modos WARP](https://developers.cloudflare.com/warp-client/warp-modes/).

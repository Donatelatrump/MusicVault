#!/usr/bin/env python3
"""Extract safe static UI literals. Run with --include-root once those screens are stable.
No Kotlin templates, identifiers, MIME types, routes or preferences keys are rewritten.
English is authored for the dictionary below; unlisted legacy explanations retain Spanish fallback.
"""
from pathlib import Path
import argparse, hashlib, json, re, unicodedata, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main'
TRANSLATIONS = {
'Ajustes':'Settings', 'Cerrar':'Close', 'Cancelar':'Cancel', 'Guardar':'Save', 'Atrás':'Back',
'Reproducir':'Play', 'Pausar':'Pause', 'Reproduciendo':'Playing', 'Pausada':'Paused', 'Descargada':'Downloaded',
'Descargar':'Download', 'Cancelar descarga':'Cancel download', 'Más opciones':'More options',
'Agregar a cola':'Add to queue', 'Agregar a cola de reproducción':'Add to playback queue',
'Me gusta':'Like', 'Quitar de Me gusta':'Unlike', 'Agregar a álbum':'Add to album',
'Iniciar radio':'Start radio', 'Iniciar radio de esta canción':'Start radio from this song',
'Quitar de esta playlist':'Remove from this playlist', 'Eliminar canción descargada':'Delete downloaded song',
'Eliminar canción':'Delete song', 'Eliminar álbum':'Delete album', 'Eliminar playlist':'Delete playlist',
'Crear nuevo álbum':'Create new album', '+  Crear nuevo álbum':'+ Create new album', 'Crear y agregar':'Create and add',
'Quitar de la playlist':'Remove from playlist', 'No disponible sin conexión':'Unavailable offline',
'Reproducir canción':'Play song', 'Abrir playlist':'Open playlist', 'Abrir álbum':'Open album',
'Álbumes':'Albums', 'Nuevo álbum':'New album', 'Sin seleccionar':'Not selected', 'Seleccionada':'Selected',
'Seleccionar':'Select', 'Quitar selección':'Deselect', 'Duración desconocida':'Unknown duration',
'Para ti':'For you', 'Recomendaciones para ti':'Recommended for you', 'Álbumes para ti':'Albums for you',
'Reproducción y dispositivos':'Playback and devices', 'Saltar silencios':'Skip silence',
'ReplayGain / nivel local':'ReplayGain / local leveling',
'Usa las etiquetas ReplayGain de la canción, con límite de pico y sin amplificación. Sin etiquetas, mide el nivel RMS local y atenúa hasta -18 dBFS; las transmisiones sin etiquetas conservan su volumen.':'Uses track ReplayGain tags with peak protection and no amplification. Without tags, measures local RMS and attenuates to -18 dBFS; untagged streams keep their original volume.',
'Con crossfade en 0, la cola preparada usa reproducción continua del motor y respeta delay/padding disponibles en el archivo.':'With crossfade at 0, the prepared queue uses continuous playback and honors the delay and padding available in the file.',
'Chromecast no está disponible en este dispositivo. Comprueba Google Play Services.':'Chromecast is unavailable on this device. Check Google Play Services.',
'Elegir dispositivo Chromecast':'Choose Chromecast device', 'Dispositivo conectado':'Device connected',
'Elige un dispositivo en la misma red Wi-Fi.':'Choose a device on the same Wi-Fi network.',
'Enviar canción actual':'Cast current song', 'Reproducir / pausar':'Play / pause', 'Volver al teléfono':'Return to phone',
'Reiniciar canción en Chromecast':'Restart song on Chromecast',
'Solo URLs HTTP/HTTPS accesibles por el receptor. Los archivos locales permanecen en el teléfono.':'Only HTTP/HTTPS URLs accessible to the receiver. Local files stay on the phone.',
'Conecta el teléfono para navegar y reproducir la música local disponible. Los archivos ausentes no aparecen.':'Connect your phone to browse and play available local music. Missing files are hidden.',
'El reloj puede controlar la sesión multimedia activa del teléfono desde su control de medios. La música se reproduce en el teléfono.':'The watch can control the phone’s active media session using its media controls. Music plays on the phone.',
'Texto o LRC [mm:ss.xx]. Un desfase positivo retrasa las líneas.':'Text or LRC [mm:ss.xx]. A positive offset delays the lines.',
'Letras':'Lyrics', 'Desfase en milisegundos (±30000)':'Offset in milliseconds (±30000)',
'Introduce un desfase entre -30000 y 30000.':'Enter an offset between -30000 and 30000.',
'CONEXIÓN Y HERRAMIENTAS':'CONNECTION AND TOOLS', 'Modo sin conexión':'Offline mode',
'Busca y reproduce archivos disponibles en este dispositivo.':'Browse and play files available on this device.',
'Reproducción y Chromecast':'Playback and Chromecast', 'Almacenamiento, M3U y respaldos':'Storage, M3U and backups',
'Proveedores, fallos y ListenBrainz':'Providers, diagnostics and ListenBrainz', 'APARIENCIA':'APPEARANCE',
'Diseño de la aplicación':'App layout', 'Estilo del reproductor':'Player style', 'REPRODUCCIÓN':'PLAYBACK',
'ALMACENAMIENTO':'STORAGE', 'Ruta de descargas':'Download folder', 'Elegir carpeta':'Choose folder',
'Usar carpeta interna':'Use internal folder', 'Conectar Spotify':'Connect Spotify', 'Desconectar Spotify':'Disconnect Spotify',
'Calidad del audio':'Audio quality', 'Descargar solo por Wi-Fi':'Download over Wi-Fi only',
'Se aplica a nuevas descargas y reintentos.':'Applies to new downloads and retries.',
'ESPACIO EN EL DISPOSITIVO':'DEVICE STORAGE', 'Límite de caché':'Cache limit', 'Vaciar caché':'Clear cache',
'Actualizar espacio':'Refresh storage', 'RESPALDO DE BIBLIOTECA':'LIBRARY BACKUP',
'MIGRAR DE DEBUG A RELEASE':'MIGRATE FROM DEBUG TO RELEASE', 'Crear paquete para release':'Create release migration package',
'Importar paquete de debug':'Import debug package', 'Vaciar caché de streaming':'Clear streaming cache',
'Pausa la reproducción antes de vaciar la caché.':'Pause playback before clearing the cache.', 'Caché vaciada.':'Cache cleared.',
'Límite de caché actualizado.':'Cache limit updated.', 'No se pudo completar la operación.':'Could not complete the operation.',
'La caché acelera el streaming. Las canciones descargadas se guardan por separado.':'Cache speeds up streaming. Downloaded songs are stored separately.',
'Los archivos de audio y las sesiones de Spotify no se incluyen.':'Audio files and Spotify sessions are not included.',
'Se borrará el audio temporal. Tus canciones descargadas y playlists se conservarán.':'Temporary audio will be deleted. Downloaded songs and playlists will be kept.',
'Respaldo guardado. Incluye biblioteca, playlists, favoritos y ajustes; los archivos de audio se conservan aparte.':'Backup saved. It includes your library, playlists, favorites and settings; audio files are stored separately.',
'Exporta playlists, favoritos, canciones y ajustes. Al importar se combinan con tu biblioteca actual y se restauran los ajustes incluidos.':'Export playlists, favorites, songs and settings. Import merges them into your current library and restores the included settings.',
'Copia tus canciones y biblioteca a un archivo de migración. Guárdalo fuera de la carpeta de la app y mantén debug instalada hasta comprobar la release.':'Copy your songs and library into a migration file. Save it outside the app folder and keep debug installed until you have checked the release.',
'Selecciona el paquete creado en debug. Se copiarán las canciones a esta instalación y se combinarán playlists, favoritos y ajustes.':'Select the package created in debug. Songs will be copied into this installation and playlists, favorites and settings will be merged.',
'Proveedores y privacidad':'Providers and privacy', 'Extractor yt-dlp':'yt-dlp extractor', 'Comprobando…':'Checking…',
'Comprobar actualización':'Check for updates', 'Diagnóstico local':'Local diagnostics',
'Medir fluidez localmente':'Measure responsiveness locally', 'Exportar diagnóstico':'Export diagnostics',
'Borrar eventos locales':'Clear local events', 'Eventos locales borrados':'Local events cleared',
'Diagnóstico guardado':'Diagnostics saved', 'No se pudo guardar. Vuelve a elegir un documento.':'Could not save. Choose a document again.',
'Token de ListenBrainz':'ListenBrainz token', 'Obtén tu token en listenbrainz.org/settings/ e introdúcelo aquí.':'Get your token at listenbrainz.org/settings/ and enter it here.',
'No se pudo conectar. Comprueba tu conexión y token.':'Could not connect. Check your connection and token.',
'Conectando…':'Connecting…', 'Conectar y compartir escuchas':'Connect and share listens',
'ListenBrainz desconectado':'ListenBrainz disconnected', 'Desconectar y borrar cola':'Disconnect and clear queue',
'La comprobación automática ocurre cada 24 horas. La actualización espera a que terminen las extracciones y conserva el extractor anterior si falla.':'Automatic checks run every 24 hours. Updates wait for active extractions to finish and preserve the previous extractor if they fail.',
'Exporta versión, API de Android y eventos técnicos recientes. No incluye cookies, cuentas, títulos de canciones, rutas ni enlaces de audio. Solo se guarda en el documento que elijas.':'Exports version, Android API and recent technical events. It excludes cookies, accounts, song titles, paths and audio links. It is saved only to the document you choose.',
'Opcional: guarda conteos de frames lentos y duración agregada por pantalla mientras la app está visible. No guarda contenido ni trazas de cada frame y no envía datos. El informe puede incluir la clase del último cierre inesperado y símbolos del código, sin el mensaje del error.':'Optional: saves slow-frame counts and total time per screen while the app is visible. It saves no content or individual frame traces and sends no data. The report may include the last unexpected crash class and code symbols, without the error message.',
'Al conectar, compartes artista, canción, álbum y hora de escucha con ListenBrainz. Una escucha cuenta tras media canción o 4 minutos. Sin conexión se conserva una cola local; desconectar borra la credencial y la cola.':'Connecting shares artist, song, album and listen time with ListenBrainz. A listen counts after half the song or 4 minutes. An offline queue is kept; disconnecting deletes the credential and queue.',
'Administrar biblioteca':'Manage library', 'Cancelar operación':'Cancel operation', 'Playlist importada':'Imported playlist',
'Playlists M3U / M3U8':'M3U / M3U8 playlists', 'Nombre al importar':'Imported playlist name',
'Seleccionar carpeta de música':'Choose music folder', 'Cambiar carpeta de música':'Change music folder',
'Importar playlist':'Import playlist', 'Deshacer retiradas':'Undo removals', 'Vaciar papelera':'Empty trash',
'Detectar audio duplicado':'Find duplicate audio', 'Conservar primero y retirar duplicados':'Keep first and remove duplicates',
'Descargas automáticas':'Automatic downloads', 'Cuota total de audio en MB':'Total audio quota in MB',
'Selección de descargas guardada.':'Download selection saved.', 'Guardar descargas automáticas':'Save automatic downloads',
'Sin ejecución todavía':'Not run yet', 'Respaldo portable':'Portable backup',
'Contraseña opcional / de restauración':'Optional / restore password', 'Guardar respaldo ahora':'Save backup now',
'Restaurar respaldo':'Restore backup', 'Exportar paquete con audio para release':'Export audio package for release',
'Restaurar paquete con audio de debug':'Restore audio package from debug', 'Respaldos a conservar (1–30)':'Backups to keep (1–30)',
'Intervalo en días (1–30)':'Interval in days (1–30)', 'Seleccionar carpeta y programar':'Choose folder and schedule',
'Ejecutar respaldo programado':'Run scheduled backup', 'Desactivar programación':'Disable schedule',
'Eliminar definitivamente':'Delete permanently', 'Retirar audio':'Remove audio',
'Respaldo programado desactivado.':'Scheduled backup disabled.', 'Respaldo añadido a trabajos en segundo plano.':'Backup added to background work.',
'Respaldo guardado. Conserva la contraseña para otro dispositivo.':'Backup saved. Keep the password to restore on another device.',
'Respaldo programado. Guarda la contraseña fuera de este dispositivo.':'Backup scheduled. Store the password outside this device.',
'No se pudo conservar el permiso de música.':'Could not retain access to the music folder.',
'Operación cancelada; los cambios de restauración pendientes se revirtieron.':'Operation canceled; pending restore changes were rolled back.',
'Rutas relativas: selecciona su carpeta de música. Se importan archivos accesibles; no se descargan URLs remotas. Las URIs exportadas requieren permisos en el dispositivo de destino.':'For relative paths, select the music folder. Accessible files are imported; remote URLs are not downloaded. Exported URIs require permission on the destination device.',
'El audio compartido se cuenta una sola vez. Retirar audio guarda los archivos privados en la papelera y conserva playlists/favoritos. Libera espacio vaciándola. Archivos ajenos a la app y audio compartido con otras colecciones se conservan.':'Shared audio is counted once. Removing audio moves private files to trash and preserves playlists and favorites. Empty trash to free space. Files outside the app and audio shared with other collections are kept.',
'Solo con Wi-Fi y cargando. La selección se revisa cada 6 horas y mantiene las descargas existentes al desactivarla. Cuota estimada conservadora a 320 kbps; incluye audio local y trabajos pendientes.':'Only on Wi-Fi while charging. Selections are checked every 6 hours. Disabling keeps existing downloads. The conservative quota estimate uses 320 kbps and includes local audio and pending jobs.',
'Incluye biblioteca, orden, favoritos y ajustes portables. No incluye audio, cookies ni permisos SAF. Contraseña opcional (mínimo 8 caracteres); necesaria para restaurar en otro dispositivo.':'Includes your library, ordering, favorites and portable settings. Excludes audio, cookies and SAF permissions. A password is optional (at least 8 characters); keep it to restore on another device.',
'El audio retirado se eliminará definitivamente. Las referencias de biblioteca permanecen.':'Removed audio will be permanently deleted. Library references are kept.',
'Se guardará el audio privado seleccionado en la papelera. Puedes recuperarlo hasta vaciarla.':'Selected private audio will be moved to trash. You can recover it until the trash is emptied.',
}
extra_translations = ROOT / 'tools/ui_translations_en.json'
if extra_translations.exists(): TRANSLATIONS.update(json.loads(extra_translations.read_text()))
NATIVE = set(TRANSLATIONS) | {'Podcast','ASMR','Otro','Nombre','YouTube'}
EXCLUDE = {'MusicVaultApp.kt','MusicVaultViewModel.kt','PlayerScreen.kt','SearchScreen.kt'}
TOOL_FILES = {'PlaybackToolsScreen.kt','LibraryToolsSettings.kt','ProviderToolsScreen.kt','LibraryManagementScreen.kt','LyricsEditorDialog.kt'}

# Kotlin lexer: consumes templates as a single token, including nested quoted expressions.
def strings(source):
    i=0
    while i < len(source):
        if source.startswith('//',i):
            end=source.find('\n',i); i=len(source) if end<0 else end+1; continue
        if source.startswith('/*',i):
            end=source.find('*/',i+2); i=len(source) if end<0 else end+2; continue
        if source.startswith('"""',i):
            end=source.find('"""',i+3); i=len(source) if end<0 else end+3; continue
        if source[i]!='"': i+=1; continue
        start=i; i+=1; depth=0
        while i<len(source):
            if source[i]=='\\': i+=2; continue
            if source.startswith('${',i):
                depth=1; i+=2
                while i<len(source) and depth:
                    if source[i]=='"':
                        i+=1
                        while i<len(source):
                            if source[i]=='\\':i+=2;continue
                            if source[i]=='"': i+=1;break
                            i+=1
                        continue
                    if source[i]=='{':depth+=1
                    elif source[i]=='}':depth-=1
                    i+=1
                continue
            if source[i]=='"': i+=1;break
            i+=1
        yield start,i,source[start+1:i-1]

def value(raw):
    return raw.replace('\\n','\n').replace('\\t','\t').replace('\\"','"').replace('\\\\','\\')
def resource_name(text):
    simple=unicodedata.normalize('NFKD',text).encode('ascii','ignore').decode().lower()
    slug=re.sub(r'[^a-z0-9]+','_',simple).strip('_')[:46].rstrip('_') or 'label'
    return 'ui_'+slug+'_'+hashlib.sha1(text.encode()).hexdigest()[:6]
def load_xml(path):
    if not path.exists():return {}
    return {item.attrib['name']: (item.text or '').replace("\\'", "'").replace('\\"', '"').replace('\\n', '\n').replace('\\\\', '\\') for item in ET.parse(path).getroot() if item.tag=='string'}
def escape(text):
    return text.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;').replace('\\','\\\\').replace("'","\\'").replace('"','\\"').replace('\n','\\n')
def save_xml(path,entries):
    path.parent.mkdir(parents=True,exist_ok=True)
    lines=['<?xml version="1.0" encoding="utf-8"?>','<resources>']
    for key,text in sorted(entries.items()):
        # formatted=false prevents literal percent signs being interpreted as positional placeholders.
        lines.append(f'    <string name="{key}" formatted="false">{escape(text)}</string>')
    path.write_text('\n'.join(lines+['</resources>','']))
def native_context(path,source,position):
    if path.name=='RemoteResultsAdapter.kt':
        if position < source.index('    private fun showSearchTrackMenu') and position >= source.index('    private class HeaderHolder'): return 'itemView.context'
        return 'context'
    if path.name=='FastFeedAdapters.kt':
        for start,end in [('internal class NativeTrackHolder','private class NativePlayButton'),('private class LocalAlbumHeaderHolder','private fun makeLocalAlbumHeader'),('private class AlbumCardHolder','private fun makeAlbumCard'),('internal class PickerHolder','private companion object {\n        const val PAYLOAD_CHECK'),('internal class Holder(itemView: View, onToggle:','private companion object { const val PAYLOAD_CHECK')]:
            a=source.find(start); b=source.find(end,a)
            if a>=0 and b>=0 and a<=position<b:return 'itemView.context'
    return 'context'

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--include-root',action='store_true');parser.add_argument('--files',nargs='*');args=parser.parse_args()
    es_path=BASE/'res/values/strings.xml';en_path=BASE/'res/values-en/strings.xml'
    es=load_xml(es_path);en=load_xml(en_path)
    files=[Path(f) for f in args.files] if args.files else list((BASE/'java/com/musicvault/app/ui').rglob('*.kt'))+[BASE/'java/com/musicvault/app/lyrics/LyricsEditorDialog.kt']
    changed=0
    for path in files:
        if not args.include_root and path.name in EXCLUDE:continue
        source=path.read_text();edits=[];uses_compose=False
        native=path.name in {'FastFeedAdapters.kt','RemoteResultsAdapter.kt','NativeUtilityAdapters.kt'}
        for start,end,raw in strings(source):
            if '$' in raw or not raw.strip():continue
            text=value(raw);before=source[max(0,start-800):start]
            composable=re.search(r'\b(?:Text|SettingsSection|SectionTitle)\(\s*$',before) is not None
            icon=(re.search(r'contentDescription\s*=\s*$',before) is not None and re.search(r'\bIcon\([^)]*$',before,re.S) is not None) or re.search(r'\bIcon\([^(),]*,\s*$',before) is not None
            tool=path.name in TOOL_FILES and (text in TRANSLATIONS or (re.search(r'[áéíóúñ¿¡]',text,re.I) or (len(text.split())>2 and not text.startswith(('%','http','audio/','application/')))))
            native_label=native and text in NATIVE
            if not any((composable,icon,tool,native_label)):continue
            key=resource_name(text);es[key]=text
            if text in TRANSLATIONS:en[key]=TRANSLATIONS[text]
            elif text in {'Chromecast','Android Auto','Wear OS','SPOTIFY','Spotify','YouTube','ListenBrainz','Crossfade','Podcast','ASMR'}:en[key]=text
            if tool:
                expr=f'context.getString(R.string.{key})'
            elif composable or icon:
                expr=f'stringResource(R.string.{key})';uses_compose=True
            else:
                expr=f'{native_context(path,source,start)}.getString(R.string.{key})'
            edits.append((start,end,expr))
        if edits:
            for a,b,expr in reversed(edits):source=source[:a]+expr+source[b:]
            imports=['import com.musicvault.app.R']
            if uses_compose:imports.append('import androidx.compose.ui.res.stringResource')
            additions=[imp for imp in imports if imp not in source]
            if additions:
                pos=source.find('\n',source.index('package '))+1
                source=source[:pos]+'\n'+'\n'.join(additions)+'\n'+source[pos:]
            path.write_text(source);changed+=len(edits)
    for key,text in es.items():
        if text in TRANSLATIONS: en[key] = TRANSLATIONS[text]
    save_xml(es_path,es);save_xml(en_path,en)
    print(f'Extracted {changed} occurrences; {len(es)} Spanish resources; {len(en)} authored English resources. Missing English uses Spanish fallback.')
if __name__=='__main__':main()

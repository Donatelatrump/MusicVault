"""One sequential player per guild; voice callbacks arrive from another thread."""
import asyncio
from contextlib import suppress
import logging
import threading

from .errors import playback_error_message, safe_diagnostic

log = logging.getLogger(__name__)


class GuildPlayer:
    def __init__(self, voice, provider, source_factory, notify, *, idle_timeout=180):
        self.voice = voice
        self.provider = provider
        self.source_factory = source_factory
        self.notify = notify
        self.idle_timeout = idle_timeout
        self.queue = asyncio.Queue(maxsize=100)
        self.current = None
        self.cancel = threading.Event()
        self.closed = False
        self.close_lock = asyncio.Lock()
        self.task = asyncio.create_task(self._run())

    def enqueue(self, track):
        if self.closed:
            raise ValueError("La sesión terminó. Vuelve a usar /reproducir.")
        try:
            self.queue.put_nowait(track)
        except asyncio.QueueFull:
            raise ValueError("La cola ya tiene 100 canciones.") from None

    def skip(self):
        self.cancel.set()
        self.voice.stop()

    def pending(self):
        return list(self.queue._queue)

    async def close(self):
        async with self.close_lock:
            if not self.closed:
                self.closed = True
                self.cancel.set()
                self.voice.stop()
                self.task.cancel()
            with suppress(asyncio.CancelledError):
                await self.task
            # Cancellation before the worker first runs skips its finally block.
            self._clear_queue()
            if self.voice.is_connected():
                with suppress(Exception):
                    await self.voice.disconnect(force=True)

    def _clear_queue(self):
        while not self.queue.empty():
            self.queue.get_nowait()
            self.queue.task_done()

    async def _notice(self, message):
        try:
            await self.notify(message)
        except Exception:
            log.warning("No se pudo enviar un aviso al canal de texto.")

    async def _run(self):
        try:
            while True:
                try:
                    track = await asyncio.wait_for(self.queue.get(), self.idle_timeout)
                except asyncio.TimeoutError:
                    return
                self.current = track
                cancel = self.cancel = threading.Event()
                source = None
                handed_to_voice = False
                try:
                    url = await asyncio.to_thread(self.provider.resolve, track, cancel)
                    if cancel.is_set():
                        continue
                    source = self.source_factory(url)
                    loop = asyncio.get_running_loop()
                    ended = loop.create_future()

                    def finish(error, future=ended):
                        if not future.done():
                            future.set_result(error)

                    def after(error, callback=finish):
                        loop.call_soon_threadsafe(callback, error)

                    self.voice.play(source, after=after)
                    handed_to_voice = True
                    await self._notice(f"Reproduciendo: {track.artist} — {track.title}")
                    error = await ended
                    if error:
                        log.warning("Voice playback failed (%s): %s", type(error).__name__, safe_diagnostic(error))
                        await self._notice("La reproducción falló. Paso a la siguiente canción.")
                except asyncio.CancelledError:
                    cancel.set()
                    raise
                except Exception as error:
                    # Do not expose signed stream URLs or provider diagnostics to Discord.
                    if not cancel.is_set():
                        log.warning("Audio preparation failed (%s): %s", type(error).__name__, safe_diagnostic(error))
                        await self._notice(playback_error_message(error))
                finally:
                    if source is not None and not handed_to_voice:
                        source.cleanup()
                    self.current = None
                    self.queue.task_done()
        finally:
            self.closed = True
            self.cancel.set()
            self.voice.stop()
            self._clear_queue()
            with suppress(Exception):
                await self.voice.disconnect(force=True)

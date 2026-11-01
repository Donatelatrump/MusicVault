"""Slash commands and Discord voice transport."""
import asyncio
import logging
import os
import threading

from .runtime import prepare_runtime

import discord
from discord import app_commands

from .media import ffmpeg_input_options
from .choices import Searches
from .player import GuildPlayer
from .provider import MusicProvider


class MusicBot(discord.Client):
    def __init__(self, provider):
        intents = discord.Intents.none()
        intents.guilds = True
        intents.voice_states = True
        super().__init__(intents=intents, allowed_mentions=discord.AllowedMentions.none())
        self.provider = provider
        self.searches = Searches()
        self.tree = app_commands.CommandTree(self)
        self.players = {}
        self.connection_lock = asyncio.Lock()
        self.search_slots = asyncio.Semaphore(1)
        self.max_players = int(os.environ.get("MUSICVAULT_MAX_PLAYERS", "1"))
        self.register_commands()

    async def setup_hook(self):
        # Global commands make the public invite usable in other servers too.
        await self.tree.sync()
        guild_id = os.environ.get("DISCORD_GUILD_ID")
        if guild_id:
            guild = discord.Object(id=int(guild_id))
            self.tree.copy_global_to(guild=guild)
            await self.tree.sync(guild=guild)

    async def close(self):
        await asyncio.gather(*(player.close() for player in self.players.values()), return_exceptions=True)
        await super().close()

    def user_channel(self, interaction):
        if not interaction.guild or not isinstance(interaction.user, discord.Member):
            raise ValueError("Usa este comando dentro de un servidor.")
        channel = interaction.user.voice.channel if interaction.user.voice else None
        if channel is None:
            raise ValueError("Entra primero a un canal de voz.")
        if not isinstance(channel, discord.VoiceChannel):
            raise ValueError("Usa un canal de voz normal para reproducir música.")
        return channel

    def active_player(self, interaction):
        channel = self.user_channel(interaction)
        player = self.players.get(interaction.guild_id)
        if player is None or player.closed or not player.voice.is_connected():
            raise ValueError("No hay una sesión activa. Usa /reproducir.")
        if player.voice.channel.id != channel.id:
            raise ValueError("Debes estar en el mismo canal de voz que el bot.")
        return player

    async def get_player(self, interaction):
        channel = self.user_channel(interaction)
        async with self.connection_lock:
            player = self.players.get(interaction.guild_id)
            if player and not player.closed and player.voice.is_connected():
                if player.voice.channel.id != channel.id:
                    raise ValueError("El bot ya está reproduciendo en otro canal de voz.")
                return player
            if player:
                await player.close()
            if sum(not item.closed for item in self.players.values()) >= self.max_players:
                raise ValueError("El alojamiento admite una sesión de música a la vez. Espera a que termine o usa /salir.")
            perms = channel.permissions_for(interaction.guild.me)
            if not perms.connect or not perms.speak:
                raise ValueError("Necesito permisos Conectar y Hablar en ese canal.")
            voice = await channel.connect(self_deaf=True, timeout=20)
            text_channel = interaction.channel

            async def notify(message):
                await text_channel.send(message[:1900], allowed_mentions=discord.AllowedMentions.none())

            def source(url):
                return discord.FFmpegOpusAudio(
                    url, executable=os.environ["MUSICVAULT_FFMPEG"], before_options=ffmpeg_input_options(),
                    options="-vn -loglevel error", bitrate=128)

            player = GuildPlayer(voice, self.provider, source, notify)
            self.players[interaction.guild_id] = player
            return player

    def register_commands(self):
        @self.tree.command(name="reproducir", description="Busca canciones para elegir o añade un enlace de YouTube a la cola")
        @app_commands.guild_only()
        @app_commands.describe(consulta="Canción y artista, o enlace de YouTube")
        async def play(interaction: discord.Interaction, consulta: str):
            self.user_channel(interaction)
            await interaction.response.defer()
            cancel = threading.Event()
            try:
                async with self.search_slots:
                    tracks = await asyncio.wait_for(asyncio.to_thread(self.provider.search_many, consulta, cancel), timeout=35)
                if "://" in consulta:
                    player = await self.get_player(interaction)
                    player.enqueue(tracks[0])
                    await interaction.followup.send(f"Añadida a la cola: {tracks[0].artist} — {tracks[0].title}"[:1900])
                    return
                key = (interaction.guild_id, interaction.user.id)
                result = self.searches.save(key, tracks)
                view = SongPicker(self, key, result)
                result.view = view
                lines = ["Elige en el menú o usa /elegir número. Se añadirá a la cola."]
                lines.extend(f"{i}. {t.artist[:60]} — {t.title[:120]}" for i, t in enumerate(tracks, 1))
                view.message = await interaction.followup.send("\n".join(lines)[:1900], view=view, wait=True)
            finally:
                cancel.set()

        @self.tree.command(name="elegir", description="Añade a la cola un resultado de tu última búsqueda")
        @app_commands.guild_only()
        @app_commands.describe(numero="Número de la canción en la lista de resultados")
        async def choose(interaction: discord.Interaction, numero: app_commands.Range[int, 1, 5]):
            await self.choose_song(interaction, numero)

        @self.tree.command(name="cola", description="Muestra la canción actual y las siguientes")
        @app_commands.guild_only()
        async def queue(interaction: discord.Interaction):
            player = self.players.get(interaction.guild_id)
            if not player or player.closed:
                await interaction.response.send_message("La cola está vacía.")
                return
            # Queue access is on the event loop; copying avoids destructive reads.
            pending = player.pending()
            lines = [f"Ahora: {player.current.title}" if player.current else "Preparando reproducción…"]
            lines.extend(f"{i}. {track.artist} — {track.title}" for i, track in enumerate(pending[:10], 1))
            if len(pending) > 10:
                lines.append(f"Y {len(pending) - 10} más.")
            await interaction.response.send_message("\n".join(lines)[:1900])

        @self.tree.command(name="pausa", description="Pausa la reproducción")
        @app_commands.guild_only()
        async def pause(interaction: discord.Interaction):
            player = self.active_player(interaction)
            if not player.voice.is_playing():
                raise ValueError("No hay una canción sonando.")
            player.voice.pause()
            await interaction.response.send_message("Reproducción pausada.")

        @self.tree.command(name="seguir", description="Continúa la reproducción pausada")
        @app_commands.guild_only()
        async def resume(interaction: discord.Interaction):
            player = self.active_player(interaction)
            if not player.voice.is_paused():
                raise ValueError("La reproducción no está pausada.")
            player.voice.resume()
            await interaction.response.send_message("Reproducción reanudada.")

        @self.tree.command(name="saltar", description="Salta la canción actual, incluso mientras se prepara")
        @app_commands.guild_only()
        async def skip(interaction: discord.Interaction):
            player = self.active_player(interaction)
            if player.current is None:
                raise ValueError("No hay una canción para saltar.")
            player.skip()
            await interaction.response.send_message("Canción saltada.")

        @self.tree.command(name="salir", description="Vacía la cola y desconecta al bot")
        @app_commands.guild_only()
        async def leave(interaction: discord.Interaction):
            player = self.active_player(interaction)
            await interaction.response.defer()
            await player.close()
            # A concurrent /reproducir may have created a replacement session.
            if self.players.get(interaction.guild_id) is player:
                self.players.pop(interaction.guild_id, None)
            await interaction.followup.send("Desconectado. Cola vaciada.")

        @self.tree.error
        async def command_error(interaction: discord.Interaction, error: app_commands.AppCommandError):
            await self.send_error(interaction, getattr(error, "original", error))

    async def choose_song(self, interaction, number, expected=None):
        key = (interaction.guild_id, interaction.user.id)
        result = self.searches.get(key, number, expected)
        self.user_channel(interaction)
        await interaction.response.defer()
        player = await self.get_player(interaction)
        # There is no await between consuming and enqueueing: two clicks cannot
        # add the same search result twice, even while connection is in progress.
        track = self.searches.consume(key, number, result)
        try:
            player.enqueue(track)
        except ValueError:
            if key not in self.searches.results:
                self.searches.results[key] = result
            raise
        if result.view:
            await result.view.disable()
        await interaction.followup.send(f"Añadida a la cola: {track.artist} — {track.title}"[:1900])

    async def send_error(self, interaction, original):
        if isinstance(original, ValueError):
            message = str(original)
        elif isinstance(original, (TimeoutError, asyncio.TimeoutError)):
            message = "La operación tardó demasiado. Inténtalo de nuevo."
        else:
            message = "No pude completar la operación. Comprueba la conexión y los permisos del bot."
            logging.getLogger(__name__).warning("Comando fallido: %s", type(original).__name__)
        if interaction.response.is_done():
            await interaction.followup.send(message[:1900], ephemeral=True)
        else:
            await interaction.response.send_message(message[:1900], ephemeral=True)


class SongPicker(discord.ui.View):
    def __init__(self, bot, key, result):
        super().__init__(timeout=120)
        self.bot = bot
        self.key = key
        self.result = result
        self.message = None
        self.select = discord.ui.Select(placeholder="Elige la canción que quieres escuchar", options=[
            discord.SelectOption(label=f"{i}. {track.title}"[:100], description=track.artist[:100] or "YouTube", value=str(i))
            for i, track in enumerate(result.tracks, 1)])
        self.select.callback = self.choose
        self.add_item(self.select)

    async def interaction_check(self, interaction):
        if (interaction.guild_id, interaction.user.id) != self.key:
            await interaction.response.send_message("Esta lista pertenece a otra persona. Crea la tuya con /reproducir.", ephemeral=True)
            return False
        return True

    async def choose(self, interaction):
        await self.bot.choose_song(interaction, int(self.select.values[0]), expected=self.result)

    async def disable(self):
        self.select.disabled = True
        self.stop()
        if self.message:
            try:
                await self.message.edit(view=self)
            except discord.HTTPException:
                pass

    async def on_timeout(self):
        await self.disable()

    async def on_error(self, interaction, error, item):
        await self.bot.send_error(interaction, error)


def main():
    prepare_runtime()
    token = os.environ.get("DISCORD_TOKEN", "").strip()
    if not token:
        raise SystemExit("Falta DISCORD_TOKEN. Configúralo como variable de entorno.")
    bot = MusicBot(MusicProvider())
    bot.run(token)


if __name__ == "__main__":
    main()

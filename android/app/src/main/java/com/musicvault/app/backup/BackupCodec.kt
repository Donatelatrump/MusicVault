package com.musicvault.app.backup

import com.musicvault.app.data.*
import org.json.JSONArray
import org.json.JSONObject

data class LibraryBackup(
    val tracks: List<TrackEntity>,
    val playlists: List<PlaylistEntity>,
    val links: List<PlaylistTrackEntity>,
    val favorites: List<FavoriteEntity>,
    val settings: Map<String, Any>
)

/** A portable, versioned metadata document. No credentials or file grants. */
object BackupCodec {
    const val MAX_BYTES = 20 * 1024 * 1024
    private const val MAX_ROWS = 50000
    private val settingKeys = setOf("player_style", "app_design", "crossfade_seconds",
        "stream_quality", "download_quality", "download_wifi_only", "stream_cache_mb")

    fun settings(values: Map<String, *>): Map<String, Any> = values.filterKeys { it in settingKeys }
        .mapNotNull { (key, value) -> if (validSetting(key, value)) key to value!! else null }.toMap()

    private fun validSetting(key: String, value: Any?): Boolean = when (key) {
        "player_style" -> value == "vinyl" || value == "circular"
        "app_design" -> value == "hybrid" || value == "classic"
        "crossfade_seconds" -> value is Int && value in 0..12
        "stream_quality", "download_quality" -> value == "BEST" || value == "SAVER"
        "download_wifi_only" -> value is Boolean
        "stream_cache_mb" -> value in listOf(256, 512, 1024, 2048)
        else -> false
    }

    fun encode(data: LibraryBackup): String {
        val root = JSONObject().put("format", "musicvault-backup").put("version", 1)
        root.put("tracks", JSONArray().apply {
            data.tracks.forEach { t -> put(JSONObject().put("id", t.id).put("title", t.title)
                .put("artist", t.artistName).put("albumId", t.albumId).put("album", t.albumTitle)
                .put("duration", t.durationMs).put("cover", remoteCover(t.coverUri))
                .put("source", t.source).put("sourceId", portableSourceId(t.sourceId))) }
        })
        root.put("playlists", JSONArray().apply { data.playlists.forEach { p ->
            put(JSONObject().put("id", p.id).put("title", p.title).put("description", p.description)
                .put("cover", remoteCover(p.coverUri)))
        } })
        root.put("links", JSONArray().apply { data.links.forEach { p ->
            put(JSONObject().put("playlist", p.playlistId).put("track", p.trackId).put("position", p.position))
        } })
        root.put("favorites", JSONArray().apply { data.favorites.forEach { f ->
            put(JSONObject().put("track", f.trackId).put("createdAt", f.createdAt))
        } })
        root.put("settings", JSONObject(settings(data.settings)))
        return root.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "El respaldo supera los 20 MB." } }
    }

    fun decode(text: String): LibraryBackup {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "El respaldo supera los 20 MB." }
        val root = JSONObject(text)
        require(root.getString("format") == "musicvault-backup" && root.getInt("version") == 1) { "Formato o versión de respaldo no compatible." }
        val tracks = rows(root, "tracks").map { t ->
            val duration = t.getLong("duration")
            require(duration in 0..604800000L) { "Duración inválida." }
            TrackEntity(t.text("id"), t.text("title", false), t.text("artist", false), t.text("albumId"),
                t.text("album", false), duration, null, false,
                remoteCover(t.optional("cover")), t.optional("source"), portableSourceId(t.optional("sourceId")))
        }
        val playlists = rows(root, "playlists").map { p ->
            PlaylistEntity(p.text("id"), p.text("title"), p.text("description", false), remoteCover(p.optional("cover")))
        }
        val links = rows(root, "links").map { p ->
            val position = p.getInt("position")
            require(position >= 0) { "Orden de playlist inválido." }
            PlaylistTrackEntity(p.text("playlist"), p.text("track"), position)
        }
        val favorites = rows(root, "favorites").map { f ->
            FavoriteEntity(f.text("track"), f.getLong("createdAt").also { require(it >= 0) })
        }
        val ids = tracks.map { it.id }.toSet()
        val playlistIds = playlists.map { it.id }.toSet()
        require(ids.size == tracks.size && playlistIds.size == playlists.size) { "El respaldo contiene identificadores duplicados." }
        require(links.all { it.trackId in ids && it.playlistId in playlistIds } && favorites.all { it.trackId in ids }) { "El respaldo contiene referencias incompletas." }
        require(links.map { it.playlistId to it.trackId }.distinct().size == links.size) { "El respaldo repite canciones en una playlist." }
        val prefs = root.getJSONObject("settings")
        val settings = prefs.keys().asSequence().associateWith { key -> prefs.get(key) }
        require(settings.all { validSetting(it.key, it.value) }) { "El respaldo contiene ajustes no compatibles." }
        return LibraryBackup(tracks, playlists, links, favorites, settings)
    }

    private fun rows(root: JSONObject, key: String): List<JSONObject> {
        val array = root.getJSONArray(key)
        require(array.length() <= MAX_ROWS) { "El respaldo contiene demasiados registros." }
        return List(array.length()) { array.getJSONObject(it) }
    }
    private fun JSONObject.text(key: String, required: Boolean = true): String = getString(key).also {
        require(it.length <= 8192 && (!required || it.isNotBlank())) { "Campo inválido: $key" }
    }
    private fun JSONObject.optional(key: String): String? = if (isNull(key) || !has(key)) null else text(key, false).takeIf { it.isNotBlank() }
    private fun remoteCover(value: String?) = value?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    private fun portableSourceId(value: String?) = value?.takeUnless {
        it.startsWith("/") || it.startsWith("\\") || it.startsWith("file:") || it.startsWith("content:")
    }
}

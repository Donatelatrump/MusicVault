package com.musicvault.app.ui

import android.content.SharedPreferences
import com.musicvault.app.data.Album
import com.musicvault.app.data.Track
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Small persistent snapshot used to paint Home while the library/catalog flows warm up. */
internal object HomeRecommendationCache {
    private const val CACHE_KEY = "home_recommendations_v2"
    private const val PROFILE_KEY = "home_recommendation_profile_v2"

    data class Snapshot(
        val profileKey: String,
        val tracks: List<Track>,
        val albums: List<Album>
    )

    fun profileKey(genres: Set<String>, artists: Set<String>): String = buildString {
        append(artists.map(String::trim).filter(String::isNotBlank).sortedBy { it.lowercase() }.joinToString("\u001f"))
        append("\u001e")
        append(genres.map(String::trim).filter(String::isNotBlank).sortedBy { it.lowercase() }.joinToString("\u001f"))
    }

    fun load(preferences: SharedPreferences): Snapshot? = runCatching {
        val root = JSONObject(preferences.getString(CACHE_KEY, null) ?: return null)
        val tracks = root.optJSONArray("tracks").objects().mapNotNull { item ->
            runCatching {
                Track(
                    id = item.getString("id"), title = item.getString("title"),
                    artistName = item.getString("artistName"), albumId = item.getString("albumId"),
                    albumTitle = item.getString("albumTitle"), durationMs = item.optLong("durationMs"),
                    audioUri = item.nullableString("audioUri"), isDownloaded = item.optBoolean("isDownloaded"),
                    coverUri = item.nullableString("coverUri"), source = item.nullableString("source"),
                    sourceId = item.nullableString("sourceId"), url = item.nullableString("url")
                )
            }.getOrNull()
        }
        val albums = root.optJSONArray("albums").objects().mapNotNull { item ->
            runCatching {
                Album(
                    id = item.getString("id"), title = item.getString("title"),
                    artistId = item.getString("artistId"), artistName = item.getString("artistName"),
                    year = item.optInt("year"), coverUri = item.nullableString("coverUri")
                )
            }.getOrNull()
        }
        Snapshot(root.getString("profileKey"), tracks, albums).takeIf { tracks.isNotEmpty() || albums.isNotEmpty() }
    }.getOrNull()

    fun save(preferences: SharedPreferences, profileKey: String, tracks: List<Track>, albums: List<Album>) {
        if (tracks.isEmpty() && albums.isEmpty()) return
        val root = JSONObject().put("profileKey", profileKey)
        root.put("tracks", JSONArray().apply { tracks.forEach { track ->
            put(JSONObject().put("id", track.id).put("title", track.title).put("artistName", track.artistName)
                .put("albumId", track.albumId).put("albumTitle", track.albumTitle).put("durationMs", track.durationMs)
                .put("audioUri", track.audioUri).put("isDownloaded", track.isDownloaded).put("coverUri", track.coverUri)
                .put("source", track.source).put("sourceId", track.sourceId).put("url", track.url))
        } })
        root.put("albums", JSONArray().apply { albums.forEach { album ->
            put(JSONObject().put("id", album.id).put("title", album.title).put("artistId", album.artistId)
                .put("artistName", album.artistName).put("year", album.year).put("coverUri", album.coverUri))
        } })
        preferences.edit().putString(CACHE_KEY, root.toString()).putString(PROFILE_KEY, profileKey).apply()
    }

    fun persistedProfileKey(preferences: SharedPreferences): String? = preferences.getString(PROFILE_KEY, null)

    /** Rotate the first visible item daily while keeping the full cached pool available. */
    fun forToday(snapshot: Snapshot): Pair<List<Track>, List<Album>> {
        val day = LocalDate.now().toEpochDay()
        val trackOffset = if (snapshot.tracks.isEmpty()) 0 else Math.floorMod((day + snapshot.profileKey.hashCode()).toInt(), snapshot.tracks.size)
        val albumOffset = if (snapshot.albums.isEmpty()) 0 else Math.floorMod((day + snapshot.profileKey.hashCode().toLong() * 31L).toInt(), snapshot.albums.size)
        return snapshot.tracks.rotate(trackOffset) to snapshot.albums.rotate(albumOffset)
    }

    private fun <T> List<T>.rotate(offset: Int): List<T> = if (isEmpty() || offset == 0) this else drop(offset) + take(offset)
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else buildList {
        for (index in 0 until length()) optJSONObject(index)?.let(::add)
    }
    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
}

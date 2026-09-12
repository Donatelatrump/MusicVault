package com.musicvault.app.spotify

enum class SpotifyLinkKind { TRACK, PLAYLIST }

data class SpotifyLink(val kind: SpotifyLinkKind, val id: String) {
    val canonicalUrl: String
        get() = "https://open.spotify.com/${kind.name.lowercase()}/$id"
}

object SpotifyLinks {
    private val resource = Regex(
        "^https://(?:www\\.)?open\\.spotify\\.com/(?:intl-[A-Za-z-]+/)?(?:embed/)?(track|playlist)/([A-Za-z0-9]{22})(?:/)?(?:[?#].*)?$",
        RegexOption.IGNORE_CASE
    )
    private val embedded = Regex(
        "https://(?:www\\.)?open\\.spotify\\.com/[^\\s<>\\\"']+",
        RegexOption.IGNORE_CASE
    )

    fun parse(value: String?): SpotifyLink? {
        val match = resource.matchEntire(value?.trim().orEmpty()) ?: return null
        val kind = when (match.groupValues[1].lowercase()) {
            "track" -> SpotifyLinkKind.TRACK
            "playlist" -> SpotifyLinkKind.PLAYLIST
            else -> return null
        }
        return SpotifyLink(kind, match.groupValues[2])
    }

    fun findInText(value: String?): SpotifyLink? = embedded.findAll(value.orEmpty())
        .mapNotNull { parse(it.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')) }
        .firstOrNull()
}

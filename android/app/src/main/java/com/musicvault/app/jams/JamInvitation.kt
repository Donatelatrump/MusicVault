package com.musicvault.app.jams

import java.net.URI

/** Only our verified web host and explicit app scheme may trigger navigation. */
internal object JamInvitation {
    const val HOST = "musicvault-jams-donatelatrump.web.app"
    private val codePattern = Regex("[a-f0-9]{32}")
    enum class Kind { ROOM, FRIEND }
    data class Link(val kind: Kind, val code: String)
    fun roomUrl(code: String) = "https://$HOST/jam/$code"
    fun friendUrl(code: String) = "https://$HOST/friend/$code"
    fun parse(raw: String): Link? = runCatching {
        val uri = URI(raw.trim())
        if (uri.userInfo != null || uri.port != -1 || uri.query != null || uri.fragment != null) return null
        val parts = when {
            uri.scheme == "https" && uri.host == HOST -> uri.path.split('/').filter { it.isNotEmpty() }
            uri.scheme == "musicvault" && uri.host in setOf("jam", "friend") -> listOf(uri.host) + uri.path.split('/').filter { it.isNotEmpty() }
            else -> return null
        }
        if (parts.size != 2 || !codePattern.matches(parts[1])) return null
        val kind = when(parts[0]) { "jam" -> Kind.ROOM; "friend" -> Kind.FRIEND; else -> return null }
        Link(kind, parts[1])
    }.getOrNull()
    fun roomCode(raw: String): String? {
        parse(raw)?.let { return it.code.takeIf { _ -> it.kind == Kind.ROOM } }
        val code = raw.trim().removePrefix("MusicVault JAMS:").trim().lowercase()
        return code.takeIf(codePattern::matches)
    }
}

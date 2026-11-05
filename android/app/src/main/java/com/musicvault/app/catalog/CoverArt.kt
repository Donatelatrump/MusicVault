package com.musicvault.app.catalog

object CoverArt {
    /** Upgrade known thumbnail URLs without changing local or unknown artwork. */
    fun highResolution(url: String): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase() ?: return url
        return when {
            host == "i.ytimg.com" || host == "img.youtube.com" -> {
                val match = Regex("^/(vi|vi_webp)/([A-Za-z0-9_-]{11})/[^/]+$").matchEntire(uri.path) ?: return url
                "https://i.ytimg.com/vi/${match.groupValues[2]}/maxresdefault.jpg"
            }
            host == "e-cdns-images.dzcdn.net" || host.endsWith(".dzcdn.net") ->
                url.replace(Regex("/\\d+x\\d+-"), "/1200x1200-")
            host == "coverartarchive.org" -> url.replace(Regex("/front-(250|500)$"), "/front-1200")
            else -> url
        }
    }

    fun releaseFront(releaseId: String?): String? =
        releaseId?.takeIf { it.isNotBlank() }?.let {
            "https://coverartarchive.org/release/$it/front-500"
        }

    fun releaseGroupFront(releaseGroupId: String?): String? =
        releaseGroupId?.takeIf { it.isNotBlank() }?.let {
            "https://coverartarchive.org/release-group/$it/front-500"
        }
}

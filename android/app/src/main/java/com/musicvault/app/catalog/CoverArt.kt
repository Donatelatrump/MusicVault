package com.musicvault.app.catalog

object CoverArt {
    fun releaseFront(releaseId: String?): String? =
        releaseId?.takeIf { it.isNotBlank() }?.let {
            "https://coverartarchive.org/release/$it/front-500"
        }

    fun releaseGroupFront(releaseGroupId: String?): String? =
        releaseGroupId?.takeIf { it.isNotBlank() }?.let {
            "https://coverartarchive.org/release-group/$it/front-500"
        }
}

package com.musicvault.app.download

import com.musicvault.app.data.Track

/**
 * Extension point for audio acquisition.
 *
 * IMPORTANT:
 * Implementations must only retrieve audio from sources and content
 * that the user is authorized to download and use.
 */
interface AudioSourceProvider {
    val id: String
    val displayName: String

    suspend fun resolve(track: Track): ResolvedAudio?
}

data class ResolvedAudio(
    val sourceUri: String,
    val mimeType: String?,
    val suggestedFileName: String
)

/**
 * Placeholder for a future provider integration.
 *
 * This intentionally contains no YouTube extraction/downloading code.
 * A future implementation can be added here when its use complies
 * with the relevant platform terms and copyright permissions.
 */
class FutureVideoCatalogProvider : AudioSourceProvider {
    override val id = "future-video-catalog"
    override val displayName = "Future video/audio provider"

    override suspend fun resolve(track: Track): ResolvedAudio? = null
}

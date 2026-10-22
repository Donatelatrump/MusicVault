package com.musicvault.app.player

import com.musicvault.app.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ephemeral resolved URL for explicit output transfer. Never written to preferences. */
object PlaybackRouteState {
    internal val current = MutableStateFlow<Track?>(null)
    val track = current.asStateFlow()
}

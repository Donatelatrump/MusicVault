package com.musicvault.app.ui.coordinators

import com.musicvault.app.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/** Library subscriptions share one scope and do not depend on playback ticks. */
class LibraryCoordinator(repository: MusicRepository, scope: CoroutineScope) {
    val tracks = repository.tracks().stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
    val downloaded = repository.downloaded().stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
    val albums = repository.albums().stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists = repository.playlists().stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
}

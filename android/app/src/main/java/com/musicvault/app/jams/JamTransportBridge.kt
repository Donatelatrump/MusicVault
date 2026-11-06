package com.musicvault.app.jams

/** Routes notification/headset intent to the room without echoing internal sync commands. */
internal object JamTransportBridge {
    sealed interface Command {
        data object Toggle : Command
        data class Play(val playing: Boolean) : Command
        data object Next : Command
        data object Previous : Command
        data class Seek(val positionMs: Long) : Command
    }
    var listener: ((Command) -> Unit)? = null
        set(value) { field = value; if (value == null) { canPrevious = false; canNext = false }; onActiveChanged?.invoke() }
    var canPrevious = false
        private set
    var canNext = false
        private set
    fun updateNavigation(previous: Boolean, next: Boolean) {
        if (canPrevious == previous && canNext == next) return
        canPrevious = previous; canNext = next; onActiveChanged?.invoke()
    }
    var onActiveChanged: (() -> Unit)? = null
    val active get() = listener != null
    fun dispatch(command: Command): Boolean {
        val receiver = listener ?: return false
        receiver(command)
        return true
    }
}

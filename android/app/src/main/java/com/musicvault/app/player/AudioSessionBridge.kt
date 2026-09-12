package com.musicvault.app.player

/**
 * Shares the ExoPlayer audio session id between the Media3 playback service
 * and the in-process controller. MediaController itself does not expose the
 * ExoPlayer audioSessionId.
 */
object AudioSessionBridge {
    @Volatile
    var sessionId: Int = 0
}

package com.musicvault.app.player

import kotlinx.coroutines.*

class SleepTimer(private val scope: CoroutineScope, private val onFade: suspend () -> Unit, private val stop: () -> Unit) {
    private var job: Job? = null
    fun start(minutes: Int) {
        job?.cancel(); job = scope.launch {
            delay(minutes * 60_000L)
            onFade()
            delay(8000)
            stop()
        }
    }
    fun cancel(){ job?.cancel() }
}

package com.musicvault.app.providers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ExtractorUpdateState(val version: String? = null, val checking: Boolean = false,
    val lastCheck: Long = 0, val message: String = "NewPipe extractor activo")

/** Stub conservado para compatibilidad con referencias residuales. NewPipe no necesita actualización de extractor. */
object ProviderMaintenance {
    private val mutableState = MutableStateFlow(ExtractorUpdateState())
    val state = mutableState.asStateFlow()

    suspend fun check(context: android.content.Context, force: Boolean = false): ExtractorUpdateState =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { mutableState.value }
}

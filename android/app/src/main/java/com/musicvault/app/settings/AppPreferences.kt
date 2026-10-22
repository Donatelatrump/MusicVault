package com.musicvault.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArraySet

/** One settings owner. Legacy call sites keep their API while storage is DataStore. */
object AppPreferences {
    @Volatile private var instance: Store? = null
    fun prefs(context: Context): SharedPreferences = instance ?: synchronized(this) {
        instance ?: Store(context.applicationContext).also { instance = it }
    }
    fun changes(context: Context): StateFlow<Map<String, Any>> = (prefs(context) as Store).changes

    private class Store(private val context: Context) : SharedPreferences {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val data = PreferenceDataStoreFactory.create(
            migrations = listOf(
                SharedPreferencesMigration(context, "musicvault.settings"),
                SharedPreferencesMigration(context, "musicvault.portable.settings")
            ),
            scope = scope,
            produceFile = { context.preferencesDataStoreFile("musicvault_settings") }
        )
        private val lock = Any()
        private val writeLock = Mutex()
        private var revision = 0L
        private var storedRevision = 0L
        private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
        private val main = Handler(Looper.getMainLooper())
        private val state = MutableStateFlow(runBlocking(Dispatchers.IO) {
            data.data.first().asMap().mapKeys { it.key.name }
        })
        val changes: StateFlow<Map<String, Any>> = state.asStateFlow()
        override fun getAll(): MutableMap<String, *> = state.value.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = state.value[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (state.value[key] as? Set<String>)?.toMutableSet() ?: defValues?.toMutableSet()
        override fun getInt(key: String?, defValue: Int) = state.value[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = state.value[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = state.value[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = state.value[key] as? Boolean ?: defValue
        override fun contains(key: String?) = state.value.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { if (listener != null) listeners.add(listener) }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { listeners.remove(listener) }
        override fun edit(): SharedPreferences.Editor = Editor()
        private suspend fun persist(version: Long, values: Map<String, Any>) = writeLock.withLock {
            if (version < storedRevision) return@withLock
            data.edit { target ->
                target.clear()
                values.forEach { (key, value) ->
                    when (value) {
                        is String -> target[stringPreferencesKey(key)] = value
                        is Int -> target[intPreferencesKey(key)] = value
                        is Long -> target[longPreferencesKey(key)] = value
                        is Float -> target[floatPreferencesKey(key)] = value
                        is Boolean -> target[booleanPreferencesKey(key)] = value
                        is Set<*> -> target[stringSetPreferencesKey(key)] = value.filterIsInstance<String>().toSet()
                    }
                }
            }
            val portable = context.getSharedPreferences("musicvault.portable.settings", Context.MODE_PRIVATE).edit().clear()
            values.filterKeys { it in setOf("player_style", "app_design", "crossfade_seconds", "download_quality", "download_wifi_only", "stream_cache_mb", "playback_speed", "skip_silence", "normalize_volume") }.forEach { (key, value) ->
                when (value) {
                    is String -> portable.putString(key, value)
                    is Int -> portable.putInt(key, value)
                    is Boolean -> portable.putBoolean(key, value)
                    is Float -> portable.putFloat(key, value)
                }
            }
            portable.apply()
            storedRevision = version
        }
        private inner class Editor : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearing = false
            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values?.toSet() }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearing = true }
            private fun publish(): Pair<Long, Map<String, Any>> = synchronized(lock) {
                val before = state.value
                val next = if (clearing) linkedMapOf() else before.toMutableMap()
                pending.forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
                revision++
                state.value = next.toMap()
                val changed = (before.keys + next.keys).filter { before[it] != next[it] }
                main.post { changed.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this@Store, key) } } }
                revision to state.value
            }
            override fun apply() {
                val (version, values) = publish()
                scope.launch { try { persist(version, values) } catch (e: Exception) { android.util.Log.e("MusicVaultSettings", "Settings write failed", e) } }
            }
            override fun commit(): Boolean {
                val (version, values) = publish()
                return runBlocking(Dispatchers.IO) { runCatching { persist(version, values) }.isSuccess }
            }
        }
    }
}

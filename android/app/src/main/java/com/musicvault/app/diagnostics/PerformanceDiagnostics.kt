package com.musicvault.app.diagnostics

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Window
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** These are fixed destinations, never song titles, search text, paths or account names. */
enum class DiagnosticsScreen { HOME, SEARCH, LIBRARY, QUEUE, PLAYER, LYRICS, SETTINGS, DETAIL, PROVIDER_TOOLS, UNKNOWN }

/** Aggregate-only, opt-in frame collection. No FrameData objects or per-frame traces are retained. */
object PerformanceDiagnostics {
    private const val PREFS = "musicvault_diagnostics"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val aggregateLock = Any()
    private val diskLock = Any()
    private val initialized = AtomicBoolean()
    private val generation = AtomicLong()
    private val mutableEnabled = MutableStateFlow(false)
    val enabled = mutableEnabled.asStateFlow()
    private var application: Context? = null
    @Volatile private var screen = DiagnosticsScreen.UNKNOWN
    private class Counts(var frames: Long = 0, var janky: Long = 0, var totalNanos: Long = 0, var maxNanos: Long = 0)
    private val counts = linkedMapOf<DiagnosticsScreen, Counts>()
    // Accessed only on main. Neither a tracker nor its callback strongly retains an Activity/Window.
    private class Tracker(window: Window) {
        val window = WeakReference(window)
        var stats: JankStats? = null
        var resumed = false
    }
    private val trackers = WeakHashMap<Window, Tracker>()

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val app = context.applicationContext
        application = app
        mutableEnabled.value = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("jank_capture", false)
        scope.launch {
            val capturedGeneration = generation.get()
            val json = synchronized(diskLock) {
                runCatching { JSONObject(File(app.noBackupFilesDir, "frame-aggregates.json").readText()) }.getOrNull()
            } ?: return@launch
            synchronized(aggregateLock) {
                if (generation.get() != capturedGeneration) return@synchronized
                val byScreen = json.optJSONObject("screens") ?: return@synchronized
                for (destination in DiagnosticsScreen.entries) {
                    val saved = byScreen.optJSONObject(destination.name) ?: continue
                    val target = counts.getOrPut(destination) { Counts() }
                    target.frames += saved.optLong("frames").coerceAtLeast(0)
                    target.janky += saved.optLong("jankyFrames").coerceAtLeast(0)
                    target.totalNanos += saved.optLong("totalUiNanos").coerceAtLeast(0)
                    target.maxNanos = maxOf(target.maxNanos, saved.optLong("maxUiNanos").coerceAtLeast(0))
                }
            }
        }
    }

    fun setEnabled(context: Context, capture: Boolean) {
        initialize(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("jank_capture", capture).apply()
        mutableEnabled.value = capture
        main.post { trackers.values.forEach(::updateTracking) }
        if (!capture) persist()
    }

    fun onResume(activity: Activity) {
        initialize(activity)
        val tracker = trackers.getOrPut(activity.window) { Tracker(activity.window) }
        tracker.resumed = true
        updateTracking(tracker)
    }
    fun onPause(activity: Activity) {
        trackers[activity.window]?.let { it.resumed = false; it.stats?.isTrackingEnabled = false }
        persist()
    }
    fun onDestroy(activity: Activity) {
        trackers.remove(activity.window)?.stats?.isTrackingEnabled = false
    }

    fun setScreen(destination: DiagnosticsScreen) {
        screen = destination
        main.post {
            trackers.values.forEach { tracker -> tracker.window.get()?.decorView?.let { view ->
                PerformanceMetricsState.getHolderForHierarchy(view).state?.putState("Screen", destination.name)
            } }
        }
    }

    private fun updateTracking(tracker: Tracker) {
        val window = tracker.window.get() ?: return
        if (mutableEnabled.value && tracker.resumed && tracker.stats == null) {
            tracker.stats = try {
                JankStats.createAndTrack(window) { frame ->
                    if (!mutableEnabled.value) return@createAndTrack
                    // FrameData and its state list are recycled by JankStats. Copy only scalar counters immediately.
                    val destination = frame.states.firstOrNull { it.key == "Screen" }?.value
                        ?.let { value -> DiagnosticsScreen.entries.firstOrNull { it.name == value } } ?: screen
                    synchronized(aggregateLock) {
                        val totals = counts.getOrPut(destination) { Counts() }
                        totals.frames++
                        if (frame.isJank) totals.janky++
                        val duration = frame.frameDurationUiNanos.coerceAtLeast(0)
                        totals.totalNanos += duration
                        totals.maxNanos = maxOf(totals.maxNanos, duration)
                    }
                }
            } catch (_: IllegalStateException) { null }
            PerformanceMetricsState.getHolderForHierarchy(window.decorView).state?.putState("Screen", screen.name)
        }
        tracker.stats?.isTrackingEnabled = mutableEnabled.value && tracker.resumed
    }

    fun snapshot(): JSONObject = synchronized(aggregateLock) {
        val screens = JSONObject()
        counts.forEach { (destination, totals) ->
            screens.put(destination.name, JSONObject().put("frames", totals.frames).put("jankyFrames", totals.janky)
                .put("totalUiNanos", totals.totalNanos).put("maxUiNanos", totals.maxNanos)
                .put("meanUiMs", if (totals.frames == 0L) 0.0 else totals.totalNanos.toDouble() / totals.frames / 1_000_000))
        }
        JSONObject().put("captureEnabled", mutableEnabled.value).put("screens", screens)
            .put("policy", "Opt-in foreground aggregates only; no frame traces, content identifiers or automatic upload")
    }

    private fun persist() {
        val app = application ?: return
        val capturedGeneration = generation.get()
        scope.launch {
            val json = snapshot()
            synchronized(diskLock) {
                if (generation.get() != capturedGeneration) return@synchronized
                runCatching {
                    val target = File(app.noBackupFilesDir, "frame-aggregates.json")
                    val staging = File(app.noBackupFilesDir, "frame-aggregates.pending")
                    staging.writeText(json.toString())
                    if (!staging.renameTo(target)) { staging.copyTo(target, overwrite = true); staging.delete() }
                }
            }
        }
    }

    fun clear(context: Context) {
        synchronized(aggregateLock) { generation.incrementAndGet(); counts.clear() }
        synchronized(diskLock) { File(context.noBackupFilesDir, "frame-aggregates.json").delete() }
    }
}

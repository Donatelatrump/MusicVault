package com.musicvault.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import com.musicvault.app.MainActivity
import com.musicvault.app.R
import com.musicvault.app.player.MusicPlaybackService

/**
 * Home-screen playback widget (standard AppWidget APIs — also works on
 * Xiaomi/HyperOS, which renders AppWidgets through its own system UI).
 *
 * Displays the current track and artist with prev/play/next controls and a
 * tap-through into the app. State is pushed from the app process whenever the
 * playback UI state is re-published; button presses arrive as explicit
 * broadcasts and are routed to a cached MediaController bound to the session.
 */
class MusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        push(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> withController(context) { c -> if (c.isPlaying) c.pause() else c.play() }
            ACTION_NEXT -> withController(context) { it.seekToNextMediaItem() }
            ACTION_PREVIOUS -> withController(context) { it.seekToPreviousMediaItem() }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.musicvault.app.widget.TOGGLE"
        const val ACTION_NEXT = "com.musicvault.app.widget.NEXT"
        const val ACTION_PREVIOUS = "com.musicvault.app.widget.PREVIOUS"

        @Volatile private var controller: MediaController? = null
        private var lastPushedKey: Pair<String?, Boolean>? = null

        private fun withController(context: Context, block: (MediaController) -> Unit) {
            controller?.let { block(it); return }
            val app = context.applicationContext
            val future = MediaController.Builder(
                app,
                SessionToken(app, ComponentName(app, MusicPlaybackService::class.java))
            ).buildAsync()
            Futures.addCallback(future, object : FutureCallback<MediaController> {
                override fun onSuccess(result: MediaController) {
                    controller = result
                    block(result)
                }

                override fun onFailure(t: Throwable) {}
            }, MoreExecutors.directExecutor())
        }

        /** Re-renders every widget instance; a no-op when nothing changed. */
        fun push(context: Context) {
            val app = context.applicationContext
            // Dedupe BEFORE touching AppWidgetManager: pushes arrive on every
            // playback transition and getAppWidgetIds is a binder call to
            // system_server. The cheap key check short-circuits no-op pushes.
            val c = controller
            if (c != null) {
                val key = c.currentMediaItem?.mediaId to c.isPlaying
                if (key == lastPushedKey) return
            }
            val manager = AppWidgetManager.getInstance(app) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(app, MusicWidgetProvider::class.java))
            if (ids.isEmpty()) return
            if (c == null) {
                // First push after process start: bind a MediaController and
                // re-render with live state instead of leaving the placeholder.
                withController(app) { push(app) }
            }
            val item = c?.currentMediaItem
            if (c != null) {
                lastPushedKey = item?.mediaId to c.isPlaying
            }
            val views = RemoteViews(app.packageName, R.layout.widget_music)
            views.setTextViewText(
                R.id.widget_title,
                item?.mediaMetadata?.title?.toString().takeUnless { it.isNullOrBlank() } ?: "MusicVault"
            )
            views.setTextViewText(
                R.id.widget_artist,
                item?.mediaMetadata?.artist?.toString().takeUnless { it.isNullOrBlank() } ?: "Tu bóveda de música"
            )
            views.setImageViewResource(
                R.id.widget_play,
                if (c?.isPlaying == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            val tint = 0xFFF1EAD9.toInt()
            views.setInt(R.id.widget_prev, "setColorFilter", tint)
            views.setInt(R.id.widget_play, "setColorFilter", tint)
            views.setInt(R.id.widget_next, "setColorFilter", tint)
            views.setOnClickPendingIntent(R.id.widget_root, openApp(app))
            views.setOnClickPendingIntent(R.id.widget_play, command(app, ACTION_TOGGLE, 11))
            views.setOnClickPendingIntent(R.id.widget_next, command(app, ACTION_NEXT, 12))
            views.setOnClickPendingIntent(R.id.widget_prev, command(app, ACTION_PREVIOUS, 13))
            manager.updateAppWidget(ids, views)
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun command(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, MusicWidgetProvider::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}

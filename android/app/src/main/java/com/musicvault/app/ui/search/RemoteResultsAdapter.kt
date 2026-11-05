package com.musicvault.app.ui.search

import com.musicvault.app.R

import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.PopupWindow
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.request.ImageRequest
import coil.size.Size
import com.musicvault.app.catalog.CatalogAlbum
import com.musicvault.app.catalog.CatalogPlaylist
import com.musicvault.app.catalog.CatalogTrack
import com.musicvault.app.catalog.CoverImageCache
import com.musicvault.app.ui.NativeAccessibility
import com.musicvault.app.jams.JamTrackFeedback
import com.musicvault.app.jams.JamTrackStatus
import kotlin.math.roundToInt

sealed interface RemoteResultItem {
    val stableId: String
    data class HeaderItem(val title: String) : RemoteResultItem {
        override val stableId: String = "header:$title"
    }
    data class AlbumItem(val album: CatalogAlbum) : RemoteResultItem {
        override val stableId: String = "album:${album.provider}:${album.id}"
    }
    data class TrackItem(val track: CatalogTrack) : RemoteResultItem {
        override val stableId: String = "track:${track.provider}:${track.id}"
    }
    data class PlaylistItem(val playlist: CatalogPlaylist) : RemoteResultItem {
        override val stableId: String = "playlist:${playlist.provider}:${playlist.id}"
    }
}

enum class MoreAction { QUEUE, ALBUM, DOWNLOAD, CANCEL_DOWNLOAD, DELETE, RADIO }

class RemoteResultsAdapter(
    private val onAlbumClick: (CatalogAlbum) -> Unit,
    private val onTrackClick: (CatalogTrack) -> Unit,
    private val onCancelDownload: (String) -> Unit,
    private val onMoreAction: (CatalogTrack, MoreAction) -> Unit,
    private val onPlayToggle: (CatalogTrack) -> Unit,
    private val onPlaylistClick: (CatalogPlaylist) -> Unit = {}
) : ListAdapter<RemoteResultItem, RecyclerView.ViewHolder>(DIFF) {

    private var downloadedIds: Set<String> = emptySet()
    private var progress: Map<String, Int> = emptyMap()
    private var selectedTrackId: String? = null
    private var isPlaying: Boolean = false
    private var jamFeedback: Map<String, JamTrackFeedback> = emptyMap()
    private val positionByLocalId = HashMap<String, Int>(128)

    init {
        setHasStableIds(true)
    }

    fun updateItems(items: List<RemoteResultItem>) {
        if (currentList === items) return
        submitList(items) {
            rebuildPositionIndex()
            notifyVisibleStateChanges()
        }
    }

    private fun rebuildPositionIndex() {
        positionByLocalId.clear()
        currentList.forEachIndexed { position, item ->
            if (item is RemoteResultItem.TrackItem) {
                positionByLocalId["${item.track.provider}:recording:${item.track.id}"] = position
            }
        }
    }

    private fun notifyVisibleStateChanges() {
        val changedIds = HashSet<String>()
        changedIds.addAll(progress.keys)
        changedIds.addAll(downloadedIds)
        selectedTrackId?.let(changedIds::add)

        for (localId in changedIds) {
            positionByLocalId[localId]?.let { position ->
                if (position in 0 until itemCount) {
                    notifyItemChanged(position, PAYLOAD_STATE)
                }
            }
        }
    }

    internal fun updateState(
        downloadedIds: Set<String>,
        progress: Map<String, Int>,
        selectedTrackId: String?,
        isPlaying: Boolean,
        jamFeedback: Map<String, JamTrackFeedback> = emptyMap()
    ) {
        if (this.downloadedIds === downloadedIds &&
            this.progress === progress &&
            this.selectedTrackId == selectedTrackId &&
            this.isPlaying == isPlaying && this.jamFeedback == jamFeedback
        ) return
        if (this.downloadedIds == downloadedIds &&
            this.progress == progress &&
            this.selectedTrackId == selectedTrackId &&
            this.isPlaying == isPlaying && this.jamFeedback == jamFeedback
        ) return

        val oldDownloadedIds = this.downloadedIds
        val oldProgress = this.progress
        val oldSelectedTrackId = this.selectedTrackId
        val oldJamFeedback = this.jamFeedback
        this.jamFeedback = jamFeedback

        this.downloadedIds = downloadedIds
        this.progress = progress
        this.selectedTrackId = selectedTrackId
        this.isPlaying = isPlaying

        if (positionByLocalId.isNotEmpty()) {
            val changedIds = HashSet<String>(oldProgress.size + progress.size + oldDownloadedIds.size + downloadedIds.size + 2)
            changedIds.addAll(oldProgress.keys)
            changedIds.addAll(progress.keys)
            changedIds.addAll(oldJamFeedback.keys)
            changedIds.addAll(jamFeedback.keys)
            changedIds.addAll(oldDownloadedIds)
            changedIds.addAll(downloadedIds)
            oldSelectedTrackId?.let(changedIds::add)
            selectedTrackId?.let(changedIds::add)
            changedIds.forEach { localId ->
                positionByLocalId[localId]?.let { position ->
                    if (position in 0 until itemCount) {
                        notifyItemChanged(position, PAYLOAD_STATE)
                    }
                }
            }
        }
    }

    override fun getItemId(position: Int): Long = getItem(position).stableId.hashCode().toLong()

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is RemoteResultItem.HeaderItem -> TYPE_HEADER
        is RemoteResultItem.AlbumItem -> TYPE_ALBUM
        is RemoteResultItem.TrackItem -> TYPE_TRACK
        is RemoteResultItem.PlaylistItem -> TYPE_PLAYLIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(parent.context)
            TYPE_ALBUM -> AlbumHolder(parent.context, onAlbumClick)
            TYPE_PLAYLIST -> PlaylistHolder(parent.context, onPlaylistClick)
            else -> TrackHolder(parent.context, onTrackClick, onCancelDownload, onMoreAction, onPlayToggle, ::showSearchTrackMenu)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        bind(holder, getItem(position), null)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_STATE)) {
            bind(holder, getItem(position), PAYLOAD_STATE)
        } else {
            bind(holder, getItem(position), null)
        }
    }

    private fun bind(holder: RecyclerView.ViewHolder, item: RemoteResultItem, payload: Any?) {
        when {
            holder is HeaderHolder && item is RemoteResultItem.HeaderItem -> holder.bind(item.title)
            holder is AlbumHolder && item is RemoteResultItem.AlbumItem -> holder.bind(item.album)
            holder is PlaylistHolder && item is RemoteResultItem.PlaylistItem -> holder.bind(item.playlist)
            holder is TrackHolder && item is RemoteResultItem.TrackItem -> holder.bind(
                item.track,
                downloadedIds,
                progress,
                selectedTrackId,
                isPlaying,
                jamFeedback,
                payload != null
            )
        }
    }

    private class HeaderHolder(context: Context) : RecyclerView.ViewHolder(TextView(context).apply {
        setTextColor(Color.parseColor("#F1EAD9"))
        textSize = 16f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 4), dp(context, 10), dp(context, 4), dp(context, 10))
        minHeight = dp(context, 48)
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }) {
        fun bind(value: String) { (itemView as TextView).text = value; NativeAccessibility.heading(itemView) }
    }

    private class AlbumHolder(
        context: Context,
        private val onClick: (CatalogAlbum) -> Unit
    ) : RecyclerView.ViewHolder(makeRow(context, 66)) {
        private val image = itemView.findViewWithTag<ImageView>("cover")
        private val title = itemView.findViewWithTag<TextView>("title")
        private val subtitle = itemView.findViewWithTag<TextView>("subtitle")
        private var album: CatalogAlbum? = null

        init { itemView.setOnClickListener { album?.let(onClick) } }

        fun bind(value: CatalogAlbum) {
            album = value
            title.text = value.title
            subtitle.text = value.artistName + (value.year?.let { " · $it" } ?: "")
            NativeAccessibility.describe(itemView, "${value.title}, ${subtitle.text}", clickLabel = itemView.context.getString(R.string.ui_abrir_album_bdccd1),
                decorative = listOf(image, title, subtitle))
            loadCover(itemView.context, image, value.coverUri, 56)
        }
    }

    private class PlaylistHolder(
        context: Context,
        private val onClick: (CatalogPlaylist) -> Unit
    ) : RecyclerView.ViewHolder(makeRow(context, 72)) {
        private val image = itemView.findViewWithTag<ImageView>("cover")
        private val title = itemView.findViewWithTag<TextView>("title")
        private val subtitle = itemView.findViewWithTag<TextView>("subtitle")
        private var playlist: CatalogPlaylist? = null

        init {
            installPressFeedback(itemView)
            itemView.setOnClickListener { playlist?.let(onClick) }
        }

        fun bind(value: CatalogPlaylist) {
            playlist = value
            title.text = value.title
            subtitle.text = buildString {
                append(value.uploaderName.ifBlank { itemView.context.getString(R.string.ui_youtube_558865) })
                if (value.trackCount > 0) append(" · ${value.trackCount} elementos")
            }
            NativeAccessibility.describe(itemView, "${value.title}, ${subtitle.text}", clickLabel = itemView.context.getString(R.string.ui_abrir_playlist_b7a8ce),
                decorative = listOf(image, title, subtitle))
            loadCover(itemView.context, image, value.coverUri, 56)
        }
    }

    private class TrackHolder(
        context: Context,
        private val onClick: (CatalogTrack) -> Unit,
        private val onCancel: (String) -> Unit,
        private val onMoreAction: (CatalogTrack, MoreAction) -> Unit,
        private val onPlayToggle: (CatalogTrack) -> Unit,
        private val showMoreMenu: (View, CatalogTrack) -> Unit
    ) : RecyclerView.ViewHolder(makeTrackRow(context)) {
        private val image = itemView.findViewWithTag<ImageView>("cover")
        private val title = itemView.findViewWithTag<TextView>("title")
        private val subtitle = itemView.findViewWithTag<TextView>("subtitle")
        private val description = itemView.findViewWithTag<TextView>("description")
        private val progressBar = itemView.findViewWithTag<ProgressBar>("progress")
        private val play = itemView.findViewWithTag<StyledPlayButton>("play")
        private val more = itemView.findViewWithTag<MoreButton>("more")
        private var track: CatalogTrack? = null

        init {
            installPressFeedback(itemView)
            itemView.setOnClickListener { track?.let(onClick) }
            play.setOnClickListener {
                track?.let { t ->
                    val localId = "${t.provider}:recording:${t.id}"
                    // A download is already represented by the spinner. The Play
                    // button is hidden during that state, so taps cannot cancel it.
                    if (progressFor(localId) == null || localId in downloadedIdsForBind) {
                        onPlayToggle(t)
                    }
                }
            }
            more.setOnClickListener { anchor ->
                val current = track ?: return@setOnClickListener
                showMoreMenu(anchor, current)
            }
        }

        private var downloadedIdsForBind: Set<String> = emptySet()
        private var progressMapForBind: Map<String, Int> = emptyMap()
        private fun progressFor(id: String): Int? = progressMapForBind[id]

        fun bind(
            value: CatalogTrack,
            downloadedIds: Set<String>,
            progress: Map<String, Int>,
            selectedTrackId: String?,
            isPlaying: Boolean,
            jamFeedback: Map<String, JamTrackFeedback>,
            stateOnly: Boolean
        ) {
            track = value
            downloadedIdsForBind = downloadedIds
            progressMapForBind = progress
            val localId = "${value.provider}:recording:${value.id}"
            val p = progress[localId]
            val downloaded = localId in downloadedIds
            val downloading = p != null && !downloaded
            if (!stateOnly) {
                title.text = value.title
                subtitle.text = buildSubtitle(value)
                description.text = value.description?.takeIf { it.isNotBlank() }.orEmpty()
                description.visibility = if (description.text.isNullOrBlank()) View.GONE else View.VISIBLE
                loadCover(itemView.context, image, value.coverUri, 56)
            }
            val feedback = jamFeedback[localId]?.status
            val preparing = feedback == JamTrackStatus.LOADING
            progressBar.isIndeterminate = preparing
            progressBar.visibility = if (downloading || preparing) View.VISIBLE else View.GONE
            if (downloading && !preparing) progressBar.progress = (p ?: 0).coerceIn(0, 100)
            description.text = when (feedback) {
                JamTrackStatus.LOADING -> itemView.context.getString(R.string.jams_preparing_track)
                JamTrackStatus.ADDED -> itemView.context.getString(R.string.jams_track_added)
                JamTrackStatus.FAILED -> itemView.context.getString(R.string.jams_track_failed)
                else -> value.description.orEmpty()
            }
            description.visibility = if (description.text.isBlank()) View.GONE else View.VISIBLE
            description.setTextColor(Color.parseColor(if (feedback == JamTrackStatus.FAILED) "#D96A55" else "#E0A458"))

            // Row tap is now the universal playback action (local or streaming).
            // The trailing affordance is always the overflow menu; download is
            // an explicit action inside that menu instead of a Play button.
            play.visibility = View.GONE
            more.visibility = View.VISIBLE
            more.setDotsColor(Color.parseColor("#F1EAD9"))
            play.mode = if (isPlaying && selectedTrackId == localId) StyledPlayButton.Mode.PAUSE else StyledPlayButton.Mode.PLAY
            title.setTextColor(if (preparing || selectedTrackId == localId) Color.parseColor("#E0A458") else Color.parseColor("#F1EAD9"))
            val active = isPlaying && selectedTrackId == localId
            val playbackLabel = if (active) itemView.context.getString(R.string.ui_pausar_8e0f5e) else itemView.context.getString(R.string.ui_reproducir_d1a188)
            val state = buildList {
                if (active) add(itemView.context.getString(R.string.ui_reproduciendo_2d20e2)) else if (selectedTrackId == localId) add(itemView.context.getString(R.string.ui_pausada_144ffd))
                if (downloaded) add(itemView.context.getString(R.string.ui_descargada_c000cf))
                if (downloading) add("Descargando ${(p ?: 0).coerceIn(0, 100)} por ciento")
            }.joinToString(", ")
            NativeAccessibility.describe(itemView, "${value.title}, ${buildSubtitle(value)}", state,
                clickLabel = itemView.context.getString(R.string.ui_reproducir_cancion_e47bd3), actions = buildList {
                    add(NativeAccessibility.Action(playbackLabel) { onPlayToggle(value) })
                    add(NativeAccessibility.Action(itemView.context.getString(R.string.ui_agregar_a_cola_ca3388)) { onMoreAction(value, MoreAction.QUEUE) })
                    if (!downloaded) add(NativeAccessibility.Action(if (downloading) itemView.context.getString(R.string.ui_cancelar_descarga_f42d93) else itemView.context.getString(R.string.ui_descargar_8b98cb)) {
                        if (downloading) onCancel(localId) else onMoreAction(value, MoreAction.DOWNLOAD)
                    })
                    add(NativeAccessibility.Action(itemView.context.getString(R.string.ui_iniciar_radio_ec118d)) { onMoreAction(value, MoreAction.RADIO) })
                    add(NativeAccessibility.Action(itemView.context.getString(R.string.ui_mas_opciones_977c75)) { showMoreMenu(more, value) })
                }, decorative = listOf(image, title, subtitle, description, progressBar))
            NativeAccessibility.button(more, "Más opciones para ${value.title}")
            play.contentDescription = "$playbackLabel ${value.title}"
        }

        private fun buildSubtitle(value: CatalogTrack): String {
            val parts = buildList {
                if (value.artistName.isNotBlank()) add(value.artistName)
                when (value.contentType) {
                    com.musicvault.app.catalog.CatalogContentType.MUSIC -> Unit
                    com.musicvault.app.catalog.CatalogContentType.PODCAST -> add(itemView.context.getString(R.string.ui_podcast_bafb6e))
                    com.musicvault.app.catalog.CatalogContentType.ASMR -> add(itemView.context.getString(R.string.ui_asmr_11ddb2))
                    com.musicvault.app.catalog.CatalogContentType.OTHER -> add(itemView.context.getString(R.string.ui_otro_ada1c1))
                }
                add(formatDuration(value.durationMs, itemView.context))
            }.filter { it.isNotBlank() }
            val base = parts.joinToString(" · ")
            val source = value.source?.takeIf { it.isNotBlank() } ?: value.provider
            return if (base.isBlank()) source else "$base · $source"
        }
    }

    /**
     * Search play control intentionally mirrors the simple Material play/pause
     * control used by Home TrackRow: no extra circular background, just the
     * white glyph with the normal 48dp touch target.
     */
    private class StyledPlayButton(context: Context) : ImageButton(context) {
        enum class Mode { PLAY, PAUSE, CANCEL }
        var mode: Mode = Mode.PLAY
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        init {
            background = null
            setPadding(0, 0, 0, 0)
            isClickable = true
            isFocusable = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val size = minOf(width, height) * 0.36f
            paint.style = Paint.Style.FILL
            paint.color = if (isPressed) Color.parseColor("#F1EAD9") else Color.parseColor("#F1EAD9")

            when (mode) {
                Mode.PLAY -> {
                    path.reset()
                    path.moveTo(cx - size * .32f, cy - size * .55f)
                    path.lineTo(cx + size * .55f, cy)
                    path.lineTo(cx - size * .32f, cy + size * .55f)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                Mode.PAUSE -> {
                    canvas.drawRoundRect(
                        cx - size * .45f, cy - size * .58f,
                        cx - size * .08f, cy + size * .58f,
                        size * .08f, size * .08f, paint
                    )
                    canvas.drawRoundRect(
                        cx + size * .08f, cy - size * .58f,
                        cx + size * .45f, cy + size * .58f,
                        size * .08f, size * .08f, paint
                    )
                }
                Mode.CANCEL -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = maxOf(3f, size * .18f)
                    paint.strokeCap = Paint.Cap.ROUND
                    canvas.drawLine(cx - size * .45f, cy - size * .45f, cx + size * .45f, cy + size * .45f, paint)
                    canvas.drawLine(cx + size * .45f, cy - size * .45f, cx - size * .45f, cy + size * .45f, paint)
                }
            }
        }
    }

    private fun showSearchTrackMenu(anchor: View, track: CatalogTrack) {
        val context = anchor.context
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = rounded(Color.parseColor("#292420"), dp(12).toFloat())
            elevation = dp(8).toFloat()
        }
        var popup: PopupWindow? = null
        fun addItem(text: String, action: MoreAction) {
            container.addView(TextView(context).apply {
                this.text = text
                setTextColor(Color.parseColor("#F1EAD9"))
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                minimumHeight = dp(48)
                layoutParams = LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT)
                maxLines = 3
                setPadding(dp(14), dp(10), dp(14), dp(10))
                NativeAccessibility.button(this, text)
                isClickable = true
                setOnClickListener {
                    onMoreAction(track, action)
                    popup?.dismiss()
                }
            })
        }
        addItem(context.getString(R.string.ui_agregar_a_cola_de_reproduccion_f8c617), MoreAction.QUEUE)
        addItem(context.getString(R.string.ui_agregar_a_album_7c4ad3), MoreAction.ALBUM)
        addItem(context.getString(R.string.ui_iniciar_radio_de_esta_cancion_cc8ac4), MoreAction.RADIO)
        val localId = "${track.provider}:recording:${track.id}"
        when {
            localId in downloadedIds -> addItem(context.getString(R.string.ui_eliminar_cancion_descargada_8c5b0a), MoreAction.DELETE)
            progress.containsKey(localId) -> addItem(context.getString(R.string.ui_cancelar_descarga_f42d93), MoreAction.CANCEL_DOWNLOAD)
            else -> addItem(context.getString(R.string.ui_descargar_8b98cb), MoreAction.DOWNLOAD)
        }

        popup = PopupWindow(container, dp(220), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
        }
        // Explicit END gravity anchors the menu to the three-dot button. This
        // avoids the top-left fallback that PopupMenu can exhibit in the
        // edge-to-edge RecyclerView search screen.
        popup.showAsDropDown(anchor, 0, dp(4), Gravity.END)
    }

    private class MoreButton(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var dotsColor: Int = Color.parseColor("#F1EAD9")
        init {
            isClickable = true
            isFocusable = true
            background = null
        }
        fun setDotsColor(color: Int) {
            dotsColor = color
            paint.color = color
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = (minOf(width, height) * 0.065f).coerceAtLeast(2f)
            val gap = radius * 3.2f
            paint.style = Paint.Style.FILL
            paint.color = dotsColor
            canvas.drawCircle(cx, cy - gap, radius, paint)
            canvas.drawCircle(cx, cy, radius, paint)
            canvas.drawCircle(cx, cy + gap, radius, paint)
        }
    }


    companion object {
        private const val TYPE_HEADER = 1
        private const val TYPE_ALBUM = 2
        private const val TYPE_TRACK = 3
        private const val TYPE_PLAYLIST = 4
        private const val PAYLOAD_STATE = "state"

        private val DIFF = object : DiffUtil.ItemCallback<RemoteResultItem>() {
            override fun areItemsTheSame(oldItem: RemoteResultItem, newItem: RemoteResultItem): Boolean = oldItem.stableId == newItem.stableId
            override fun areContentsTheSame(oldItem: RemoteResultItem, newItem: RemoteResultItem): Boolean = oldItem == newItem
        }

        private fun makeRow(context: Context, heightDp: Int): LinearLayout {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
                minimumHeight = dp(context, heightDp)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(Color.TRANSPARENT)
            }
            val image = ImageView(context).apply {
                tag = "cover"
                layoutParams = LinearLayout.LayoutParams(dp(context, 50), dp(context, 50))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(Color.parseColor("#1E1B18"), dp(context, 6).toFloat())
                clipToOutline = true
            }
            row.addView(image)
            val text = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(context, 12) }
            }
            text.addView(TextView(context).apply { tag = "title"; setTextColor(Color.parseColor("#F1EAD9")); textSize = 15f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setTypeface(typeface, android.graphics.Typeface.BOLD) })
            text.addView(TextView(context).apply { tag = "subtitle"; setTextColor(Color.parseColor("#B3A992")); textSize = 12f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            row.addView(text)
            row.addView(TextView(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; this.text = "›"; textSize = 28f; setTextColor(Color.parseColor("#7E7666")); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(context, 28), dp(context, 48)) })
            return row
        }

        private fun makeTrackRow(context: Context): LinearLayout {
            val row = makeRow(context, 84)
            // Keep a consistent minimum while allowing enlarged accessibility fonts to grow the row.
            row.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            row.minimumHeight = dp(context, 84)
            row.gravity = Gravity.CENTER_VERTICAL
            row.removeViewAt(2)
            val text = row.getChildAt(1) as LinearLayout
            text.layoutParams = (text.layoutParams as LinearLayout.LayoutParams).apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                gravity = Gravity.CENTER_VERTICAL
            }
            text.gravity = Gravity.CENTER_VERTICAL
            val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                tag = "progress"
                visibility = View.GONE
                max = 100
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E0A458"))
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#292420"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 2)).apply {
                    topMargin = dp(context, 3)
                }
            }
            text.addView(progress)
            text.addView(TextView(context).apply {
                tag = "description"
                setTextColor(Color.parseColor("#9C927F"))
                textSize = 11f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                visibility = View.GONE
            })
            // Single fixed-size action column: play / spinner / more live in the
            // same 48dp box (visibility-toggled), so every row — no matter the
            // title length or download state — aligns its action in exactly the
            // same place.
            val actions = android.widget.FrameLayout(context).apply {
                tag = "actionsBox"
                layoutParams = LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)).apply { gravity = Gravity.CENTER_VERTICAL }
            }
            val play = StyledPlayButton(context).apply {
                tag = "play"
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                mode = StyledPlayButton.Mode.PLAY
                stateListAnimator = null
            }
            actions.addView(play)
            actions.addView(MoreButton(context).apply {
                tag = "more"
                visibility = View.GONE
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            })
            row.addView(actions)
            return row
        }

        private fun formatDuration(durationMs: Long, context: Context): String {
            if (durationMs <= 0L) return context.getString(R.string.ui_duracion_desconocida_ab5898)
            val totalSeconds = durationMs / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }

        private fun installPressFeedback(view: View) {
            view.isClickable = true
            view.setOnTouchListener { target, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> target.animate()
                        .scaleX(0.985f).scaleY(0.985f).alpha(0.82f).setDuration(65L).start()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> target.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f).setDuration(110L).start()
                }
                false
            }
        }

        private fun buttonBackground(context: Context, normal: Int, pressed: Int, radius: Float): StateListDrawable {
            val states = StateListDrawable()
            states.addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, radius))
            states.addState(intArrayOf(), rounded(normal, radius))
            return states
        }

        private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
        private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

        private fun loadCover(context: Context, image: ImageView, url: String?, sizePx: Int) {
            if (url.isNullOrBlank()) {
                image.setImageDrawable(rounded(Color.parseColor("#1E1B18"), dp(context, 6).toFloat()))
                return
            }
            image.loadWithCoil(context, url, sizePx)
        }

        private fun ImageView.loadWithCoil(context: Context, url: String, sizePx: Int) {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(Size(sizePx, sizePx))
                .memoryCacheKey("musicvault:$url:$sizePx")
                .diskCacheKey(url)
                .crossfade(false)
                .target(this)
                .build()
            CoverImageCache.loader(context).enqueue(request)
        }
    }
}

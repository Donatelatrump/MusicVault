package com.musicvault.app.ui.search

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
import android.view.animation.LinearInterpolator
import android.animation.ValueAnimator
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
import com.musicvault.app.catalog.CatalogTrack
import com.musicvault.app.catalog.CoverImageCache
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
}

enum class MoreAction { QUEUE, ALBUM, DELETE }

class RemoteResultsAdapter(
    private val onAlbumClick: (CatalogAlbum) -> Unit,
    private val onTrackClick: (CatalogTrack) -> Unit,
    private val onCancelDownload: (String) -> Unit,
    private val onMoreAction: (CatalogTrack, MoreAction) -> Unit,
    private val onPlayToggle: (CatalogTrack) -> Unit
) : ListAdapter<RemoteResultItem, RecyclerView.ViewHolder>(DIFF) {

    private var downloadedIds: Set<String> = emptySet()
    private var progress: Map<String, Int> = emptyMap()
    private var selectedTrackId: String? = null
    private var isPlaying: Boolean = false
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

    fun updateState(
        downloadedIds: Set<String>,
        progress: Map<String, Int>,
        selectedTrackId: String?,
        isPlaying: Boolean
    ) {
        if (this.downloadedIds === downloadedIds &&
            this.progress === progress &&
            this.selectedTrackId == selectedTrackId &&
            this.isPlaying == isPlaying
        ) return
        if (this.downloadedIds == downloadedIds &&
            this.progress == progress &&
            this.selectedTrackId == selectedTrackId &&
            this.isPlaying == isPlaying
        ) return

        val oldDownloadedIds = this.downloadedIds
        val oldProgress = this.progress
        val oldSelectedTrackId = this.selectedTrackId

        this.downloadedIds = downloadedIds
        this.progress = progress
        this.selectedTrackId = selectedTrackId
        this.isPlaying = isPlaying

        if (positionByLocalId.isNotEmpty()) {
            val changedIds = HashSet<String>(oldProgress.size + progress.size + oldDownloadedIds.size + downloadedIds.size + 2)
            changedIds.addAll(oldProgress.keys)
            changedIds.addAll(progress.keys)
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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(parent.context)
            TYPE_ALBUM -> AlbumHolder(parent.context, onAlbumClick)
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
            holder is TrackHolder && item is RemoteResultItem.TrackItem -> holder.bind(
                item.track,
                downloadedIds,
                progress,
                selectedTrackId,
                isPlaying,
                payload != null
            )
        }
    }

    private class HeaderHolder(context: Context) : RecyclerView.ViewHolder(TextView(context).apply {
        setTextColor(Color.parseColor("#F1EAD9"))
        textSize = 16f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 4), dp(context, 8), dp(context, 4), dp(context, 4))
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 42))
    }) {
        fun bind(value: String) { (itemView as TextView).text = value }
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
        private val downloadSpinner = itemView.findViewWithTag<DownloadSpinnerView>("downloadSpinner")
        private val play = itemView.findViewWithTag<StyledPlayButton>("play")
        private val more = itemView.findViewWithTag<MoreButton>("more")
        private var track: CatalogTrack? = null

        init {
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
            progressBar.visibility = View.GONE

            // Search results start with only Play visible. During a download the
            // spinner is the only action. Once the repository reports the exact
            // persisted local ID, Play is removed permanently and only More remains.
            if (downloading) {
                // While a remote track is being downloaded, the action area is
                // exclusively the animated spinner. Neither Play nor More is
                // available until the file has actually been persisted.
                play.visibility = View.GONE
                more.visibility = View.GONE
                downloadSpinner.visibility = View.VISIBLE
                downloadSpinner.start()
            } else {
                downloadSpinner.stop()
                downloadSpinner.visibility = View.GONE
                play.visibility = if (downloaded) View.GONE else View.VISIBLE
                // Once persisted, the row is opened by tapping the song itself;
                // keep only the overflow menu to avoid duplicating playback UI.
                more.visibility = if (downloaded) View.VISIBLE else View.GONE
                more.setDotsColor(Color.parseColor("#F1EAD9"))
                play.mode = if (isPlaying && selectedTrackId == localId) StyledPlayButton.Mode.PAUSE else StyledPlayButton.Mode.PLAY
            }
            title.setTextColor(if (isPlaying && selectedTrackId == localId) Color.parseColor("#E0A458") else Color.parseColor("#F1EAD9"))
        }

        private fun buildSubtitle(value: CatalogTrack): String {
            val parts = buildList {
                if (value.artistName.isNotBlank()) add(value.artistName)
                when (value.contentType) {
                    com.musicvault.app.catalog.CatalogContentType.MUSIC -> Unit
                    com.musicvault.app.catalog.CatalogContentType.PODCAST -> add("Podcast")
                    com.musicvault.app.catalog.CatalogContentType.ASMR -> add("ASMR")
                    com.musicvault.app.catalog.CatalogContentType.OTHER -> add("Otro")
                }
                add(formatDuration(value.durationMs))
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
                layoutParams = LinearLayout.LayoutParams(dp(220), dp(48))
                isClickable = true
                setOnClickListener {
                    onMoreAction(track, action)
                    popup?.dismiss()
                }
            })
        }
        addItem("Agregar a cola de reproducción", MoreAction.QUEUE)
        addItem("Agregar a álbum", MoreAction.ALBUM)
        val localId = "${track.provider}:recording:${track.id}"
        if (localId in downloadedIds) {
            addItem("Eliminar canción", MoreAction.DELETE)
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

    private class DownloadSpinnerView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0
        private var animator: ValueAnimator? = null
        init { isClickable = false }
        fun start() { if (animator?.isRunning == true) return; animator = ValueAnimator.ofInt(0, 7).apply { duration = 900; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); addUpdateListener { phase = it.animatedValue as Int; invalidate() }; start() } }
        fun stop() { animator?.cancel(); animator = null; phase = 0; invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas); val cx=width/2f; val cy=height/2f; val radius=minOf(width,height)*.30f; val dot=minOf(width,height)*.065f
            for (i in 0 until 8) { val a=Math.toRadians((i*45.0)-90.0); val x=cx+Math.cos(a)*radius; val y=cy+Math.sin(a)*radius; val distance=(i-phase+8)%8; val alpha=70+(185*(8-distance)/8); paint.color=Color.argb(alpha,224,164,88); canvas.drawCircle(x.toFloat(),y.toFloat(),dot,paint) }
        }
        override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    }

    companion object {
        private const val TYPE_HEADER = 1
        private const val TYPE_ALBUM = 2
        private const val TYPE_TRACK = 3
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
            text.addView(TextView(context).apply { tag = "title"; setTextColor(Color.parseColor("#F1EAD9")); textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTypeface(typeface, android.graphics.Typeface.BOLD) })
            text.addView(TextView(context).apply { tag = "subtitle"; setTextColor(Color.parseColor("#B3A992")); textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            row.addView(text)
            row.addView(TextView(context).apply { this.text = "›"; textSize = 28f; setTextColor(Color.parseColor("#7E7666")); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(context, 28), dp(context, 48)) })
            return row
        }

        private fun makeTrackRow(context: Context): LinearLayout {
            val row = makeRow(context, 84)
            row.removeViewAt(2)
            val text = row.getChildAt(1) as LinearLayout
            val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                tag = "progress"
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(0, 0)
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
                layoutParams = LinearLayout.LayoutParams(dp(context, 48), dp(context, 48))
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
            actions.addView(DownloadSpinnerView(context).apply {
                tag = "downloadSpinner"
                visibility = View.GONE
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            })
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

        private fun formatDuration(durationMs: Long): String {
            if (durationMs <= 0L) return "Duración desconocida"
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

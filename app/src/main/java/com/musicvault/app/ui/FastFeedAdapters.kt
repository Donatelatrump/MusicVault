package com.musicvault.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.graphics.drawable.Drawable
import android.widget.ProgressBar
import android.widget.TextView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.request.ImageRequest
import coil.size.Size
import com.musicvault.app.catalog.CoverImageCache
import com.musicvault.app.data.Album
import com.musicvault.app.data.Playlist
import com.musicvault.app.data.Track
import com.musicvault.app.ui.theme.VaultColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private object FeedPayload {
    const val PLAYER_STATE = 1
}

/** Small Compose host used only for the relatively rare Home header/carousel rows. */
internal class ComposeRowHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
    ComposeView(parent.context).apply {
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }
)

internal sealed interface HomeFeedItem {
    data object Greeting : HomeFeedItem
    data object RecommendedTracks : HomeFeedItem
    data object RecommendedAlbums : HomeFeedItem
    data object SongsHeader : HomeFeedItem
    data class TrackItem(val track: Track) : HomeFeedItem
}

private fun HomeFeedItem.stableId(): Long = when (this) {
    HomeFeedItem.Greeting -> 1L
    HomeFeedItem.RecommendedTracks -> 2L
    HomeFeedItem.RecommendedAlbums -> 3L
    HomeFeedItem.SongsHeader -> 4L
    is HomeFeedItem.TrackItem -> stableLongId("track:${track.id}")
}

private fun stableLongId(value: String): Long =
    value.fold(1125899906842597L) { hash, char -> hash * 31L + char.code }

private object HomeDiff : DiffUtil.ItemCallback<HomeFeedItem>() {
    override fun areItemsTheSame(oldItem: HomeFeedItem, newItem: HomeFeedItem): Boolean =
        oldItem.stableId() == newItem.stableId()

    override fun areContentsTheSame(oldItem: HomeFeedItem, newItem: HomeFeedItem): Boolean =
        oldItem == newItem
}

internal class HomeFeedAdapter(private val vm: MusicVaultViewModel) :
    ListAdapter<HomeFeedItem, RecyclerView.ViewHolder>(HomeDiff) {

    private var playlists: List<Playlist> = emptyList()
    private var customAlbums: List<Album> = emptyList()
    private var albumDownloads: Set<String> = emptySet()
    private var isPlaying = false
    private var selectedTrackId: String? = null
    private var recommendedTracksCache: List<Track> = emptyList()
    private var recommendedAlbumsCache: List<Album> = emptyList()
    private val progressFlows = HashMap<String, Flow<Int?>>()

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = getItem(position).stableId()

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        HomeFeedItem.Greeting -> TYPE_GREETING
        HomeFeedItem.RecommendedTracks -> TYPE_RECOMMENDED_TRACKS
        HomeFeedItem.RecommendedAlbums -> TYPE_RECOMMENDED_ALBUMS
        HomeFeedItem.SongsHeader -> TYPE_SONGS_HEADER
        is HomeFeedItem.TrackItem -> TYPE_TRACK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_TRACK) NativeTrackHolder(parent.context, vm)
        else ComposeRowHolder(parent)

    fun update(
        tracks: List<Track>,
        recommendedTracks: List<Track>,
        recommendedAlbums: List<Album>,
        playlists: List<Playlist>,
        customAlbums: List<Album>,
        albumDownloads: Set<String>,
        selectedTrackId: String?,
        isPlaying: Boolean
    ) {
        val oldSelected = this.selectedTrackId
        val oldPlaying = this.isPlaying
        val recommendationsChanged = recommendedTracks.map { it.id } != recommendedTracksCache.map { it.id } ||
            recommendedAlbums.map { it.id } != recommendedAlbumsCache.map { it.id }
        val auxiliaryDataChanged = this.playlists != playlists || this.customAlbums != customAlbums
        val downloadsChanged = this.albumDownloads != albumDownloads

        this.playlists = playlists
        this.customAlbums = customAlbums
        this.albumDownloads = albumDownloads
        this.selectedTrackId = selectedTrackId
        this.isPlaying = isPlaying
        recommendedTracksCache = recommendedTracks
        recommendedAlbumsCache = recommendedAlbums

        val newItems = buildList {
            add(HomeFeedItem.Greeting)
            if (recommendedTracks.isNotEmpty()) add(HomeFeedItem.RecommendedTracks)
            if (recommendedAlbums.isNotEmpty()) add(HomeFeedItem.RecommendedAlbums)
            if (tracks.isNotEmpty()) {
                add(HomeFeedItem.SongsHeader)
                tracks.forEach { add(HomeFeedItem.TrackItem(it)) }
            }
        }

        submitList(newItems) {
            if (auxiliaryDataChanged) currentList.forEachIndexed { i, item ->
                if (item is HomeFeedItem.TrackItem) notifyItemChanged(i)
            }
            if (recommendationsChanged) currentList.forEachIndexed { i, item ->
                if (item is HomeFeedItem.RecommendedTracks || item is HomeFeedItem.RecommendedAlbums) notifyItemChanged(i)
            }
            if (downloadsChanged) currentList.indexOfFirst { it is HomeFeedItem.RecommendedAlbums }
                .takeIf { it >= 0 }?.let { notifyItemChanged(it) }
            notifyPlayerRows(oldSelected, selectedTrackId, oldPlaying, isPlaying)
        }
    }

    private fun notifyPlayerRows(oldSelected: String?, newSelected: String?, oldPlaying: Boolean, newPlaying: Boolean) {
        if (oldSelected == newSelected && oldPlaying == newPlaying) return
        currentList.forEachIndexed { index, item ->
            if (item is HomeFeedItem.TrackItem && (item.track.id == oldSelected || item.track.id == newSelected)) {
                notifyItemChanged(index, FeedPayload.PLAYER_STATE)
            }
        }
    }

    private fun progressFlow(id: String): Flow<Int?> = progressFlows.getOrPut(id) { vm.downloadProgressFor(id) }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        when (val item = getItem(position)) {
            HomeFeedItem.Greeting -> (holder as ComposeRowHolder).bind { GreetingContent() }
            HomeFeedItem.RecommendedTracks -> (holder as ComposeRowHolder).bind {
                RecommendedTracksRow(vm, playlists, customAlbums, selectedTrackId, isPlaying)
            }
            HomeFeedItem.RecommendedAlbums -> (holder as ComposeRowHolder).bind {
                RecommendedAlbumsRow(vm, albumDownloads)
            }
            HomeFeedItem.SongsHeader -> (holder as ComposeRowHolder).bind { SectionTitle("Tus canciones") }
            is HomeFeedItem.TrackItem -> (holder as NativeTrackHolder).bind(
                item.track,
                progressFlow(item.track.id),
                playlists,
                customAlbums,
                selectedTrackId,
                isPlaying
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) =
        onBindViewHolder(holder, position, mutableListOf())

    private fun ComposeRowHolder.bind(content: @Composable () -> Unit) {
        (itemView as ComposeView).setContent(content)
    }

    @Composable private fun GreetingContent() {
        Column {
            Text(greetingForNow(), color = VaultColors.TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
        }
    }

    @Composable private fun RecommendedTracksRow(vm: MusicVaultViewModel, playlists: List<Playlist>, albums: List<Album>, selectedId: String?, playing: Boolean) {
        val tracks = recommendedTracksCache
        if (tracks.isEmpty()) return
        Text("Para ti", color = VaultColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            tracks.forEach { track ->
                RecommendationTrackCard(
                    track = track,
                    progressFlow = progressFlow(track.id),
                    onPlay = { vm.playRecommendation(track) },
                    actions = { TrackActionsMenu(track, playlists, albums, vm, vm.isFavorite(track.id)) },
                    isPlaying = playing && selectedId == track.id
                )
            }
        }
    }

    @Composable private fun RecommendedAlbumsRow(vm: MusicVaultViewModel, albumDownloads: Set<String>) {
        val albums = recommendedAlbumsCache
        if (albums.isEmpty()) return
        Text("Álbumes para ti", color = VaultColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            albums.forEach { album ->
                AlbumCard(
                    album = album,
                    onClick = { vm.openRecommendedAlbum(album) },
                    onDownloadAll = if (album.id.contains(":release-group:")) ({ vm.downloadRecommendedAlbum(album) }) else null,
                    isDownloading = album.id in albumDownloads
                )
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is NativeTrackHolder) holder.recycle()
        super.onViewRecycled(holder)
    }

    companion object {
        private const val TYPE_GREETING = 1
        private const val TYPE_RECOMMENDED_TRACKS = 2
        private const val TYPE_RECOMMENDED_ALBUMS = 3
        private const val TYPE_SONGS_HEADER = 4
        private const val TYPE_TRACK = 5
    }
}

private object LibraryDiff : DiffUtil.ItemCallback<Track>() {
    override fun areItemsTheSame(oldItem: Track, newItem: Track): Boolean = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Track, newItem: Track): Boolean = oldItem == newItem
}

internal class LibraryFeedAdapter(private val vm: MusicVaultViewModel) :
    ListAdapter<Track, NativeTrackHolder>(LibraryDiff) {
    private var playlists: List<Playlist> = emptyList()
    private var customAlbums: List<Album> = emptyList()
    private var selectedTrackId: String? = null
    private var isPlaying = false
    private val progressFlows = HashMap<String, Flow<Int?>>()

    init { setHasStableIds(true) }
    override fun getItemId(position: Int): Long = stableLongId("track:${getItem(position).id}")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NativeTrackHolder = NativeTrackHolder(parent.context, vm)

    fun update(tracks: List<Track>, playlists: List<Playlist>, customAlbums: List<Album>, selectedTrackId: String?, isPlaying: Boolean) {
        val oldSelected = this.selectedTrackId
        val oldPlaying = this.isPlaying
        val auxiliaryDataChanged = this.playlists != playlists || this.customAlbums != customAlbums
        this.playlists = playlists
        this.customAlbums = customAlbums
        this.selectedTrackId = selectedTrackId
        this.isPlaying = isPlaying

        submitList(tracks) {
            if (auxiliaryDataChanged) currentList.indices.forEach { notifyItemChanged(it) }
            if (oldSelected != selectedTrackId || oldPlaying != isPlaying) {
                currentList.forEachIndexed { index, track ->
                    if (track.id == oldSelected || track.id == selectedTrackId) notifyItemChanged(index, FeedPayload.PLAYER_STATE)
                }
            }
        }
    }

    private fun progressFlow(id: String): Flow<Int?> = progressFlows.getOrPut(id) { vm.downloadProgressFor(id) }

    override fun onBindViewHolder(holder: NativeTrackHolder, position: Int, payloads: MutableList<Any>) {
        holder.bind(getItem(position), progressFlow(getItem(position).id), playlists, customAlbums, selectedTrackId, isPlaying)
    }
    override fun onBindViewHolder(holder: NativeTrackHolder, position: Int) = onBindViewHolder(holder, position, mutableListOf())
    override fun onViewRecycled(holder: NativeTrackHolder) { holder.recycle(); super.onViewRecycled(holder) }
}

/** Native track row: avoids a Compose tree for the most frequently repeated item. */
internal class NativeTrackHolder(context: Context, private val vm: MusicVaultViewModel) :
    RecyclerView.ViewHolder(makeTrackRow(context)) {
    private val image = itemView.findViewWithTag<ImageView>("cover")
    private val title = itemView.findViewWithTag<TextView>("title")
    private val subtitle = itemView.findViewWithTag<TextView>("subtitle")
    private val progress = itemView.findViewWithTag<ProgressBar>("progress")
    private val play = itemView.findViewWithTag<NativePlayButton>("play")
    private val actions = itemView.findViewWithTag<ComposeView>("actions")
    private var track: Track? = null
    private var selectedId: String? = null
    private var progressJob: Job? = null
    private var progressFlow: Flow<Int?>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        itemView.setOnClickListener { track?.let(vm::selectTrack) }
        play.setOnClickListener {
            val t = track ?: return@setOnClickListener
            if (t.id == selectedId) vm.togglePlayback() else vm.selectTrack(t)
        }
    }

    fun bind(value: Track, flow: Flow<Int?>, playlists: List<Playlist>, albums: List<Album>, selectedId: String?, playing: Boolean) {
        track = value
        this.selectedId = selectedId
        title.text = value.title
        subtitle.text = "${value.artistName} · ${value.albumTitle}"
        loadCover(itemView.context, image, value.coverUri, 56)
        play.mode = if (playing && selectedId == value.id) NativePlayButton.Mode.PAUSE else NativePlayButton.Mode.PLAY
        title.setTextColor(if (playing && selectedId == value.id) Color.rgb(30, 215, 96) else Color.WHITE)

        if (progressFlow !== flow) {
            progressJob?.cancel()
            progressFlow = flow
            progressJob = scope.launch {
                flow.collect { p ->
                    val downloading = p != null && !value.isDownloaded
                    progress.visibility = if (downloading) View.VISIBLE else View.GONE
                    if (downloading) progress.progress = p!!.coerceIn(0, 100)
                    applyActionState(value, downloading, playlists, albums)
                }
            }
        }

        // Keep the same action contract as Search:
        // - downloaded track -> only the 3-dot menu
        // - non-downloaded track -> only Play
        // - actively downloading -> neither action (progress is shown)
        applyActionState(value, false, playlists, albums)
    }

    private fun applyActionState(value: Track, downloading: Boolean, playlists: List<Playlist>, albums: List<Album>) {
        play.visibility = if (value.isDownloaded || downloading) View.GONE else View.VISIBLE
        actions.visibility = if (value.isDownloaded && !downloading) View.VISIBLE else View.GONE
        if (value.isDownloaded && !downloading) {
            actions.setContent {
                TrackActionsMenu(value, playlists, albums, vm, vm.isFavorite(value.id))
            }
        }
    }

    fun updatePlaybackState(selectedId: String?, playing: Boolean) {
        this.selectedId = selectedId
        val current = track ?: return
        val active = playing && selectedId == current.id
        play.mode = if (active) NativePlayButton.Mode.PAUSE else NativePlayButton.Mode.PLAY
        title.setTextColor(if (active) Color.rgb(30, 215, 96) else Color.WHITE)
    }

    fun recycle() {
        progressJob?.cancel()
        progressJob = null
        progressFlow = null
    }
}


private class NativePlayButton(context: Context) : ImageButton(context) {
    enum class Mode { PLAY, PAUSE }
    var mode = Mode.PLAY
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    init { background = null; setPadding(0, 0, 0, 0); isClickable = true; isFocusable = true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f; val size = minOf(width, height) * .36f
        paint.color = if (isPressed) Color.LTGRAY else Color.WHITE; paint.style = Paint.Style.FILL
        if (mode == Mode.PLAY) {
            path.reset(); path.moveTo(cx - size * .32f, cy - size * .55f); path.lineTo(cx + size * .55f, cy); path.lineTo(cx - size * .32f, cy + size * .55f); path.close(); canvas.drawPath(path, paint)
        } else {
            canvas.drawRoundRect(cx-size*.45f, cy-size*.58f, cx-size*.08f, cy+size*.58f, size*.08f, size*.08f, paint)
            canvas.drawRoundRect(cx+size*.08f, cy-size*.58f, cx+size*.45f, cy+size*.58f, size*.08f, size*.08f, paint)
        }
    }
}

private fun makeTrackRow(context: Context): LinearLayout {
    fun dp(v: Int) = (v * context.resources.displayMetrics.density).roundToInt()
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        minimumHeight = dp(68)
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    val image = ImageView(context).apply {
        tag = "cover"; layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)); scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(35,35,35)); clipToOutline = true
    }
    row.addView(image)
    val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) } }
    text.addView(TextView(context).apply { tag="title"; setTextColor(Color.WHITE); textSize=15f; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END; setTypeface(typeface, android.graphics.Typeface.BOLD) })
    text.addView(TextView(context).apply { tag="subtitle"; setTextColor(Color.rgb(179,179,179)); textSize=12f; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
    text.addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { tag="progress"; visibility=View.GONE; max=100; layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin=dp(3) } })
    row.addView(text)
    row.addView(NativePlayButton(context).apply { tag="play"; layoutParams=LinearLayout.LayoutParams(dp(48), dp(48)) })
    row.addView(ComposeView(context).apply {
        tag="actions"; layoutParams=LinearLayout.LayoutParams(dp(44), dp(48)); setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    })
    return row
}


/** Optimized local album detail: one native header + native track rows with DiffUtil. */
internal class LocalAlbumDetailAdapter(private val vm: MusicVaultViewModel) :
    ListAdapter<Track, RecyclerView.ViewHolder>(LocalAlbumTrackDiff) {

    companion object {
        const val TYPE_HEADER = 30
        const val TYPE_TRACK = 31
        const val STATE_PAYLOAD = "player_state"
    }

    private var album: Album? = null
    private var playlists: List<Playlist> = emptyList()
    private var albums: List<Album> = emptyList()
    private var selectedTrackId: String? = null
    private var isPlaying: Boolean = false
    private var onBack: (() -> Unit)? = null
    private val progressFlows = HashMap<String, Flow<Int?>>()

    init { setHasStableIds(true) }

    override fun getItemCount(): Int = super.getItemCount() + 1

    override fun getItemId(position: Int): Long =
        if (position == 0) Long.MIN_VALUE + 30 else stableLongId("album-detail:${getItem(position - 1).id}")

    override fun getItemViewType(position: Int): Int = if (position == 0) TYPE_HEADER else TYPE_TRACK

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER) LocalAlbumHeaderHolder(parent.context)
        else NativeTrackHolder(parent.context, vm)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position == 0) {
            (holder as LocalAlbumHeaderHolder).bind(album, onBack)
        } else {
            val track = getItem(position - 1)
            (holder as NativeTrackHolder).bind(
                track,
                progressFlow(track.id),
                playlists,
                albums,
                selectedTrackId,
                isPlaying
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(STATE_PAYLOAD) && position > 0 && holder is NativeTrackHolder) {
            holder.updatePlaybackState(selectedTrackId, isPlaying)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is NativeTrackHolder) holder.recycle()
        super.onViewRecycled(holder)
    }

    private fun progressFlow(id: String): Flow<Int?> = progressFlows.getOrPut(id) { vm.downloadProgressFor(id) }

    fun update(
        album: Album,
        tracks: List<Track>,
        playlists: List<Playlist>,
        albums: List<Album>,
        selectedTrackId: String?,
        isPlaying: Boolean,
        onBack: () -> Unit
    ) {
        val headerChanged = this.album != album || this.onBack !== onBack
        this.album = album
        this.playlists = playlists
        this.albums = albums
        val oldSelected = this.selectedTrackId
        val oldPlaying = this.isPlaying
        this.selectedTrackId = selectedTrackId
        this.isPlaying = isPlaying
        this.onBack = onBack
        submitList(tracks.toList()) {
            if (headerChanged && itemCount > 0) notifyItemChanged(0)
            if (oldSelected != selectedTrackId || oldPlaying != isPlaying) {
                if (oldSelected != null) currentList.indexOfFirst { it.id == oldSelected }.takeIf { it >= 0 }?.let { notifyItemChanged(it + 1, STATE_PAYLOAD) }
                if (selectedTrackId != null && selectedTrackId != oldSelected) currentList.indexOfFirst { it.id == selectedTrackId }.takeIf { it >= 0 }?.let { notifyItemChanged(it + 1, STATE_PAYLOAD) }
            }
        }
    }

}

private object LocalAlbumTrackDiff : DiffUtil.ItemCallback<Track>() {
    override fun areItemsTheSame(oldItem: Track, newItem: Track) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Track, newItem: Track) = oldItem == newItem
}

private class LocalAlbumHeaderHolder(context: Context) : RecyclerView.ViewHolder(makeLocalAlbumHeader(context)) {
    private val back = itemView.findViewWithTag<ImageButton>("back")
    private val cover = itemView.findViewWithTag<ImageView>("cover")
    private val title = itemView.findViewWithTag<TextView>("title")
    private val artist = itemView.findViewWithTag<TextView>("artist")
    private val year = itemView.findViewWithTag<TextView>("year")
    private var callback: (() -> Unit)? = null

    init { back.setOnClickListener { callback?.invoke() } }

    fun bind(album: Album?, onBack: (() -> Unit)?) {
        if (album == null) return
        callback = onBack
        title.text = album.title
        artist.text = album.artistName
        year.text = if (album.year > 0) album.year.toString() else ""
        year.visibility = if (album.year > 0) View.VISIBLE else View.GONE
        if (album.id == "playlist:favorites") {
            cover.setImageDrawable(HeartDrawable())
            cover.setColorFilter(null)
            cover.scaleType = ImageView.ScaleType.CENTER
        } else {
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            loadCover(itemView.context, cover, album.coverUri, 180)
        }
    }
}

private fun makeLocalAlbumHeader(context: Context): LinearLayout {
    fun dp(v: Int) = (v * context.resources.displayMetrics.density).roundToInt()
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(14))
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    root.addView(ImageButton(context).apply {
        tag = "back"; background = null; setImageResource(android.R.drawable.ic_media_previous); setColorFilter(Color.WHITE)
        contentDescription = "Atrás"; layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    })
    val info = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    info.addView(ImageView(context).apply {
        tag = "cover"; layoutParams = LinearLayout.LayoutParams(dp(150), dp(150)); scaleType = ImageView.ScaleType.CENTER_CROP
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(35,35,35)) }
    })
    val text = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) }
    }
    text.addView(TextView(context).apply { tag="title"; setTextColor(Color.WHITE); textSize=24f; setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END })
    text.addView(TextView(context).apply { tag="artist"; setTextColor(Color.rgb(179,179,179)); textSize=14f; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END; layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin=dp(4) } })
    text.addView(TextView(context).apply { tag="year"; setTextColor(Color.rgb(130,130,130)); textSize=12f; layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin=dp(2) } })
    info.addView(text)
    root.addView(info)
    return root
}

private class HeartDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        val path = Path()
        val cx = w / 2f; val top = h * .23f
        path.moveTo(cx, h * .80f)
        path.cubicTo(w*.10f, h*.55f, w*.14f, top, w*.32f, top)
        path.cubicTo(w*.43f, top, cx, h*.34f, cx, h*.34f)
        path.cubicTo(cx, h*.34f, w*.57f, top, w*.68f, top)
        path.cubicTo(w*.86f, top, w*.90f, h*.55f, cx, h*.80f)
        path.close()
        canvas.drawPath(path, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

internal class AlbumFeedAdapter(private val vm: MusicVaultViewModel) :
    ListAdapter<Playlist, RecyclerView.ViewHolder>(AlbumDiff) {

    companion object {
        const val TYPE_HEADER = 20
        const val TYPE_ALBUM = 21
    }

    var onAddAlbum: (() -> Unit)? = null

    init { setHasStableIds(true) }

    override fun getItemCount(): Int = super.getItemCount() + 1

    override fun getItemId(position: Int): Long =
        if (position == 0) Long.MIN_VALUE else stableLongId("album:${getItem(position - 1).id}")

    override fun getItemViewType(position: Int): Int =
        if (position == 0) TYPE_HEADER else TYPE_ALBUM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER) AlbumHeaderHolder(parent.context)
        else AlbumCardHolder(parent.context, vm)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position == 0) (holder as AlbumHeaderHolder).bind(onAddAlbum)
        else (holder as AlbumCardHolder).bind(getItem(position - 1))
    }

    fun update(items: List<Playlist>) {
        submitList(items.toList())
    }
}

private object AlbumDiff : DiffUtil.ItemCallback<Playlist>() {
    override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist) = oldItem == newItem
}

private class AlbumHeaderHolder(context: Context) : RecyclerView.ViewHolder(makeAlbumHeader(context)) {
    private val add = itemView.findViewWithTag<ImageButton>("addAlbum")
    private var callback: (() -> Unit)? = null
    init { add.setOnClickListener { callback?.invoke() } }
    fun bind(onAdd: (() -> Unit)?) { callback = onAdd }
}

private fun makeAlbumHeader(context: Context): LinearLayout {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).roundToInt()
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(18))
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
    text.addView(TextView(context).apply { this.text = "Álbumes"; setTextColor(Color.WHITE); textSize = 28f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
    text.addView(TextView(context).apply { this.text = "Tus colecciones de canciones descargadas"; setTextColor(Color.rgb(179,179,179)); textSize = 14f })
    root.addView(text)
    root.addView(ImageButton(context).apply {
        tag = "addAlbum"; background = null; setImageResource(android.R.drawable.ic_input_add); setColorFilter(Color.WHITE);
        contentDescription = "Nuevo álbum"; layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    })
    return root
}

private class AlbumCardHolder(context: Context, private val vm: MusicVaultViewModel) : RecyclerView.ViewHolder(makeAlbumCard(context)) {
    private val cover = itemView.findViewWithTag<ImageView>("albumCover")
    private val title = itemView.findViewWithTag<TextView>("albumTitle")
    private val description = itemView.findViewWithTag<TextView>("albumDescription")
    private var playlist: Playlist? = null
    init { itemView.setOnClickListener { playlist?.let(vm::openPlaylist) } }
    fun bind(value: Playlist) {
        playlist = value
        title.text = value.title
        description.text = value.description
        if (value.id == "playlist:favorites") {
            cover.setImageDrawable(HeartDrawable())
            cover.setColorFilter(null)
            cover.scaleType = ImageView.ScaleType.CENTER
        } else {
            cover.setColorFilter(null)
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }
}

private fun makeAlbumCard(context: Context): LinearLayout {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).roundToInt()
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12).toFloat(); setColor(Color.rgb(30,30,30))
        }
    }
    root.addView(ImageView(context).apply { tag="albumCover"; layoutParams=LinearLayout.LayoutParams(dp(92), dp(92)); scaleType=ImageView.ScaleType.CENTER_INSIDE })
    val text=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL; layoutParams=LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart=dp(14); marginEnd=dp(12) } }
    text.addView(TextView(context).apply { tag="albumTitle"; setTextColor(Color.WHITE); textSize=16f; setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
    text.addView(TextView(context).apply { tag="albumDescription"; setTextColor(Color.rgb(179,179,179)); textSize=13f; maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END })
    root.addView(text)
    return root
}

private fun loadCover(context: Context, image: ImageView, url: String?, sizePx: Int) {
    if (url.isNullOrBlank()) { image.setImageDrawable(null); image.setBackgroundColor(Color.rgb(35,35,35)); return }
    val request = ImageRequest.Builder(context).data(url).size(Size(sizePx, sizePx)).memoryCacheKey("musicvault:$url:$sizePx").diskCacheKey("musicvault:$url:$sizePx").crossfade(false).target(image).build()
    CoverImageCache.loader(context).enqueue(request)
}

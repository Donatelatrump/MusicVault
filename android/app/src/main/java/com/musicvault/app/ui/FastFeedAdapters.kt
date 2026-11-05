package com.musicvault.app.ui

import com.musicvault.app.R
import androidx.compose.ui.res.stringResource

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.CheckBox
import android.widget.LinearLayout
import android.graphics.drawable.Drawable
import android.widget.ProgressBar
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.EditText
import android.widget.TextView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.recyclerview.widget.RecyclerView
import coil.request.ImageRequest
import coil.dispose
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
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
    }
)

internal sealed interface HomeFeedItem {
    data object Greeting : HomeFeedItem
    data object RecommendedTracks : HomeFeedItem
    data object RecommendedAlbums : HomeFeedItem
    data class SongsHeader(@androidx.annotation.StringRes val title: Int) : HomeFeedItem
    data class TrackItem(val track: Track) : HomeFeedItem
}

private fun HomeFeedItem.stableId(): Long = when (this) {
    HomeFeedItem.Greeting -> 1L
    HomeFeedItem.RecommendedTracks -> 2L
    HomeFeedItem.RecommendedAlbums -> 3L
    is HomeFeedItem.SongsHeader -> stableLongId("header:${title}")
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
    private var recommendationsCache: List<Track> = emptyList()
    private var hybridDesign = true
    private var isOnline = true
    private var offlinePlayableIds: Set<String> = emptySet()
    private val progressFlows = HashMap<String, Flow<Int?>>()
    private val trackPositionById = HashMap<String, Int>()

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = getItem(position).stableId()

    override fun getItemViewType(position: Int): Int = when (val item = getItem(position)) {
        HomeFeedItem.Greeting -> TYPE_GREETING
        HomeFeedItem.RecommendedTracks -> TYPE_RECOMMENDED_TRACKS
        HomeFeedItem.RecommendedAlbums -> TYPE_RECOMMENDED_ALBUMS
        is HomeFeedItem.SongsHeader -> TYPE_SONGS_HEADER
        is HomeFeedItem.TrackItem -> TYPE_TRACK
    }

    private fun rebuildTrackPositionIndex() {
        trackPositionById.clear()
        currentList.forEachIndexed { position, item ->
            if (item is HomeFeedItem.TrackItem) {
                trackPositionById[item.track.id] = position
            }
        }
    }

    private inline fun <T> sameIds(oldList: List<T>, newList: List<T>, crossinline idOf: (T) -> String): Boolean {
        if (oldList.size != newList.size) return false
        for (index in oldList.indices) {
            if (idOf(oldList[index]) != idOf(newList[index])) return false
        }
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_TRACK) NativeTrackHolder(parent.context, vm)
        else ComposeRowHolder(parent)

    fun update(
        recommendedTracks: List<Track>,
        recommendedAlbums: List<Album>,
        recommendations: List<Track>,
        playlists: List<Playlist>,
        customAlbums: List<Album>,
        albumDownloads: Set<String>,
        selectedTrackId: String?,
        isPlaying: Boolean,
        isOnline: Boolean,
        offlinePlayableIds: Set<String>,
        hybridDesign: Boolean,
        onContentCommitted: (() -> Unit)? = null
    ) {
        val oldSelected = this.selectedTrackId
        val oldPlaying = this.isPlaying
        // Identity-first comparisons: the lists are usually the SAME instances
        // across recompositions, so the element-wise map/equals only runs when
        // a genuinely new list arrived.
        val recommendationsChanged =
            (recommendedTracks !== recommendedTracksCache && !sameIds(recommendedTracksCache, recommendedTracks) { it.id }) ||
                (recommendedAlbums !== recommendedAlbumsCache && !sameIds(recommendedAlbumsCache, recommendedAlbums) { it.id }) ||
                (recommendations !== recommendationsCache && !sameIds(recommendationsCache, recommendations) { it.id })
        val auxiliaryDataChanged = this.playlists != playlists || this.customAlbums != customAlbums
        val downloadsChanged = this.albumDownloads != albumDownloads
        val designChanged = this.hybridDesign != hybridDesign
        val connectivityChanged = this.isOnline != isOnline
        val offlineAvailabilityChanged = this.offlinePlayableIds != offlinePlayableIds

        // This update runs on every recomposition of the hosting screen (playback
        // ticks included). Skip the item rebuild and DiffUtil when nothing changed.
        if (!auxiliaryDataChanged && !recommendationsChanged && !downloadsChanged && !designChanged &&
            !connectivityChanged && !offlineAvailabilityChanged &&
            oldSelected == selectedTrackId && oldPlaying == isPlaying
        ) {
            // Home may already contain the correct list when onboarding starts
            // waiting for the visual handoff. Treat the existing committed list
            // as ready instead of waiting for another data mutation.
            if (recommendedTracks.isNotEmpty() && recommendedAlbums.isNotEmpty()) {
                onContentCommitted?.invoke()
            }
            return
        }

        this.playlists = playlists
        this.customAlbums = customAlbums
        this.albumDownloads = albumDownloads
        this.selectedTrackId = selectedTrackId
        this.isPlaying = isPlaying
        this.hybridDesign = hybridDesign
        this.isOnline = isOnline
        this.offlinePlayableIds = offlinePlayableIds
        recommendedTracksCache = recommendedTracks
        recommendedAlbumsCache = recommendedAlbums
        recommendationsCache = recommendations

        // The horizontal "Para ti" carousel shows the first quick picks; the
        // vertical discovery list shows the rest so the two never duplicate.
        val quickPickCount = 8
        val newItems = buildList {
            add(HomeFeedItem.Greeting)
            if (recommendedTracks.isNotEmpty()) add(HomeFeedItem.RecommendedTracks)
            add(HomeFeedItem.RecommendedAlbums)
            val discoveryList = recommendations.drop(quickPickCount)
            if (discoveryList.isNotEmpty()) {
                add(HomeFeedItem.SongsHeader(R.string.ui_recomendaciones_para_ti_b135e5))
                discoveryList.forEach { add(HomeFeedItem.TrackItem(it)) }
            }
        }

        submitList(newItems) {
            rebuildTrackPositionIndex()
            if (auxiliaryDataChanged) currentList.forEachIndexed { i, item ->
                if (item is HomeFeedItem.TrackItem) notifyItemChanged(i)
            }
            if (recommendationsChanged) currentList.forEachIndexed { i, item ->
                if (item is HomeFeedItem.RecommendedTracks || item is HomeFeedItem.RecommendedAlbums) notifyItemChanged(i)
            }
            if (downloadsChanged) currentList.indexOfFirst { it is HomeFeedItem.RecommendedAlbums }
                .takeIf { it >= 0 }?.let { notifyItemChanged(it) }
            if (designChanged) currentList.forEachIndexed { index, item ->
                if (item !is HomeFeedItem.TrackItem) notifyItemChanged(index)
            }
            if (connectivityChanged || offlineAvailabilityChanged) currentList.forEachIndexed { index, item ->
                if (item is HomeFeedItem.TrackItem || item is HomeFeedItem.RecommendedTracks) notifyItemChanged(index)
            }
            notifyPlayerRows(oldSelected, selectedTrackId, oldPlaying, isPlaying)
            onContentCommitted?.invoke()
        }
    }

    private fun notifyPlayerRows(oldSelected: String?, newSelected: String?, oldPlaying: Boolean, newPlaying: Boolean) {
        if (oldSelected == newSelected && oldPlaying == newPlaying) return
        if (oldSelected != null) trackPositionById[oldSelected]?.let { notifyItemChanged(it, FeedPayload.PLAYER_STATE) }
        if (newSelected != null && newSelected != oldSelected) {
            trackPositionById[newSelected]?.let { notifyItemChanged(it, FeedPayload.PLAYER_STATE) }
        }
    }

    private fun progressFlow(id: String): Flow<Int?> = progressFlows.getOrPut(id) { vm.downloadProgressFor(id) }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == FeedPayload.PLAYER_STATE } && holder is NativeTrackHolder) {
            holder.updatePlaybackState(selectedTrackId, isPlaying)
            return
        }
        when (val item = getItem(position)) {
            HomeFeedItem.Greeting -> (holder as ComposeRowHolder).bind { GreetingContent() }
            HomeFeedItem.RecommendedTracks -> (holder as ComposeRowHolder).bind {
                RecommendedTracksRow(vm, playlists, customAlbums, selectedTrackId, isPlaying, isOnline, offlinePlayableIds)
            }
            HomeFeedItem.RecommendedAlbums -> (holder as ComposeRowHolder).bind {
                RecommendedAlbumsRow(vm, albumDownloads)
            }
            is HomeFeedItem.SongsHeader -> (holder as ComposeRowHolder).bind {
                Column {
                    if (hybridDesign) {
                        HorizontalDivider(color = VaultColors.Divider)
                        Spacer(Modifier.height(14.dp))
                    }
                    SectionTitle(stringResource(item.title), hybridDesign)
                }
            }
            is HomeFeedItem.TrackItem -> (holder as NativeTrackHolder).bind(
                item.track,
                progressFlow(item.track.id),
                playlists,
                customAlbums,
                selectedTrackId,
                isPlaying,
                isOnline,
                offlinePlayableIds
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) =
        onBindViewHolder(holder, position, mutableListOf())

    private fun ComposeRowHolder.bind(content: @Composable () -> Unit) {
        (itemView as ComposeView).setContent(content)
        // The first composition applies AFTER this view has been measured, so
        // the RecyclerView can keep the empty-composition height. Without a
        // re-request, the grown carousel paints over the neighboring rows and
        // the next section title ("Recomendaciones para ti") shows through the
        // gaps between album cards.
        itemView.post { itemView.requestLayout() }
    }

    @Composable private fun GreetingContent() {
        Column {
            Text(greetingForNow(), color = VaultColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
        }
    }

    @Composable private fun RecommendedTracksRow(vm: MusicVaultViewModel, playlists: List<Playlist>, albums: List<Album>, selectedId: String?, playing: Boolean, isOnline: Boolean, offlinePlayableIds: Set<String>) {
        val tracks = recommendedTracksCache
        if (tracks.isEmpty()) return
        // clipToBounds: this row's height is measured while its composition is
        // still empty; a late composition must never paint over the rows below.
        Column(Modifier.fillMaxWidth().clipToBounds()) {
            if (hybridDesign) {
                HorizontalDivider(color = VaultColors.Divider)
                Spacer(Modifier.height(14.dp))
            }
            Text(stringResource(R.string.ui_para_ti_3d70f6), color = VaultColors.TextPrimary, fontSize = if (hybridDesign) 19.sp else 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            // Lazy row: only visible cards are composed/collected. The old
            // horizontalScroll Row composed every card even when offscreen, which
            // made the first Home frame noticeably slow.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(tracks, key = { it.id }) { track ->
                    RecommendationTrackCard(
                        track = track,
                        progressFlow = progressFlow(track.id),
                        onPlay = { vm.playRecommendation(track) },
                        actions = { TrackActionsMenu(track, playlists, albums, vm, vm.isFavorite(track.id)) },
                        isPlaying = playing && selectedId == track.id,
                        enabled = isOnline || track.id in offlinePlayableIds,
                        hybrid = hybridDesign
                    )
                }
            }
        }
    }

    @Composable private fun RecommendedAlbumsRow(vm: MusicVaultViewModel, albumDownloads: Set<String>) {
        val albums = recommendedAlbumsCache
        Column(Modifier.fillMaxWidth().clipToBounds()) {
            if (hybridDesign) {
                HorizontalDivider(color = VaultColors.Divider)
                Spacer(Modifier.height(14.dp))
            }
            Text(stringResource(R.string.ui_albumes_para_ti_9c66e7), color = VaultColors.TextPrimary, fontSize = if (hybridDesign) 19.sp else 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                item(key = DJ_MIX_ID) {
                    AlbumCard(
                        album = Album(DJ_MIX_ID, stringResource(R.string.dj_title), "dj", stringResource(R.string.dj_subtitle), 0),
                        onClick = vm::openDjAlbum,
                        hybrid = hybridDesign
                    )
                }
                items(albums, key = { it.id }) { album ->
                    AlbumCard(
                        album = album,
                        onClick = { vm.openRecommendedAlbum(album) },
                        onDownloadAll = if (album.id.contains(":release-group:")) ({ vm.downloadRecommendedAlbum(album) }) else null,
                        isDownloading = album.id in albumDownloads,
                        hybrid = hybridDesign
                    )
                }
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

internal class LibraryFeedAdapter(private val vm: MusicVaultViewModel, private val hybridDesign: Boolean = false) :
    ListAdapter<Track, NativeTrackHolder>(LibraryDiff) {
    private var playlists: List<Playlist> = emptyList()
    private var customAlbums: List<Album> = emptyList()
    private var selectedTrackId: String? = null
    private var tracksCache: List<Track> = emptyList()
    private var isPlaying = false
    private val progressFlows = HashMap<String, Flow<Int?>>()

    init { setHasStableIds(true) }
    override fun getItemId(position: Int): Long = stableLongId("track:${getItem(position).id}")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NativeTrackHolder = NativeTrackHolder(parent.context, vm, hybridDesign)

    fun update(tracks: List<Track>, playlists: List<Playlist>, customAlbums: List<Album>, selectedTrackId: String?, isPlaying: Boolean) {
        val oldSelected = this.selectedTrackId
        val oldPlaying = this.isPlaying
        val tracksChanged = this.tracksCache != tracks
        val auxiliaryDataChanged = this.playlists != playlists || this.customAlbums != customAlbums
        // Runs on every recomposition; skip DiffUtil when nothing visible changed.
        if (!tracksChanged && !auxiliaryDataChanged && oldSelected == selectedTrackId && oldPlaying == isPlaying) return
        this.playlists = playlists
        this.customAlbums = customAlbums
        this.selectedTrackId = selectedTrackId
        this.isPlaying = isPlaying
        this.tracksCache = tracks

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
        if (payloads.isNotEmpty() && payloads.all { it == FeedPayload.PLAYER_STATE }) {
            holder.updatePlaybackState(selectedTrackId, isPlaying)
            return
        }
        holder.bind(getItem(position), progressFlow(getItem(position).id), playlists, customAlbums, selectedTrackId, isPlaying)
    }
    override fun onBindViewHolder(holder: NativeTrackHolder, position: Int) = onBindViewHolder(holder, position, mutableListOf())
    override fun onViewRecycled(holder: NativeTrackHolder) { holder.recycle(); super.onViewRecycled(holder) }
}

/** Native track row: avoids a Compose tree for the most frequently repeated item. */
internal class NativeTrackHolder(
    context: Context,
    private val vm: MusicVaultViewModel,
    private val hybridDesign: Boolean = false,
    private val onRowClick: ((Track) -> Unit)? = null,
    private val onPlayNew: ((Track) -> Unit)? = null,
    private val onRemoveFromPlaylist: ((Track) -> Unit)? = null,
    private val allowMissingTrackLongPressRemoval: Boolean = false
) : RecyclerView.ViewHolder(makeTrackRow(context, hybridDesign)) {
    private val image = itemView.findViewWithTag<ImageView>("cover")
    private val title = itemView.findViewWithTag<TextView>("title")
    private val subtitle = itemView.findViewWithTag<TextView>("subtitle")
    private val progress = itemView.findViewWithTag<ProgressBar>("progress")
    private val play = itemView.findViewWithTag<NativePlayButton>("play")
    private val actions = itemView.findViewWithTag<NativeMoreButton>("actions")
    private var track: Track? = null
    private var boundCoverUrl: String? = null
    private var coverBound = false
    private var selectedId: String? = null
    private var currentPlaylists: List<Playlist> = emptyList()
    private var progressJob: Job? = null
    private var progressFlow: Flow<Int?>? = null
    private var currentProgress: Int? = null
    private var playableNow: Boolean = true
    private var playingNow: Boolean = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        installTrackPressFeedback(itemView)
        actions.setOnClickListener { showActionsMenu(it) }
        NativeAccessibility.button(actions, itemView.context.getString(R.string.ui_mas_opciones_977c75))
        itemView.setOnClickListener {
            if (playableNow) track?.let { t -> (onRowClick ?: vm::selectTrack).invoke(t) }
        }
        itemView.setOnLongClickListener {
            val current = track ?: return@setOnLongClickListener false
            if (allowMissingTrackLongPressRemoval && (!current.isDownloaded || current.audioUri.isNullOrBlank())) {
                confirmPlaylistRemoval(current)
                true
            } else {
                false
            }
        }
        play.setOnClickListener {
            val t = track ?: return@setOnClickListener
            if (!playableNow) return@setOnClickListener
            if (t.id == selectedId) vm.togglePlayback()
            else (onPlayNew ?: vm::selectTrack).invoke(t)
        }
    }

    fun bind(value: Track, flow: Flow<Int?>, playlists: List<Playlist>, albums: List<Album>, selectedId: String?, playing: Boolean, isOnline: Boolean = true, offlinePlayableIds: Set<String> = emptySet()) {
        if (track?.id != value.id) currentProgress = null
        track = value
        currentPlaylists = playlists
        this.selectedId = selectedId
        playableNow = isOnline || value.id in offlinePlayableIds
        itemView.isEnabled = true
        play.isEnabled = playableNow
        playingNow = playing
        itemView.alpha = if (playableNow) 1f else 0.38f

        title.text = value.title
        subtitle.text = "${value.artistName} · ${value.albumTitle}"
        if (!coverBound || boundCoverUrl != value.coverUri) {
            loadCover(itemView.context, image, value.coverUri, 56)
            boundCoverUrl = value.coverUri
            coverBound = true
        }
        play.mode = if (playing && selectedId == value.id) NativePlayButton.Mode.PAUSE else NativePlayButton.Mode.PLAY
        title.setTextColor(if (playing && selectedId == value.id) Color.rgb(224, 164, 88) else Color.rgb(241,234,217))

        if (progressFlow !== flow) {
            progressJob?.cancel()
            progressFlow = flow
            progressJob = scope.launch {
                // Reads holder fields instead of stale bind-time captures.
                flow.collect { p ->
                    val t = track ?: return@collect
                    val ready = t.isDownloaded && !t.audioUri.isNullOrBlank()
                    val downloading = p != null && !ready
                    currentProgress = p
                    progress.visibility = if (downloading) View.VISIBLE else View.GONE
                    if (downloading) progress.progress = p!!.coerceIn(0, 100)
                    // Playback is always the row tap. Keep overflow available
                    // for local and online tracks, including active downloads.
                    play.visibility = View.GONE
                    actions.visibility = View.VISIBLE
                    updateAccessibility()
                }
            }
        }

        // Universal interaction contract:
        // - tap row -> play (local file or streaming)
        // - trailing 3-dot -> actions, including Download for online tracks
        applyActionState(value, false)
        updateAccessibility()
    }

    private fun applyActionState(value: Track, downloading: Boolean) {
        play.visibility = View.GONE
        actions.visibility = View.VISIBLE
        actions.contentDescription = "Más opciones para ${value.title}"
    }

    private fun showActionsMenu(anchor: View) {
        val current = track ?: return
        PopupMenu(anchor.context, anchor, Gravity.END).apply {
            menu.add(0, MENU_QUEUE, 0, itemView.context.getString(R.string.ui_agregar_a_cola_de_reproduccion_f8c617))
            menu.add(0, MENU_FAVORITE, 1, if (vm.isFavorite(current.id)) itemView.context.getString(R.string.ui_quitar_de_me_gusta_d81660) else itemView.context.getString(R.string.ui_me_gusta_f3b81e))
            menu.add(0, MENU_ALBUM, 2, itemView.context.getString(R.string.ui_agregar_a_album_7c4ad3))
            menu.add(0, MENU_RADIO, 3, itemView.context.getString(R.string.ui_iniciar_radio_de_esta_cancion_cc8ac4))
            if (onRemoveFromPlaylist != null) menu.add(0, MENU_REMOVE_FROM_PLAYLIST, 3, itemView.context.getString(R.string.ui_quitar_de_esta_playlist_d34f9e))
            when {
                current.isDownloaded && !current.audioUri.isNullOrBlank() ->
                    menu.add(0, MENU_DELETE, 4, itemView.context.getString(R.string.ui_eliminar_cancion_descargada_8c5b0a))
                currentProgress != null ->
                    menu.add(0, MENU_CANCEL_DOWNLOAD, 4, itemView.context.getString(R.string.ui_cancelar_descarga_f42d93))
                else ->
                    menu.add(0, MENU_DOWNLOAD, 4, itemView.context.getString(R.string.ui_descargar_8b98cb))
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_QUEUE -> vm.addToQueue(current)
                    MENU_FAVORITE -> vm.toggleFavorite(current)
                    MENU_ALBUM -> showAlbumChooser(current)
                    MENU_RADIO -> vm.startRadio(current)
                    MENU_REMOVE_FROM_PLAYLIST -> confirmPlaylistRemoval(current)
                    MENU_DOWNLOAD -> vm.requestDownload(current)
                    MENU_CANCEL_DOWNLOAD -> vm.cancelDownload(current.id)
                    MENU_DELETE -> confirmTrackDeletion(current)
                }
                true
            }
            show()
        }
    }

    private fun showAlbumChooser(current: Track) {
        val context = itemView.context
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle(itemView.context.getString(R.string.ui_agregar_a_album_7c4ad3))
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton(itemView.context.getString(R.string.ui_cancelar_c111e0), null)
            .create()
        list.addView(TextView(context).apply {
            text = itemView.context.getString(R.string.ui_crear_nuevo_album_ee0a30)
            minimumHeight = dp(48)
            NativeAccessibility.button(this, itemView.context.getString(R.string.ui_crear_nuevo_album_5d97f7))
            textSize = 16f
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { dialog.dismiss(); showCreateAlbum(current) }
        })
        currentPlaylists.forEach { playlist ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(context).apply {
                text = playlist.title
                minimumHeight = dp(48)
                NativeAccessibility.button(this, "Agregar a ${playlist.title}")
                textSize = 16f
                setPadding(dp(16), dp(14), dp(8), dp(14))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { vm.addTrackToPlaylist(current, playlist.id); dialog.dismiss() }
            })
            if (playlist.id != "playlist:favorites") {
                row.addView(ImageButton(context).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    contentDescription = "Eliminar ${playlist.title}"
                    background = null
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    setOnClickListener {
                        android.app.AlertDialog.Builder(context)
                            .setTitle(itemView.context.getString(R.string.ui_eliminar_album_8f78a0))
                            .setMessage("¿Quieres eliminar \"${playlist.title}\"?")
                            .setPositiveButton(itemView.context.getString(R.string.ui_eliminar_54e140)) { _, _ -> vm.deletePlaylist(playlist.id); dialog.dismiss() }
                            .setNegativeButton(itemView.context.getString(R.string.ui_cancelar_c111e0), null)
                            .show()
                    }
                })
            }
            list.addView(row)
        }
        dialog.show()
    }

    private fun showCreateAlbum(current: Track) {
        val context = itemView.context
        val input = EditText(context).apply { hint = itemView.context.getString(R.string.ui_nombre_e68491); isSingleLine = true }
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle(itemView.context.getString(R.string.ui_crear_nuevo_album_5d97f7))
            .setView(input)
            .setPositiveButton(itemView.context.getString(R.string.ui_crear_y_agregar_d4727e), null)
            .setNegativeButton(itemView.context.getString(R.string.ui_cancelar_c111e0), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    vm.createPlaylist(name, listOf(current))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun confirmPlaylistRemoval(current: Track) {
        val remove = onRemoveFromPlaylist ?: return
        android.app.AlertDialog.Builder(itemView.context)
            .setTitle(itemView.context.getString(R.string.ui_quitar_de_la_playlist_ef39b8))
            .setMessage("¿Quieres quitar \"${current.title}\" de esta playlist? El archivo descargado no se borrará del dispositivo.")
            .setPositiveButton("Quitar") { _, _ -> remove(current) }
            .setNegativeButton(itemView.context.getString(R.string.ui_cancelar_c111e0), null)
            .show()
    }

    private fun confirmTrackDeletion(current: Track) {
        android.app.AlertDialog.Builder(itemView.context)
            .setTitle(itemView.context.getString(R.string.ui_eliminar_cancion_d65308))
            .setMessage("¿Quieres eliminar \"${current.title}\" de las descargas de MusicVault?")
            .setPositiveButton(itemView.context.getString(R.string.ui_eliminar_54e140)) { _, _ -> vm.deleteTrack(current) }
            .setNegativeButton(itemView.context.getString(R.string.ui_cancelar_c111e0), null)
            .show()
    }

    fun updatePlaybackState(selectedId: String?, playing: Boolean) {
        this.selectedId = selectedId
        playingNow = playing
        val current = track ?: return
        val active = playing && selectedId == current.id
        play.mode = if (active) NativePlayButton.Mode.PAUSE else NativePlayButton.Mode.PLAY
        title.setTextColor(if (active) Color.rgb(224, 164, 88) else Color.rgb(241,234,217))
        updateAccessibility()
    }

    private fun updateAccessibility() {
        val current = track ?: return
        val active = current.id == selectedId && playingNow
        val playbackLabel = if (active) itemView.context.getString(R.string.ui_pausar_8e0f5e) else itemView.context.getString(R.string.ui_reproducir_d1a188)
        val ready = current.isDownloaded && !current.audioUri.isNullOrBlank()
        val state = buildList {
            if (active) add(itemView.context.getString(R.string.ui_reproduciendo_2d20e2)) else if (current.id == selectedId) add(itemView.context.getString(R.string.ui_pausada_144ffd))
            if (ready) add(itemView.context.getString(R.string.ui_descargada_c000cf))
            if (currentProgress != null && !ready) add("Descargando ${currentProgress!!.coerceIn(0, 100)} por ciento")
            if (!playableNow) add(itemView.context.getString(R.string.ui_no_disponible_sin_conexion_04cfee))
            if (vm.isFavorite(current.id)) add(itemView.context.getString(R.string.ui_me_gusta_f3b81e))
        }.joinToString(", ")
        val custom = buildList {
            if (playableNow) add(NativeAccessibility.Action(playbackLabel) {
                if (current.id == selectedId) vm.togglePlayback() else (onPlayNew ?: vm::selectTrack).invoke(current)
            })
            add(NativeAccessibility.Action(itemView.context.getString(R.string.ui_agregar_a_cola_ca3388)) { vm.addToQueue(current) })
            add(NativeAccessibility.Action(if (vm.isFavorite(current.id)) itemView.context.getString(R.string.ui_quitar_de_me_gusta_d81660) else itemView.context.getString(R.string.ui_me_gusta_f3b81e)) { vm.toggleFavorite(current) })
            if (!ready) add(NativeAccessibility.Action(if (currentProgress != null) itemView.context.getString(R.string.ui_cancelar_descarga_f42d93) else itemView.context.getString(R.string.ui_descargar_8b98cb)) {
                if (currentProgress != null) vm.cancelDownload(current.id) else vm.requestDownload(current)
            })
            add(NativeAccessibility.Action(itemView.context.getString(R.string.ui_iniciar_radio_ec118d)) { vm.startRadio(current) })
            if (onRemoveFromPlaylist != null) add(NativeAccessibility.Action(itemView.context.getString(R.string.ui_quitar_de_esta_playlist_d34f9e)) { confirmPlaylistRemoval(current) })
            add(NativeAccessibility.Action(itemView.context.getString(R.string.ui_mas_opciones_977c75)) { showActionsMenu(actions) })
        }
        NativeAccessibility.describe(itemView, "${current.title}, ${current.artistName}, ${current.albumTitle}", state,
            if (playableNow) itemView.context.getString(R.string.ui_reproducir_cancion_e47bd3) else null, actions = custom, decorative = listOf(image, title, subtitle, progress))
        NativeAccessibility.button(actions, "Más opciones para ${current.title}")
        play.contentDescription = "$playbackLabel ${current.title}"
    }

    fun recycle() {
        progressJob?.cancel()
        progressJob = null
        progressFlow = null
        currentProgress = null
    }

    private companion object {
        const val MENU_QUEUE = 1
        const val MENU_FAVORITE = 2
        const val MENU_ALBUM = 3
        const val MENU_DELETE = 4
        const val MENU_REMOVE_FROM_PLAYLIST = 5
        const val MENU_DOWNLOAD = 6
        const val MENU_CANCEL_DOWNLOAD = 7
        const val MENU_RADIO = 8
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
        paint.color = if (isPressed) Color.rgb(241,234,217) else Color.rgb(241,234,217); paint.style = Paint.Style.FILL
        if (mode == Mode.PLAY) {
            path.reset(); path.moveTo(cx - size * .32f, cy - size * .55f); path.lineTo(cx + size * .55f, cy); path.lineTo(cx - size * .32f, cy + size * .55f); path.close(); canvas.drawPath(path, paint)
        } else {
            canvas.drawRoundRect(cx-size*.45f, cy-size*.58f, cx-size*.08f, cy+size*.58f, size*.08f, size*.08f, paint)
            canvas.drawRoundRect(cx+size*.08f, cy-size*.58f, cx+size*.45f, cy+size*.58f, size*.08f, size*.08f, paint)
        }
    }
}

private class NativeMoreButton(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(241, 234, 217) }
    init { isClickable = true; isFocusable = true; background = null }
    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) * .06f).coerceAtLeast(2f)
        val gap = radius * 3.2f
        canvas.drawCircle(cx, cy - gap, radius, paint)
        canvas.drawCircle(cx, cy, radius, paint)
        canvas.drawCircle(cx, cy + gap, radius, paint)
    }
}

private fun installTrackPressFeedback(view: View) {
    view.isClickable = true
    view.setOnTouchListener { target, event ->
        if (!target.isEnabled) return@setOnTouchListener false
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> target.animate()
                .scaleX(0.985f).scaleY(0.985f).alpha(0.84f).setDuration(65L).start()
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> target.animate()
                .scaleX(1f).scaleY(1f).alpha(1f).setDuration(110L).start()
        }
        false
    }
}

private fun makeTrackRow(context: Context, hybrid: Boolean): LinearLayout {
    fun dp(v: Int) = (v * context.resources.displayMetrics.density).roundToInt()
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(if (hybrid) 10 else 4), dp(if (hybrid) 8 else 4), dp(if (hybrid) 10 else 4), dp(if (hybrid) 8 else 4))
        minimumHeight = dp(if (hybrid) 74 else 68)
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (hybrid) bottomMargin = dp(6)
        }
        if (hybrid) background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.rgb(30, 27, 24))
            setStroke(dp(1), Color.rgb(58, 52, 44))
        }
    }
    val image = ImageView(context).apply {
        tag = "cover"; layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)); scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(30,27,24)); clipToOutline = true
    }
    row.addView(image)
    val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) } }
    text.addView(TextView(context).apply { tag="title"; setTextColor(Color.rgb(241,234,217)); textSize=15f; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END; setTypeface(typeface, android.graphics.Typeface.BOLD) })
    text.addView(TextView(context).apply { tag="subtitle"; setTextColor(Color.rgb(179,169,146)); textSize=12f; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
    text.addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { tag="progress"; visibility=View.GONE; max=100; layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin=dp(3) } })
    row.addView(text)
    row.addView(NativePlayButton(context).apply { tag="play"; layoutParams=LinearLayout.LayoutParams(dp(48), dp(48)) })
    row.addView(NativeMoreButton(context).apply {
        tag="actions"; layoutParams=LinearLayout.LayoutParams(dp(48), dp(48))
    })
    return row
}


/** Optimized local album detail: one native header + native track rows with DiffUtil. */
internal class LocalAlbumDetailAdapter(private val vm: MusicVaultViewModel, private val hybridDesign: Boolean = false) :
    ListAdapter<Track, RecyclerView.ViewHolder>(LocalAlbumTrackDiff) {

    companion object {
        const val TYPE_HEADER = 30
        const val TYPE_TRACK = 31
        const val STATE_PAYLOAD = "player_state"
    }

    private var album: Album? = null
    private var playlists: List<Playlist> = emptyList()
    private var albums: List<Album> = emptyList()
    private var tracksCache: List<Track> = emptyList()
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
        if (viewType == TYPE_HEADER) LocalAlbumHeaderHolder(parent.context, hybridDesign)
        else NativeTrackHolder(parent.context, vm, hybridDesign)

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
        // This update runs on every recomposition of the hosting screen. Skip
        // the whole rebuild (and DiffUtil) when nothing the rows render changed.
        val headerChanged = this.album != album || this.onBack !== onBack
        val tracksChanged = tracks !== tracksCache && tracks != tracksCache
        val selectionChanged = this.selectedTrackId != selectedTrackId || this.isPlaying != isPlaying
        val auxiliaryChanged = this.playlists != playlists || this.albums != albums
        val oldSelected = this.selectedTrackId
        val oldPlaying = this.isPlaying
        if (!headerChanged && !tracksChanged && !selectionChanged && !auxiliaryChanged) return

        this.album = album
        this.playlists = playlists
        this.albums = albums
        this.selectedTrackId = selectedTrackId
        this.isPlaying = isPlaying
        this.onBack = onBack

        if (tracksChanged) {
            tracksCache = tracks
            submitList(tracks.toList()) {
                if (headerChanged && itemCount > 0) notifyItemChanged(0)
                if (selectionChanged) {
                    if (oldSelected != null) currentList.indexOfFirst { it.id == oldSelected }.takeIf { it >= 0 }?.let { notifyItemChanged(it + 1, STATE_PAYLOAD) }
                    if (selectedTrackId != null && selectedTrackId != oldSelected) currentList.indexOfFirst { it.id == selectedTrackId }.takeIf { it >= 0 }?.let { notifyItemChanged(it + 1, STATE_PAYLOAD) }
                }
            }
        } else {
            // Same row set: refresh only the header and the affected rows
            // instead of running DiffUtil over the whole album again.
            if (headerChanged && itemCount > 0) notifyItemChanged(0)
            if (selectionChanged) {
                if (oldSelected != null) currentList.indexOfFirst { it.id == oldSelected }.takeIf { it >= 0 }?.let { notifyItemChanged(it + 1, STATE_PAYLOAD) }
                if (selectedTrackId != null && selectedTrackId != oldSelected) currentList.indexOfFirst { it.id == selectedTrackId }.takeIf { it >= 0 }?.let { notifyItemChanged(it + 1, STATE_PAYLOAD) }
            }
        }
    }

}

/** Optimized playlist detail: Compose header + the same native track-row
 *  pipeline the Library uses (DiffUtil, per-row progress flows, native menus),
 *  so big playlists scroll with the same per-row cost as the Library. */
internal class PlaylistDetailAdapter(
    private val vm: MusicVaultViewModel,
    private val hybridDesign: Boolean = false,
    private val allowRemoval: Boolean = true
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_HEADER = 40
        const val TYPE_TRACK = 41
        const val STATE_PAYLOAD = "player_state"
    }

    private var headerContent: (@Composable () -> Unit)? = null
    private var rowClick: ((Track) -> Unit)? = null
    private var playNew: ((Track) -> Unit)? = null
    private var removeFromPlaylist: ((Track) -> Unit)? = null
    private var playlists: List<Playlist> = emptyList()
    private var albums: List<Album> = emptyList()
    private var tracksCache: List<Track> = emptyList()
    private var selectedTrackId: String? = null
    private var isPlaying: Boolean = false
    private var isOnline: Boolean = true
    private var offlinePlayableIds: Set<String> = emptySet()
    private val progressFlows = HashMap<String, Flow<Int?>>()
    // Track rows live after the header at adapter position 0. ListAdapter's
    // default update callback cannot account for that offset: when Room first
    // emits a populated playlist it reports an insertion at position 0, which
    // makes RecyclerView preserve the old header anchor at the end of the list.
    private val differ = AsyncListDiffer(
        object : ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) = notifyItemRangeInserted(position + 1, count)
            override fun onRemoved(position: Int, count: Int) = notifyItemRangeRemoved(position + 1, count)
            override fun onMoved(fromPosition: Int, toPosition: Int) =
                notifyItemMoved(fromPosition + 1, toPosition + 1)
            override fun onChanged(position: Int, count: Int, payload: Any?) =
                notifyItemRangeChanged(position + 1, count, payload)
        },
        AsyncDifferConfig.Builder(LocalAlbumTrackDiff).build()
    )
    private val currentList: List<Track> get() = differ.currentList

    init { setHasStableIds(true) }

    override fun getItemCount(): Int = currentList.size + 1

    private fun getItem(position: Int): Track = currentList[position]

    override fun getItemId(position: Int): Long =
        if (position == 0) Long.MIN_VALUE + 40 else stableLongId("playlist-detail:${getItem(position - 1).id}")

    override fun getItemViewType(position: Int): Int = if (position == 0) TYPE_HEADER else TYPE_TRACK

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER) ComposeRowHolder(parent)
        else NativeTrackHolder(
            parent.context,
            vm,
            hybridDesign,
            onRowClick = { track -> rowClick?.invoke(track) },
            onPlayNew = { track -> playNew?.invoke(track) },
            onRemoveFromPlaylist = if (allowRemoval) ({ track -> removeFromPlaylist?.invoke(track) }) else null,
            allowMissingTrackLongPressRemoval = allowRemoval
        )

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position == 0) {
            headerContent?.let { content -> (holder.itemView as ComposeView).setContent(content) }
        } else {
            val track = getItem(position - 1)
            (holder as NativeTrackHolder).bind(
                track,
                progressFlow(track.id),
                playlists,
                albums,
                selectedTrackId,
                isPlaying,
                isOnline,
                offlinePlayableIds
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
        tracks: List<Track>,
        playlists: List<Playlist>,
        albums: List<Album>,
        selectedTrackId: String?,
        isPlaying: Boolean,
        isOnline: Boolean,
        offlinePlayableIds: Set<String>,
        onRowClick: (Track) -> Unit,
        onPlayNew: (Track) -> Unit,
        onRemoveFromPlaylist: (Track) -> Unit,
        header: @Composable () -> Unit,
        onListCommitted: (() -> Unit)? = null
    ) {
        // This update runs on every recomposition of the hosting screen. Skip
        // the whole rebuild (and DiffUtil) when nothing the rows render changed.
        val headerChanged = header !== headerContent
        val tracksChanged = tracks !== tracksCache && tracks != tracksCache
        val selectionChanged = this.selectedTrackId != selectedTrackId || this.isPlaying != isPlaying
        val auxiliaryChanged = this.playlists != playlists || this.albums != albums
        val connectivityChanged = this.isOnline != isOnline
        val offlineAvailabilityChanged = this.offlinePlayableIds != offlinePlayableIds
        val oldSelected = this.selectedTrackId
        if (!headerChanged && !tracksChanged && !selectionChanged && !auxiliaryChanged &&
            !connectivityChanged && !offlineAvailabilityChanged) return

        this.headerContent = header
        this.rowClick = onRowClick
        this.playNew = onPlayNew
        this.removeFromPlaylist = onRemoveFromPlaylist
        this.playlists = playlists
        this.albums = albums
        this.selectedTrackId = selectedTrackId
        this.isPlaying = isPlaying
        this.isOnline = isOnline
        this.offlinePlayableIds = offlinePlayableIds

        if (tracksChanged) {
            tracksCache = tracks
            differ.submitList(tracks.toList()) {
                if (headerChanged && itemCount > 0) notifyItemChanged(0)
                if (selectionChanged) {
                    if (oldSelected != null) currentList.indexOfFirst { it.id == oldSelected }.takeIf { it >= 0 }?.let { notifyItemChanged(it + 1, STATE_PAYLOAD) }
                    if (selectedTrackId != null && selectedTrackId != oldSelected) currentList.indexOfFirst { it.id == selectedTrackId }.takeIf { it >= 0 }?.let { notifyItemChanged(it + 1, STATE_PAYLOAD) }
                }
                if ((connectivityChanged || offlineAvailabilityChanged) && currentList.isNotEmpty()) notifyItemRangeChanged(1, currentList.size)
                onListCommitted?.invoke()
            }
        } else {
            // Same row set: refresh only the header and the affected rows
            // instead of running DiffUtil over the whole playlist again.
            if (headerChanged && itemCount > 0) notifyItemChanged(0)
            if (selectionChanged) {
                if (oldSelected != null) currentList.indexOfFirst { it.id == oldSelected }.takeIf { it >= 0 }?.let { notifyItemChanged(it + 1, STATE_PAYLOAD) }
                if (selectedTrackId != null && selectedTrackId != oldSelected) currentList.indexOfFirst { it.id == selectedTrackId }.takeIf { it >= 0 }?.let { notifyItemChanged(it + 1, STATE_PAYLOAD) }
            }
            if ((connectivityChanged || offlineAvailabilityChanged) && currentList.isNotEmpty()) notifyItemRangeChanged(1, currentList.size)
        }
    }
}

private object LocalAlbumTrackDiff : DiffUtil.ItemCallback<Track>() {
    override fun areItemsTheSame(oldItem: Track, newItem: Track) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Track, newItem: Track) = oldItem == newItem
}

private class LocalAlbumHeaderHolder(context: Context, hybrid: Boolean) : RecyclerView.ViewHolder(makeLocalAlbumHeader(context, hybrid)) {
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
        NativeAccessibility.heading(title)
        cover.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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

private fun makeLocalAlbumHeader(context: Context, hybrid: Boolean): LinearLayout {
    fun dp(v: Int) = (v * context.resources.displayMetrics.density).roundToInt()
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(14))
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    root.addView(ImageButton(context).apply {
        tag = "back"
        setImageResource(R.drawable.ic_vault_back)
        setColorFilter(Color.rgb(241, 234, 217))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        val shape = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.rgb(30, 27, 24))
            setStroke(dp(1), Color.rgb(50, 44, 37))
        }
        background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(Color.argb(50, 224, 164, 88)), shape, null
        )
        contentDescription = context.getString(R.string.ui_atras_0e715a)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(12) }
    })
    val info = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    info.addView(ImageView(context).apply {
        tag = "cover"; layoutParams = LinearLayout.LayoutParams(dp(150), dp(150)); scaleType = ImageView.ScaleType.CENTER_CROP
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(if (hybrid) 18 else 12).toFloat(); setColor(Color.rgb(30,27,24)) }
    })
    val text = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) }
    }
    text.addView(TextView(context).apply { tag="title"; setTextColor(Color.rgb(241,234,217)); textSize=24f; setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END })
    text.addView(TextView(context).apply { tag="artist"; setTextColor(Color.rgb(179,169,146)); textSize=14f; maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END; layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin=dp(4) } })
    text.addView(TextView(context).apply { tag="year"; setTextColor(Color.rgb(126,118,102)); textSize=12f; layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin=dp(2) } })
    info.addView(text)
    root.addView(info)
    return root
}

private class HeartDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(241,234,217); style = Paint.Style.FILL }
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

internal class AlbumFeedAdapter(private val vm: MusicVaultViewModel, private val hybridDesign: Boolean = false) :
    ListAdapter<Playlist, RecyclerView.ViewHolder>(AlbumDiff) {

    companion object {
        const val TYPE_HEADER = 20
        const val TYPE_ALBUM = 21
    }

    var onAddAlbum: (() -> Unit)? = null
    var onDeleteAlbum: ((Playlist) -> Unit)? = null

    init { setHasStableIds(true) }

    override fun getItemCount(): Int = super.getItemCount() + 1

    override fun getItemId(position: Int): Long =
        if (position == 0) Long.MIN_VALUE else stableLongId("album:${getItem(position - 1).id}")

    override fun getItemViewType(position: Int): Int =
        if (position == 0) TYPE_HEADER else TYPE_ALBUM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER) AlbumHeaderHolder(parent.context, hybridDesign)
        else AlbumCardHolder(parent.context, vm, hybridDesign)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position == 0) (holder as AlbumHeaderHolder).bind(onAddAlbum)
        else (holder as AlbumCardHolder).bind(getItem(position - 1), onDeleteAlbum)
    }

    fun update(items: List<Playlist>) {
        if (currentList == items) return
        submitList(items.toList())
    }
}

private object AlbumDiff : DiffUtil.ItemCallback<Playlist>() {
    override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist) = oldItem == newItem
}

private class AlbumHeaderHolder(context: Context, hybrid: Boolean) : RecyclerView.ViewHolder(makeAlbumHeader(context, hybrid)) {
    private val add = itemView.findViewWithTag<ImageButton>("addAlbum")
    private var callback: (() -> Unit)? = null
    init { add.setOnClickListener { callback?.invoke() } }
    fun bind(onAdd: (() -> Unit)?) { callback = onAdd }
}

private fun makeAlbumHeader(context: Context, hybrid: Boolean): LinearLayout {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).roundToInt()
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(18))
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
    text.addView(TextView(context).apply { this.text = ""; setTextColor(Color.rgb(241,234,217)); textSize = if (hybrid) 24f else 28f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
    root.addView(text)
    root.addView(ImageButton(context).apply {
        tag = "addAlbum"; background = null; setImageResource(android.R.drawable.ic_input_add); setColorFilter(Color.rgb(241,234,217));
        contentDescription = context.getString(R.string.ui_nuevo_album_029960); layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    })
    return root
}

private class AlbumCardHolder(context: Context, private val vm: MusicVaultViewModel, hybrid: Boolean) : RecyclerView.ViewHolder(makeAlbumCard(context, hybrid)) {
    private val cover = itemView.findViewWithTag<ImageView>("albumCover")
    private val title = itemView.findViewWithTag<TextView>("albumTitle")
    private val description = itemView.findViewWithTag<TextView>("albumDescription")
    private val more = itemView.findViewWithTag<ImageButton>("albumMore")
    private var playlist: Playlist? = null
    private var onDelete: ((Playlist) -> Unit)? = null
    init {
        itemView.setOnClickListener { playlist?.let(vm::openPlaylist) }
        more.setOnClickListener {
            val current = playlist ?: return@setOnClickListener
            if (current.id != "playlist:favorites") {
                android.widget.PopupMenu(itemView.context, more).apply {
                    menu.add("Eliminar playlist").setOnMenuItemClickListener {
                        onDelete?.invoke(current)
                        true
                    }
                    show()
                }
            }
        }
    }
    fun bind(value: Playlist, onDelete: ((Playlist) -> Unit)?) {
        playlist = value
        this.onDelete = onDelete
        title.text = value.title
        description.text = value.description
        NativeAccessibility.describe(itemView, "${value.title}, ${value.description}", clickLabel = itemView.context.getString(R.string.ui_abrir_playlist_b7a8ce),
            decorative = listOf(cover, title, description))
        NativeAccessibility.button(more, "Opciones de ${value.title}")
        more.visibility = if (value.id == "playlist:favorites") View.GONE else View.VISIBLE
        if (value.id == "playlist:favorites") {
            cover.setImageDrawable(HeartDrawable())
            cover.setColorFilter(null)
            cover.scaleType = ImageView.ScaleType.CENTER
        } else if (!value.coverUri.isNullOrBlank()) {
            cover.setColorFilter(null)
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            loadCover(itemView.context, cover, value.coverUri, 184)
        } else {
            cover.setColorFilter(null)
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }
}

private fun makeAlbumCard(context: Context, hybrid: Boolean): LinearLayout {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).roundToInt()
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(if (hybrid) 8 else 4), dp(if (hybrid) 8 else 4), dp(if (hybrid) 8 else 4), dp(if (hybrid) 8 else 4))
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(if (hybrid) 16 else 12).toFloat()
            setColor(if (hybrid) Color.rgb(37,33,28) else Color.rgb(30,27,24))
            if (hybrid) setStroke(dp(1), Color.rgb(58,52,44))
        }
    }
    root.addView(ImageView(context).apply { tag="albumCover"; layoutParams=LinearLayout.LayoutParams(dp(60), dp(60)); scaleType=ImageView.ScaleType.CENTER_INSIDE })
    val text=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL; layoutParams=LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart=dp(14); marginEnd=dp(12) } }
    text.addView(TextView(context).apply { tag="albumTitle"; setTextColor(Color.rgb(241,234,217)); textSize=15f; setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
    text.addView(TextView(context).apply { tag="albumDescription"; setTextColor(Color.rgb(179,169,146)); textSize=12f; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
    root.addView(text)
    root.addView(ImageButton(context).apply {
        tag = "albumMore"
        background = null
        setImageResource(R.drawable.ic_vault_more)
        setColorFilter(Color.WHITE)
        contentDescription = context.getString(R.string.ui_eliminar_playlist_12e613)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        isFocusable = true
    })
    return root
}

private fun loadCover(context: Context, image: ImageView, url: String?, sizePx: Int) {
    if (url.isNullOrBlank()) {
        image.dispose()
        image.setImageDrawable(null)
        image.setBackgroundColor(Color.rgb(30,27,24))
        return
    }
    val request = ImageRequest.Builder(context).data(url).size(Size(sizePx, sizePx)).memoryCacheKey("musicvault:$url:$sizePx").diskCacheKey(url).crossfade(false).target(image).build()
    CoverImageCache.loader(context).enqueue(request)
}


/**
 * Native picker used by the create-playlist dialog, with heights that accommodate large fonts.
 *
 * The old Compose scrolling list was convenient but each fast fling could compose
 * image/checkbox rows while Coil was also starting cover requests. RecyclerView
 * recycles a tiny pool of native rows instead, which keeps the modal smooth even
 * with hundreds or thousands of downloaded tracks. Selection lives in the
 * adapter so filtering never loses checked songs.
 */
internal class PlaylistTrackPickerAdapter(
    private val onSelectionCountChanged: (Int) -> Unit
) : ListAdapter<Track, PlaylistTrackPickerAdapter.PickerHolder>(PickerTrackDiff) {

    private val selectedIds = LinkedHashSet<String>()

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = stableLongId("playlist-picker:${getItem(position).id}")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PickerHolder =
        PickerHolder(makePlaylistPickerRow(parent.context)) { track -> toggle(track) }

    override fun onBindViewHolder(holder: PickerHolder, position: Int) {
        val track = getItem(position)
        holder.bind(track, track.id in selectedIds)
    }

    override fun onBindViewHolder(holder: PickerHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val track = getItem(position)
            holder.bindChecked(track.id in selectedIds)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    fun update(items: List<Track>) {
        if (currentList == items) return
        submitList(items.toList())
    }

    fun selectedIds(): Set<String> = selectedIds.toSet()

    fun selectAll(items: List<Track>) {
        val before = selectedIds.size
        items.forEach { selectedIds.add(it.id) }
        if (selectedIds.size != before) {
            onSelectionCountChanged(selectedIds.size)
            notifyItemRangeChanged(0, itemCount, PAYLOAD_CHECK)
        }
    }

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        onSelectionCountChanged(0)
        notifyItemRangeChanged(0, itemCount, PAYLOAD_CHECK)
    }

    private fun toggle(track: Track) {
        if (!selectedIds.add(track.id)) selectedIds.remove(track.id)
        onSelectionCountChanged(selectedIds.size)
        val pos = currentList.indexOfFirst { it.id == track.id }
        if (pos >= 0) notifyItemChanged(pos, PAYLOAD_CHECK)
    }

    internal class PickerHolder(
        itemView: View,
        onToggle: (Track) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val cover = itemView.findViewWithTag<ImageView>("pickerCover")
        private val title = itemView.findViewWithTag<TextView>("pickerTitle")
        private val artist = itemView.findViewWithTag<TextView>("pickerArtist")
        private val check = itemView.findViewWithTag<CheckBox>("pickerCheck")
        private var current: Track? = null

        init {
            itemView.setOnClickListener { current?.let(onToggle) }
            // Keep one click target for the whole row. This avoids duplicate
            // listener churn when RecyclerView rebinds a recycled checkbox.
            check.isClickable = false
            check.isFocusable = false
            check.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun bind(track: Track, checked: Boolean) {
            current = track
            title.text = track.title
            artist.text = track.artistName
            check.isChecked = checked
            bindChecked(checked)

            cover.dispose()
            cover.setColorFilter(null)
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            if (track.coverUri.isNullOrBlank()) {
                cover.setImageResource(android.R.drawable.ic_menu_gallery)
            } else {
                loadCover(itemView.context, cover, track.coverUri, 104)
            }
        }

        fun bindChecked(checked: Boolean) {
            check.isChecked = checked
            current?.let { NativeAccessibility.describe(itemView, "${it.title}, ${it.artistName}",
                if (checked) itemView.context.getString(R.string.ui_seleccionada_ec2675) else itemView.context.getString(R.string.ui_sin_seleccionar_31b887), if (checked) itemView.context.getString(R.string.ui_quitar_seleccion_ee7571) else itemView.context.getString(R.string.ui_seleccionar_2b8319), checked,
                decorative = listOf(cover, title, artist, check)) }
        }
    }

    private companion object {
        const val PAYLOAD_CHECK = "picker-check"
    }
}

private object PickerTrackDiff : DiffUtil.ItemCallback<Track>() {
    override fun areItemsTheSame(oldItem: Track, newItem: Track): Boolean = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Track, newItem: Track): Boolean = oldItem == newItem
}

private fun makePlaylistPickerRow(context: Context): LinearLayout {
    val d = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).roundToInt()

    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(10), dp(6), dp(8), dp(6))
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(10).toFloat()
        }

        addView(ImageView(context).apply {
            tag = "pickerCover"
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(37, 33, 28))
                cornerRadius = dp(9).toFloat()
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        })

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(11)
                marginEnd = dp(8)
            }
            addView(TextView(context).apply {
                tag = "pickerTitle"
                setTextColor(Color.rgb(241, 234, 217))
                textSize = 14f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(context).apply {
                tag = "pickerArtist"
                setTextColor(Color.rgb(179, 169, 146))
                textSize = 12f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        })

        addView(CheckBox(context).apply {
            tag = "pickerCheck"
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            gravity = Gravity.CENTER
            buttonTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(
                    Color.rgb(211, 178, 112),
                    Color.rgb(126, 117, 99)
                )
            )
        })
    }
}

/** Native picker for Spotify import. Keeps large imported playlists smooth while scrolling. */
internal class SpotifyImportTrackPickerAdapter(
    initialSelection: Set<String>,
    private val onSelectionChanged: (Set<String>) -> Unit
) : ListAdapter<com.musicvault.app.spotify.SpotifyTrack, SpotifyImportTrackPickerAdapter.Holder>(SpotifyImportTrackDiff) {

    private val selectedIds = LinkedHashSet<String>().apply { addAll(initialSelection) }

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = stableLongId("spotify-picker:${getItem(position).id}")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(makePlaylistPickerRow(parent.context)) { track -> toggle(track) }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val track = getItem(position)
        holder.bind(track, track.id in selectedIds)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) holder.bindChecked(getItem(position).id in selectedIds)
        else onBindViewHolder(holder, position)
    }

    fun update(items: List<com.musicvault.app.spotify.SpotifyTrack>) {
        if (currentList == items) return
        submitList(items.toList())
    }

    fun selectAll(items: List<com.musicvault.app.spotify.SpotifyTrack>) {
        items.forEach { selectedIds.add(it.id) }
        onSelectionChanged(selectedIds.toSet())
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_CHECK)
    }

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        onSelectionChanged(emptySet())
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_CHECK)
    }

    private fun toggle(track: com.musicvault.app.spotify.SpotifyTrack) {
        if (!selectedIds.add(track.id)) selectedIds.remove(track.id)
        onSelectionChanged(selectedIds.toSet())
        val pos = currentList.indexOfFirst { it.id == track.id }
        if (pos >= 0) notifyItemChanged(pos, PAYLOAD_CHECK)
    }

    internal class Holder(itemView: View, onToggle: (com.musicvault.app.spotify.SpotifyTrack) -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val cover = itemView.findViewWithTag<ImageView>("pickerCover")
        private val title = itemView.findViewWithTag<TextView>("pickerTitle")
        private val artist = itemView.findViewWithTag<TextView>("pickerArtist")
        private val check = itemView.findViewWithTag<CheckBox>("pickerCheck")
        private var current: com.musicvault.app.spotify.SpotifyTrack? = null

        init {
            itemView.setOnClickListener { current?.let(onToggle) }
            check.isClickable = false
            check.isFocusable = false
            check.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun bind(track: com.musicvault.app.spotify.SpotifyTrack, checked: Boolean) {
            current = track
            title.text = track.title
            artist.text = track.artist
            check.isChecked = checked
            bindChecked(checked)
            cover.dispose()
            cover.setColorFilter(null)
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            if (track.coverUrl.isNullOrBlank()) cover.setImageResource(android.R.drawable.ic_menu_gallery)
            else loadCover(itemView.context, cover, track.coverUrl, 104)
        }

        fun bindChecked(checked: Boolean) {
            check.isChecked = checked
            current?.let { NativeAccessibility.describe(itemView, "${it.title}, ${it.artist}",
                if (checked) itemView.context.getString(R.string.ui_seleccionada_ec2675) else itemView.context.getString(R.string.ui_sin_seleccionar_31b887), if (checked) itemView.context.getString(R.string.ui_quitar_seleccion_ee7571) else itemView.context.getString(R.string.ui_seleccionar_2b8319), checked,
                decorative = listOf(cover, title, artist, check)) }
        }
    }

    private companion object { const val PAYLOAD_CHECK = "spotify-picker-check" }
}

private object SpotifyImportTrackDiff : DiffUtil.ItemCallback<com.musicvault.app.spotify.SpotifyTrack>() {
    override fun areItemsTheSame(oldItem: com.musicvault.app.spotify.SpotifyTrack, newItem: com.musicvault.app.spotify.SpotifyTrack): Boolean = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: com.musicvault.app.spotify.SpotifyTrack, newItem: com.musicvault.app.spotify.SpotifyTrack): Boolean = oldItem == newItem
}

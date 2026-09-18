package com.musicvault.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.request.ImageRequest
import coil.size.Size
import com.musicvault.app.catalog.CoverImageCache
import com.musicvault.app.data.Track
import com.musicvault.app.lyrics.LyricLine
import kotlin.math.abs
import kotlin.math.roundToInt

/** RecyclerView-backed synced lyrics so long songs never build a large Compose list. */
internal class SyncedLyricsAdapter : ListAdapter<LyricLine, SyncedLyricsAdapter.Holder>(LyricsDiff) {
    private var activeIndex: Int? = null

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = utilityStableId("lyric:${getItem(position).timeMs}:${getItem(position).text}")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(makeLyricRow(parent.context))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position), position, activeIndex)

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) holder.bindStyle(position, activeIndex)
        else onBindViewHolder(holder, position)
    }

    fun update(lines: List<LyricLine>, active: Int?) {
        if (currentList != lines) {
            submitList(lines.toList()) {
                val old = activeIndex
                activeIndex = active
                if (itemCount > 0 && old != active) notifyItemRangeChanged(0, itemCount, PAYLOAD_ACTIVE)
            }
            return
        }
        if (activeIndex != active) {
            activeIndex = active
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_ACTIVE)
        }
    }

    internal class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text = itemView as TextView
        private var boundPosition = -1

        fun bind(line: LyricLine, position: Int, active: Int?) {
            boundPosition = position
            text.text = line.text
            bindStyle(position, active)
        }

        fun bindStyle(position: Int, active: Int?) {
            boundPosition = position
            val distance = active?.let { abs(position - it) } ?: 4
            val isActive = active == position
            text.setTextColor(if (isActive) Color.rgb(241, 234, 217) else Color.rgb(179, 169, 146))
            text.textSize = when {
                isActive -> 29f
                distance <= 1 -> 24f
                distance <= 3 -> 21f
                else -> 18f
            }
            text.alpha = when {
                isActive -> 1f
                distance == 1 -> .72f
                distance <= 3 -> .48f
                else -> .26f
            }
            val scale = when {
                isActive -> 1f
                distance == 1 -> .97f
                distance <= 3 -> .94f
                else -> .91f
            }
            text.scaleX = scale
            text.scaleY = scale
            text.setTypeface(text.typeface, if (isActive) Typeface.BOLD else Typeface.NORMAL)
            if (Build.VERSION.SDK_INT >= 31) {
                val density = text.resources.displayMetrics.density
                val blurDp = when {
                    isActive -> 0f
                    distance == 1 -> .55f
                    distance == 2 -> 1.0f
                    distance == 3 -> 1.5f
                    else -> 2.0f
                }
                text.setRenderEffect(
                    if (blurDp <= 0f) null
                    else RenderEffect.createBlurEffect(blurDp * density, blurDp * density, Shader.TileMode.CLAMP)
                )
            }
        }
    }

    private companion object { const val PAYLOAD_ACTIVE = "lyrics-active" }
}

private object LyricsDiff : DiffUtil.ItemCallback<LyricLine>() {
    override fun areItemsTheSame(oldItem: LyricLine, newItem: LyricLine): Boolean = oldItem.timeMs == newItem.timeMs && oldItem.text == newItem.text
    override fun areContentsTheSame(oldItem: LyricLine, newItem: LyricLine): Boolean = oldItem == newItem
}

private fun makeLyricRow(context: Context): TextView = TextView(context).apply {
    gravity = Gravity.CENTER
    setTextColor(Color.rgb(179, 169, 146))
    textSize = 21f
    setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
    minHeight = dp(context, 58)
    maxLines = 4
    textAlignment = View.TEXT_ALIGNMENT_CENTER
    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

/** Native queue list; avoids composing every queue row in the bottom sheet. */
internal class PlaybackQueueAdapter(
    private val onMove: (Int, Int) -> Unit,
    private val onRemove: (Int) -> Unit
) : ListAdapter<QueueEntry, PlaybackQueueAdapter.Holder>(QueueDiff) {
    private var currentIndex = -1

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = utilityStableId(getItem(position).key)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(makeQueueRow(parent.context), onMove, onRemove)

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position), currentIndex, itemCount - 1)

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) holder.bindState(getItem(position).index == currentIndex)
        else onBindViewHolder(holder, position)
    }

    fun update(queue: List<Track>, current: Int) {
        val rows = queue.mapIndexed { index, track -> QueueEntry("queue:${track.id}:$index", index, track) }
        val oldCurrent = currentIndex
        currentIndex = current
        submitList(rows) {
            if (oldCurrent != current && itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE)
        }
    }

    internal class Holder(
        itemView: View,
        private val onMove: (Int, Int) -> Unit,
        private val onRemove: (Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val cover = itemView.findViewWithTag<ImageView>("queueCover")
        private val title = itemView.findViewWithTag<TextView>("queueTitle")
        private val artist = itemView.findViewWithTag<TextView>("queueArtist")
        private val up = itemView.findViewWithTag<TextView>("queueUp")
        private val down = itemView.findViewWithTag<TextView>("queueDown")
        private val delete = itemView.findViewWithTag<ImageButton>("queueDelete")
        private var row: QueueEntry? = null

        init {
            up.setOnClickListener { row?.let { if (it.index > 0) onMove(it.index, it.index - 1) } }
            down.setOnClickListener { row?.let { onMove(it.index, it.index + 1) } }
            delete.setOnClickListener { row?.let { onRemove(it.index) } }
        }

        fun bind(value: QueueEntry, currentIndex: Int, lastIndex: Int) {
            row = value
            title.text = value.track.title
            artist.text = value.track.artistName
            up.isEnabled = value.index > 0
            down.isEnabled = value.index < lastIndex
            loadUtilityCover(itemView.context, cover, value.track.coverUri, 88)
            bindState(value.index == currentIndex)
        }

        fun bindState(active: Boolean) {
            title.setTextColor(if (active) Color.rgb(224, 164, 88) else Color.rgb(241, 234, 217))
        }
    }

    private companion object { const val PAYLOAD_STATE = "queue-state" }
}

internal data class QueueEntry(val key: String, val index: Int, val track: Track)

private object QueueDiff : DiffUtil.ItemCallback<QueueEntry>() {
    override fun areItemsTheSame(oldItem: QueueEntry, newItem: QueueEntry): Boolean = oldItem.key == newItem.key
    override fun areContentsTheSame(oldItem: QueueEntry, newItem: QueueEntry): Boolean = oldItem == newItem
}

private fun makeQueueRow(context: Context): LinearLayout {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 2), dp(context, 6), dp(context, 2), dp(context, 6))
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 64))
    }
    row.addView(TextView(context).apply {
        text = "≡"
        textSize = 22f
        setTextColor(Color.rgb(126, 117, 99))
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(context, 30), dp(context, 48))
    })
    row.addView(ImageView(context).apply {
        tag = "queueCover"
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = LinearLayout.LayoutParams(dp(context, 44), dp(context, 44))
        setBackgroundColor(Color.rgb(30, 27, 24))
    })
    row.addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(context, 10) }
        addView(TextView(context).apply {
            tag = "queueTitle"; textSize = 14f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(context).apply {
            tag = "queueArtist"; setTextColor(Color.rgb(179, 169, 146)); textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
    })
    fun arrow(tagName: String, glyph: String) = TextView(context).apply {
        tag = tagName; text = glyph; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.rgb(241, 234, 217)); layoutParams = LinearLayout.LayoutParams(dp(context, 38), dp(context, 48))
    }
    row.addView(arrow("queueUp", "↑"))
    row.addView(arrow("queueDown", "↓"))
    row.addView(ImageButton(context).apply {
        tag = "queueDelete"
        setImageResource(android.R.drawable.ic_menu_delete)
        setColorFilter(Color.WHITE)
        background = null
        contentDescription = "Quitar"
        layoutParams = LinearLayout.LayoutParams(dp(context, 42), dp(context, 48))
    })
    return row
}

private fun loadUtilityCover(context: Context, image: ImageView, url: String?, sizePx: Int) {
    image.dispose()
    if (url.isNullOrBlank()) {
        image.setImageResource(android.R.drawable.ic_menu_gallery)
        return
    }
    CoverImageCache.loader(context).enqueue(
        ImageRequest.Builder(context)
            .data(url)
            .size(Size(sizePx, sizePx))
            .memoryCacheKey("musicvault:$url:$sizePx")
            .diskCacheKey(url)
            .crossfade(false)
            .target(image)
            .build()
    )
}

private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

private fun utilityStableId(value: String): Long = value.hashCode().toLong() shl 32 xor value.reversed().hashCode().toLong()

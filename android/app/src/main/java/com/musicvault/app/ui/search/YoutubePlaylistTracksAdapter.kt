package com.musicvault.app.ui.search

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.request.ImageRequest
import coil.size.Size
import com.musicvault.app.catalog.CatalogTrack
import com.musicvault.app.catalog.CoverImageCache

/** Lightweight native list for remote YouTube playlist previews. */
class YoutubePlaylistTracksAdapter(
    private val onClick: (CatalogTrack) -> Unit
) : ListAdapter<CatalogTrack, YoutubePlaylistTracksAdapter.Holder>(DIFF) {

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(parent, onClick)

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position), position)

    class Holder(parent: ViewGroup, private val onClick: (CatalogTrack) -> Unit) :
        RecyclerView.ViewHolder(makeRow(parent)) {
        private val cover = (itemView as ViewGroup).getChildAt(0) as ImageView
        private val texts = (itemView as ViewGroup).getChildAt(1) as LinearLayout
        private val title = texts.getChildAt(0) as TextView
        private val subtitle = texts.getChildAt(1) as TextView
        private var current: CatalogTrack? = null

        init {
            itemView.setOnClickListener { current?.let(onClick) }
            itemView.isClickable = true
            itemView.isFocusable = true
        }

        fun bind(track: CatalogTrack, position: Int) {
            current = track
            title.text = "${position + 1}. ${track.title}"
            subtitle.text = buildString {
                append(track.artistName.ifBlank { "YouTube" })
                if (track.durationMs > 0) append(" · ${formatDuration(track.durationMs)}")
            }
            if (track.coverUri.isNullOrBlank()) {
                cover.setImageDrawable(null)
            } else {
                val request = ImageRequest.Builder(itemView.context)
                    .data(track.coverUri)
                    .size(Size(112, 112))
                    .target(cover)
                    .crossfade(false)
                    .build()
                CoverImageCache.loader(itemView.context).enqueue(request)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CatalogTrack>() {
            override fun areItemsTheSame(oldItem: CatalogTrack, newItem: CatalogTrack) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: CatalogTrack, newItem: CatalogTrack) = oldItem == newItem
        }

        private fun makeRow(parent: ViewGroup): LinearLayout {
            val c = parent.context
            val d = c.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            return LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(5), dp(4), dp(5))
                minimumHeight = dp(64)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(ImageView(c).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(7).toFloat()
                        setColor(Color.rgb(30, 27, 24))
                    }
                    clipToOutline = true
                })
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(12)
                    }
                    addView(TextView(c).apply {
                        setTextColor(Color.rgb(241, 234, 217))
                        textSize = 14f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(c).apply {
                        setTextColor(Color.rgb(166, 157, 143))
                        textSize = 12f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                })
            }
        }

        private fun formatDuration(ms: Long): String {
            val total = ms / 1000
            return "%d:%02d".format(total / 60, total % 60)
        }
    }
}

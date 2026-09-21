package ilab.iptv.player.feature.channels

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ilab.iptv.player.core.domain.channel.ChannelNumberSource

/**
 * Virtualized browse list (docs/02 §8.2: "列表虚拟化依赖 RecyclerView（658+ 台必须虚拟化）").
 *
 * Rows are flat ([ChannelListRow]) and carry a `key`, so the adapter can also expose stable ids —
 * which is what keeps focus on the same channel across a re-list (`onSaveInstanceState` /
 * player-return focus restore in docs/02 §8.1 depends on it later).
 *
 * Focus feedback follows docs/02 §8.2's "缩放 + 描边 + 高亮" triple: the row's background selector
 * owns the stroke and the highlight, the holder owns the scale animation.
 */
class ChannelListAdapter(
    private val onChannelFocused: (ChannelListRow.ChannelItem) -> Unit = {},
    private val onChannelSelected: (ChannelListRow.ChannelItem) -> Unit = {},
) : ListAdapter<ChannelListRow, RecyclerView.ViewHolder>(Diff) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).key.hashCode().toLong()

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ChannelListRow.GroupHeader -> TYPE_HEADER
        is ChannelListRow.ChannelItem -> TYPE_CHANNEL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_channel_group_header, parent, false))
        } else {
            ChannelHolder(
                inflater.inflate(R.layout.item_channel, parent, false),
                onChannelFocused,
                onChannelSelected,
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is ChannelListRow.GroupHeader -> (holder as HeaderHolder).bind(row)
            is ChannelListRow.ChannelItem -> (holder as ChannelHolder).bind(row)
        }
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.group_title)
        private val count: TextView = view.findViewById(R.id.group_count)

        fun bind(row: ChannelListRow.GroupHeader) {
            title.text = row.title
            count.text = row.count.toString()
            // A header is a label, not a stop on the remote's path: focus goes straight to channels.
            itemView.isFocusable = false
        }
    }

    private class ChannelHolder(
        view: View,
        private val onFocused: (ChannelListRow.ChannelItem) -> Unit,
        private val onSelected: (ChannelListRow.ChannelItem) -> Unit,
    ) : RecyclerView.ViewHolder(view) {

        private val number: TextView = view.findViewById(R.id.channel_number)
        private val logo: TextView = view.findViewById(R.id.channel_logo)
        private val name: TextView = view.findViewById(R.id.channel_name)
        private val meta: TextView = view.findViewById(R.id.channel_meta)
        private val origin: TextView = view.findViewById(R.id.channel_number_source)
        private var row: ChannelListRow.ChannelItem? = null

        init {
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true
            // docs/02 §8.1/§8.2: OK/Enter on a focused row opens playback. A focused, clickable view
            // receives KEYCODE_DPAD_CENTER/ENTER as a click, so this is the whole remote path — no
            // key listener of our own, and the same code runs for a tap on a phone.
            itemView.setOnClickListener { row?.let(onSelected) }
            // docs/02 §8.2: the default highlight is disabled in XML; this is the "highlight + scale"
            // half (the selector background is the other half).
            itemView.setOnFocusChangeListener { focusedView, hasFocus ->
                focusedView.animate()
                    .scaleX(if (hasFocus) FOCUS_SCALE else 1f)
                    .scaleY(if (hasFocus) FOCUS_SCALE else 1f)
                    .setDuration(FOCUS_ANIM_MS)
                    .start()
                if (hasFocus) row?.let(onFocused)
            }
        }

        fun bind(item: ChannelListRow.ChannelItem) {
            row = item
            number.text = item.number.toString()
            logo.text = item.initial
            logo.background = logo.resources.getDrawable(
                if (item.logoUrl != null) R.drawable.bg_logo_placeholder_remote else R.drawable.bg_logo_placeholder,
                null,
            )
            name.text = item.name
            meta.text = metaText(item)
            origin.text = originText(item)
        }

        private fun metaText(item: ChannelListRow.ChannelItem): String = buildString {
            append(item.groupTitle ?: item.name)
            if (item.streamCount > 1) {
                append(" · ")
                append(item.streamCount)
                append(" 路流")
            }
        }

        private fun originText(item: ChannelListRow.ChannelItem): String = when (item.numberSource) {
            ChannelNumberSource.USER_EDIT -> "编辑"
            ChannelNumberSource.SOURCE_TVG_CHNO -> "源"
            ChannelNumberSource.AUTO -> "自动"
        }

        private companion object {
            const val FOCUS_SCALE = 1.05f
            const val FOCUS_ANIM_MS = 120L
        }
    }

    private object Diff : DiffUtil.ItemCallback<ChannelListRow>() {
        override fun areItemsTheSame(oldItem: ChannelListRow, newItem: ChannelListRow): Boolean =
            oldItem.key == newItem.key

        override fun areContentsTheSame(oldItem: ChannelListRow, newItem: ChannelListRow): Boolean =
            oldItem == newItem
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_CHANNEL = 1
    }
}

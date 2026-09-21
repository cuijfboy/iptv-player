package ilab.iptv.player.feature.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ilab.iptv.player.core.domain.source.ManagedSource
import ilab.iptv.player.core.domain.source.SourceFetchResult

/**
 * The subscription rows (docs/02 §8.2 virtualization; a subscription list is short but the pattern
 * is the one already proven on the browse screen, and it keeps focus handling identical).
 *
 * Three actions per row, all reachable with the arrow keys: enable/disable, edit, delete. The
 * adapter hands the whole [ManagedSource] back so the screen never re-derives what a row means.
 */
class SourceListAdapter(
    private val onToggle: (ManagedSource, Boolean) -> Unit,
    private val onEdit: (ManagedSource) -> Unit,
    private val onDelete: (ManagedSource) -> Unit,
) : ListAdapter<ManagedSource, SourceListAdapter.Holder>(Diff) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_source, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.source_label)
        private val state: TextView = view.findViewById(R.id.source_state)
        private val url: TextView = view.findViewById(R.id.source_url)
        private val status: TextView = view.findViewById(R.id.source_status)
        private val toggle: Button = view.findViewById(R.id.source_toggle)
        private val edit: Button = view.findViewById(R.id.source_edit)
        private val delete: Button = view.findViewById(R.id.source_delete)

        fun bind(source: ManagedSource) {
            val context = itemView.context
            label.text = source.label
            state.text = context.getString(
                if (source.enabled) R.string.source_enabled_state else R.string.source_disabled_state,
            )
            url.text = source.url
            status.text = when (source.lastResult) {
                null -> context.getString(R.string.source_status_never)
                SourceFetchResult.OK -> context.getString(R.string.source_status_ok, source.entryCount ?: 0)
                SourceFetchResult.FAILED -> source.lastFailure?.let {
                    context.getString(R.string.source_status_failed, it)
                } ?: context.getString(R.string.source_status_failed_unknown)
            }
            // The button says what pressing it does, so the label and the effect cannot disagree.
            toggle.text = context.getString(
                if (source.enabled) R.string.source_disable else R.string.source_enable,
            )
            toggle.setOnClickListener { onToggle(source, !source.enabled) }
            edit.setOnClickListener { onEdit(source) }
            delete.setOnClickListener { onDelete(source) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ManagedSource>() {
        override fun areItemsTheSame(oldItem: ManagedSource, newItem: ManagedSource): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ManagedSource, newItem: ManagedSource): Boolean =
            oldItem == newItem
    }
}

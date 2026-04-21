package dev.anonymous.cardsdesignerpro.ui.export

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.databinding.ItemSelectedFileBinding

class SelectedFileAdapter(
    private val onRemove: (Uri) -> Unit
) : RecyclerView.Adapter<SelectedFileAdapter.VH>() {

    var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null
    var onDropCommit: ((List<SelectedFile>) -> Unit)? = null

    private var items: List<SelectedFile> = emptyList()
    private var isDragging = false

    fun submitList(newList: List<SelectedFile>) {
        if (isDragging) return // Do not interfere if a drag is active
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition].uri == newList[newItemPosition].uri
            }
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition] == newList[newItemPosition] // this includes ParseResult changes
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items = newList.toList()
        diffResult.dispatchUpdatesTo(this)
    }

    fun startDragSession() {
        isDragging = true
    }

    fun swapItems(from: Int, to: Int) {
        val mutable = items.toMutableList()
        java.util.Collections.swap(mutable, from, to)
        items = mutable.toList()
        notifyItemMoved(from, to)
    }

    fun commitDragSession() {
        isDragging = false
        onDropCommit?.invoke(items)
    }

    inner class VH(private val b: ItemSelectedFileBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: SelectedFile) {
            b.tvFileName.text = item.displayName
            b.tvUnsupported.visibility = if (!item.isSupported) View.VISIBLE else View.GONE

            // Always keep badge visible to prevent item height jumps; only change the text
            b.tvCardCount.text = when {
                !item.isSupported -> ""
                item.isParsing   -> b.root.context.getString(R.string.label_file_parsing)
                else -> {
                    val count = item.parseResult?.count ?: 0
                    if (count > 0) b.root.context.getString(R.string.label_cards_count, count) else ""
                }
            }
            b.tvCardCount.alpha = if (item.isParsing) 0.6f else 1f

            b.root.alpha = if (item.isParsing) 0.6f else 1f
            b.btnRemoveFile.setOnClickListener { onRemove(item.uri) }

            b.ivDragHandle.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    onStartDrag?.invoke(this)
                }
                false
            }
        }
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemSelectedFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }
}

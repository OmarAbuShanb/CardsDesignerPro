package dev.anonymous.cardsdesignerpro.app.ui.export

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.anonymous.cardsdesignerpro.app.R
import dev.anonymous.cardsdesignerpro.app.databinding.ItemSelectedFileBinding

class SelectedFileAdapter(
    private val onRemove: (Uri) -> Unit,
    private val onEditMapping: ((SelectedFile) -> Unit)? = null
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
            val isParsedAndInvalid = !item.isParsing && item.isSupported && (item.parseResult?.isSuccess == false || item.parseResult?.count == 0)
            val needsMapping = item.parseResult?.needsColumnMapping == true
            val hasError = !item.isSupported || isParsedAndInvalid || needsMapping
            
            b.tvUnsupported.visibility = if (hasError) View.VISIBLE else View.GONE
            b.tvUnsupported.text = when {
                !item.isSupported -> b.root.context.getString(R.string.label_file_unsupported)
                isParsedAndInvalid -> b.root.context.getString(R.string.error_file_invalid_data)
                needsMapping -> b.root.context.getString(R.string.error_columns_not_recognized)
                else -> ""
            }

            // Show edit mapping button when columns need mapping
            val canEditMapping = needsMapping ||
                (!item.isTemporary && item.parseResult?.headers?.isNotEmpty() == true && item.isSupported && !item.isParsing)
            b.btnEditMapping.visibility = if (canEditMapping) {
                View.VISIBLE
            } else {
                View.GONE
            }
            b.btnEditMapping.setOnClickListener { onEditMapping?.invoke(item) }

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

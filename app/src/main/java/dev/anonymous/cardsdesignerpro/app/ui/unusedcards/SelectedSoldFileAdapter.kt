package dev.anonymous.cardsdesignerpro.app.ui.unusedcards

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.anonymous.cardsdesignerpro.app.R
import dev.anonymous.cardsdesignerpro.app.databinding.ItemSelectedFileBinding

import dev.anonymous.cardsdesignerpro.app.data.parser.ParseResult

data class SelectedSoldFile(
    val uri: Uri,
    val displayName: String,
    val isSupported: Boolean,
    val parseResult: ParseResult? = null,
    val isParsing: Boolean = false
) {
    val hasDisplayError: Boolean
        get() {
            val isParsedAndInvalid = !isParsing && isSupported &&
                (parseResult?.isSuccess == false || parseResult?.count == 0)
            val needsMapping = parseResult?.needsColumnMapping == true
            return !isSupported || isParsedAndInvalid || needsMapping
        }
}

class SelectedSoldFileAdapter(
    private val onRemove: (Uri) -> Unit
) : RecyclerView.Adapter<SelectedSoldFileAdapter.VH>() {

    private var items: List<SelectedSoldFile> = emptyList()

    fun submitList(newList: List<SelectedSoldFile>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition].uri == newList[newItemPosition].uri
            }
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition] == newList[newItemPosition]
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items = newList.toList()
        diffResult.dispatchUpdatesTo(this)
    }

    inner class VH(private val b: ItemSelectedFileBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: SelectedSoldFile) {
            b.tvFileName.text = item.displayName
            val isParsedAndInvalid = !item.isParsing && item.isSupported && (item.parseResult?.isSuccess == false || item.parseResult?.count == 0)
            val needsMapping = item.parseResult?.needsColumnMapping == true
            
            b.tvUnsupported.visibility = if (item.hasDisplayError) View.VISIBLE else View.GONE
            b.tvUnsupported.text = when {
                !item.isSupported -> b.root.context.getString(R.string.label_file_unsupported)
                isParsedAndInvalid -> b.root.context.getString(R.string.error_file_invalid_data)
                needsMapping -> b.root.context.getString(R.string.error_file_invalid_data)
                else -> ""
            }

            // Hide drag handle since we don't need sorting
            b.ivDragHandle.visibility = View.GONE
            
            // Hide edit mapping button for sold files (standard auto-detect works)
            b.btnEditMapping.visibility = View.GONE

            // Card count badge
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

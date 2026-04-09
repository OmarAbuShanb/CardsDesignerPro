package dev.anonymous.cardsdesignerpro.ui.export

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.anonymous.cardsdesignerpro.databinding.ItemSelectedFileBinding

class SelectedFileAdapter(
    private val onRemove: (Uri) -> Unit
) : ListAdapter<SelectedFile, SelectedFileAdapter.VH>(DIFF) {

    inner class VH(private val b: ItemSelectedFileBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: SelectedFile) {
            b.tvFileName.text = item.displayName
            // Unsupported indicator
            b.tvUnsupported.visibility = if (!item.isSupported) View.VISIBLE else View.GONE
            // Card count badge: visible only when supported + parsed successfully
            val count = item.parseResult?.count
            if (item.isSupported && !item.isParsing && count != null && count > 0) {
                b.tvCardCount.text = b.root.context.getString(dev.anonymous.cardsdesignerpro.R.string.label_cards_count, count)
                b.tvCardCount.visibility = View.VISIBLE
            } else {
                b.tvCardCount.visibility = View.GONE
            }
            // Dim while parsing
            b.root.alpha = if (item.isParsing) 0.5f else 1f
            b.btnRemoveFile.setOnClickListener { onRemove(item.uri) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemSelectedFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SelectedFile>() {
            override fun areItemsTheSame(a: SelectedFile, b: SelectedFile) = a.uri == b.uri
            override fun areContentsTheSame(a: SelectedFile, b: SelectedFile) = a == b
        }
    }
}

package dev.anonymous.cardsdesignerpro.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.anonymous.cardsdesignerpro.data.model.Template
import dev.anonymous.cardsdesignerpro.databinding.ItemDefaultTemplateBinding

class DefaultTemplateAdapter(
    private val onOpen: (String, Template) -> Unit
) : ListAdapter<Pair<String, Template>, DefaultTemplateAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemDefaultTemplateBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Pair<String, Template>) {
            val (dirName, template) = item
            binding.tvTemplateName.text = template.name
            binding.cardCanvas.isInteractive = false
            binding.cardCanvas.bind(template, selectedId = null)
            binding.root.setOnClickListener { onOpen(dirName, template) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDefaultTemplateBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Pair<String, Template>>() {
            override fun areItemsTheSame(oldItem: Pair<String, Template>, newItem: Pair<String, Template>) = oldItem.first == newItem.first
            override fun areContentsTheSame(oldItem: Pair<String, Template>, newItem: Pair<String, Template>) = oldItem.second.id == newItem.second.id
        }
    }
}

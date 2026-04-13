package dev.anonymous.cardsdesignerpro.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.anonymous.cardsdesignerpro.data.model.Template
import dev.anonymous.cardsdesignerpro.databinding.ItemTemplateSelectionBinding

data class SelectionItem(
    val template: Template,
    var isSelected: Boolean = true,
    val statusText: String? = null
)

class TemplateSelectionAdapter(
    private val items: List<SelectionItem>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<TemplateSelectionAdapter.VH>() {

    inner class VH(val binding: ItemTemplateSelectionBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                binding.checkbox.toggle()
            }
            binding.checkbox.setOnCheckedChangeListener { _, isChecked ->
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    items[pos].isSelected = isChecked
                    onSelectionChanged()
                }
            }
        }

        fun bind(item: SelectionItem) {
            binding.tvTemplateName.text = item.template.name
            binding.checkbox.isChecked = item.isSelected
            if (item.statusText != null) {
                binding.tvTemplateStatus.visibility = View.VISIBLE
                binding.tvTemplateStatus.text = item.statusText
            } else {
                binding.tvTemplateStatus.visibility = View.GONE
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTemplateSelectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size
    
    fun getSelectedIds(): Set<String> = items.filter { it.isSelected }.map { it.template.id }.toSet()
}

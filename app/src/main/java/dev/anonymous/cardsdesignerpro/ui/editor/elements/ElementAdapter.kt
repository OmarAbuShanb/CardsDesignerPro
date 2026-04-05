package dev.anonymous.cardsdesignerpro.ui.editor.elements

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.databinding.ItemElementBinding

class ElementAdapter(
    private val onSelect: (String) -> Unit,
    private val onVisibilityToggle: (String) -> Unit,
    private val onEdit: (String) -> Unit,
    private val onDelete: (String) -> Unit,
) : ListAdapter<TemplateElement, ElementAdapter.VH>(DIFF) {

    var selectedId: String? = null
        set(value) {
            val old = field
            field = value
            // Rebind only the two affected items — DiffUtil won't do it since element data didn't change
            currentList.indexOfFirst { it.id == old }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
            currentList.indexOfFirst { it.id == value }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        }

    inner class VH(val binding: ItemElementBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(el: TemplateElement) {
            binding.tvElementLabel.text = el.labelRes(binding.root.context)

            val isCardBg  = el is TemplateElement.CardBackground
            val isLocked  = isCardBg || el is TemplateElement.BackgroundDecorationElement || el is TemplateElement.FrameElement
            // Drag handle: hidden for locked elements (their order is enforced programmatically)
            binding.ivDragHandle.visibility = if (isLocked) View.INVISIBLE else View.VISIBLE
            // Delete: only hidden for CardBackground (the base card can never be removed)
            binding.btnDelete.visibility = if (isCardBg) View.INVISIBLE else View.VISIBLE
            // Visibility toggle: hidden only for CardBackground (can't hide the card itself)
            binding.btnVisibility.visibility = if (isCardBg) View.INVISIBLE else View.VISIBLE

            // Highlight selected: border-only stroke, no background fill
            val isSelected = el.id == selectedId
            if (isSelected) {
                val dp = binding.root.resources.displayMetrics.density
                val primaryColor = MaterialColors.getColor(
                    binding.root, android.R.attr.colorPrimary, Color.BLUE)
                binding.root.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.TRANSPARENT)
                    cornerRadius = 8f * dp
                    setStroke((2f * dp).toInt(), primaryColor)
                }
            } else {
                binding.root.background = null
            }

            // Visibility icon
            binding.btnVisibility.alpha = if (el.isVisible) 1f else 0.4f

            binding.root.setOnClickListener { onSelect(el.id) }
            binding.btnVisibility.setOnClickListener { onVisibilityToggle(el.id) }
            binding.btnEdit.setOnClickListener { onEdit(el.id) }
            binding.btnDelete.setOnClickListener { onDelete(el.id) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemElementBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TemplateElement>() {
            override fun areItemsTheSame(a: TemplateElement, b: TemplateElement) = a.id == b.id
            override fun areContentsTheSame(a: TemplateElement, b: TemplateElement) = a == b
        }
    }
}

private fun TemplateElement.labelRes(ctx: android.content.Context): String = ctx.getString(
    when (this) {
        is TemplateElement.CardBackground -> R.string.elem_card_background
        is TemplateElement.TextElement -> R.string.elem_text
        is TemplateElement.UsernameElement -> R.string.elem_username
        is TemplateElement.PasswordElement -> R.string.elem_password
        is TemplateElement.ImageElement -> R.string.elem_image
        is TemplateElement.QrElement -> R.string.elem_qr
        is TemplateElement.DateElement -> R.string.elem_date
        is TemplateElement.FrameElement -> R.string.elem_frame
        is TemplateElement.BackgroundDecorationElement -> R.string.elem_decoration
    }
)

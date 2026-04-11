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
            if (field == value) return
            val old = field
            field = value
            currentList.indexOfFirst { it.id == old }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
            currentList.indexOfFirst { it.id == value }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        }

    inner class VH(val binding: ItemElementBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(el: TemplateElement) {
            binding.tvElementLabel.text = el.labelRes(binding.root.context)

            val isCardBg  = el is TemplateElement.CardBackground
            val isLocked  = isCardBg || el is TemplateElement.FrameElement
            binding.ivDragHandle.visibility = if (isLocked) View.INVISIBLE else View.VISIBLE
            binding.btnDelete.visibility = if (isCardBg) View.INVISIBLE else View.VISIBLE
            binding.btnVisibility.visibility = if (isCardBg) View.INVISIBLE else View.VISIBLE

            // Selection highlight
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
            binding.btnVisibility.setImageResource(
                if (el.isVisible) R.drawable.ic_visibility_24
                else R.drawable.ic_visibility_off_24
            )
            binding.btnVisibility.alpha = if (el.isVisible) 1f else 0.5f

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

private fun TemplateElement.labelRes(ctx: android.content.Context): String {
    if (this is TemplateElement.ImageElement) {
        return ctx.getString(when {
            imagePath.contains("packs/icons/")    -> R.string.elem_icon
            imagePath.contains("packs/fields/")   -> R.string.elem_field
            imagePath.contains("packs/dividers/") -> R.string.elem_divider
            imagePath.contains("packs/logos/")    -> R.string.elem_logo
            imagePath.contains("packs/misc/")     -> R.string.elem_misc
            else -> R.string.elem_image
        })
    }
    return ctx.getString(when (this) {
        is TemplateElement.CardBackground -> R.string.elem_card_background
        is TemplateElement.TextElement -> R.string.elem_text
        is TemplateElement.UsernameElement -> R.string.elem_username
        is TemplateElement.PasswordElement -> R.string.elem_password
        is TemplateElement.QrElement -> R.string.elem_qr
        is TemplateElement.DateElement -> R.string.elem_date
        is TemplateElement.FrameElement -> R.string.elem_frame
        else -> R.string.elem_image
    })
}

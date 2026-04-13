package dev.anonymous.cardsdesignerpro.ui.main

import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListPopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.data.model.Template
import dev.anonymous.cardsdesignerpro.databinding.ItemTemplateBinding

class TemplateAdapter(
    private val onOpen: (Template) -> Unit,
    private val onRename: (Template) -> Unit,
    private val onDuplicate: (Template) -> Unit,
    private val onDelete: (Template) -> Unit,
) : ListAdapter<Template, TemplateAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemTemplateBinding) : RecyclerView.ViewHolder(binding.root) {

        private var popup: ListPopupWindow? = null
        private var lastDismissTime = 0L

        fun bind(template: Template) {
            binding.tvTemplateName.text = template.name
            binding.cardContainer.setOnClickListener { onOpen(template) }
            
            // Render template efficiently
            binding.cardCanvas.isInteractive = false
            binding.cardCanvas.bind(template, selectedId = null)
            
            binding.btnMore.setOnClickListener { view ->
                if (System.currentTimeMillis() - lastDismissTime < 250) {
                    return@setOnClickListener
                }
                if (popup != null && popup?.isShowing == true) {
                    popup?.dismiss()
                    return@setOnClickListener
                }

                val actions = listOf(
                    Triple(R.id.action_rename, R.string.menu_rename, R.drawable.ic_rename_24),
                    Triple(R.id.action_duplicate, R.string.menu_duplicate, R.drawable.ic_duplicate_24),
                    Triple(R.id.action_delete, R.string.menu_delete, R.drawable.ic_delete_24)
                )

                popup = ListPopupWindow(view.context)
                val adapter = object : ArrayAdapter<Triple<Int, Int, Int>>(view.context, 0, actions) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val itemView = convertView ?: LayoutInflater.from(context)
                            .inflate(R.layout.item_template_action, parent, false)
                        val action = getItem(position)!!
                        itemView.findViewById<TextView>(R.id.tv_action_title).setText(action.second)
                        itemView.findViewById<ImageView>(R.id.iv_action_icon).setImageResource(action.third)
                        return itemView
                    }
                }
                popup?.setAdapter(adapter)
                popup?.anchorView = view
                
                // Measure to fit nicely
                var maxWidth = 0
                for (i in 0 until adapter.count) {
                    val v = adapter.getView(i, null, android.widget.FrameLayout(view.context))
                    v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                    if (v.measuredWidth > maxWidth) maxWidth = v.measuredWidth
                }
                popup?.setContentWidth(maxWidth)

                popup?.setOnDismissListener {
                    lastDismissTime = System.currentTimeMillis()
                    popup = null
                }

                popup?.setOnItemClickListener { _, _, position, _ ->
                    when (actions[position].first) {
                        R.id.action_rename -> onRename(template)
                        R.id.action_duplicate -> onDuplicate(template)
                        R.id.action_delete -> onDelete(template)
                    }
                    popup?.dismiss()
                }
                
                popup?.show()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTemplateBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Template>() {
            override fun areItemsTheSame(oldItem: Template, newItem: Template) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Template, newItem: Template) = oldItem == newItem
        }
    }
}

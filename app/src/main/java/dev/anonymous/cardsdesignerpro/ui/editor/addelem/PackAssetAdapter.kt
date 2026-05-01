package dev.anonymous.cardsdesignerpro.ui.editor.addelem

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.caverock.androidsvg.SVG
import dev.anonymous.cardsdesignerpro.databinding.ItemPackAssetBinding

/**
 * Displays a grid of SVG assets from a packs category.
 * Each item shows a preview; clicking it invokes [onAssetSelected]
 * with the full pack: path.
 */
class PackAssetAdapter(
    private val onAssetSelected: (packPath: String) -> Unit
) : RecyclerView.Adapter<PackAssetAdapter.VH>() {

    /** List of asset paths relative to assets/, e.g. "packs/icons/pass_icon.svg" */
    private var items: List<String> = emptyList()
    private var category: String = ""

    fun submitList(category: String, assets: List<String>) {
        this.category = category
        this.items = assets
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPackAssetBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val assetPath = items[position]
        holder.bind(assetPath, holder.itemView.context)
        holder.itemView.setOnClickListener {
            onAssetSelected("pack:$assetPath")
        }
    }

    class VH(private val binding: ItemPackAssetBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(assetPath: String, context: Context) {
            try {
                if (assetPath.endsWith(".svg", ignoreCase = true)) {
                    val svg = context.assets.open(assetPath).use { SVG.getFromInputStream(it) }
                    val picture = svg.renderToPicture()
                    binding.ivPackAsset.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                    binding.ivPackAsset.setImageDrawable(
                        android.graphics.drawable.PictureDrawable(picture)
                    )
                } else {
                    val drawable = context.assets.open(assetPath).use {
                        android.graphics.drawable.Drawable.createFromStream(it, null)
                    }
                    binding.ivPackAsset.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    binding.ivPackAsset.setImageDrawable(drawable)
                }
            } catch (e: Exception) {
                binding.ivPackAsset.setImageDrawable(null)
            }
        }
    }
}

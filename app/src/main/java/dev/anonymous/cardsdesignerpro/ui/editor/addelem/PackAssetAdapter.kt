package dev.anonymous.cardsdesignerpro.ui.editor.addelem

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.PictureDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.caverock.androidsvg.SVG
import dev.anonymous.cardsdesignerpro.databinding.ItemPackAssetBinding
import kotlinx.coroutines.launch
import androidx.core.graphics.drawable.toDrawable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * Displays a grid of SVG assets from a packs category.
 * Each item shows a preview; clicking it invokes [onAssetSelected]
 * with the full pack: path.
 */
class PackAssetAdapter(
    private val onAssetSelected: (packPath: String) -> Unit
) : RecyclerView.Adapter<PackAssetAdapter.VH>() {

    /** List of asset paths relative to assets/, e.g. "packs/credential_icons/pass_icon.svg" */
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
        private var currentJob: Job? = null

        fun bind(assetPath: String, context: Context) {
            currentJob?.cancel()
            binding.ivPackAsset.setImageDrawable(null)

            currentJob = CoroutineScope(Dispatchers.Main).launch {
                try {
                    val drawable = withContext(Dispatchers.IO) {
                        if (assetPath.endsWith(".svg", ignoreCase = true)) {
                            val svg = context.assets.open(assetPath).use { SVG.getFromInputStream(it) }
                            PictureDrawable(svg.renderToPicture())
                        } else {
                            val bitmap = context.assets.open(assetPath).use {
                                BitmapFactory.decodeStream(it)
                            }
                            if (bitmap != null) {
                                bitmap.toDrawable(context.resources)
                            } else null
                        }
                    }

                    if (assetPath.endsWith(".svg", ignoreCase = true)) {
                        binding.ivPackAsset.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    } else {
                        binding.ivPackAsset.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    }
                    binding.ivPackAsset.setImageDrawable(drawable)
                } catch (_: Exception) {
                    binding.ivPackAsset.setImageDrawable(null)
                }
            }
        }
    }
}

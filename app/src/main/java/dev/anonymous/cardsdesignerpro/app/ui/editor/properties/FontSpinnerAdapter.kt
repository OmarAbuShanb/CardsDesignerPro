package dev.anonymous.cardsdesignerpro.app.ui.editor.properties

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import dev.anonymous.cardsdesignerpro.app.R
import java.io.File

class FontSpinnerAdapter(
    context: Context,
    private val fonts: List<Pair<String, String>>,
    var previewText: String = "نص تجريبي",
    /** Directory containing custom font files (.ttf/.otf) for the current template. */
    private val fontsDir: File? = null,
    /** Called when the user taps the delete icon on a custom font row. Passes the font file name. */
    var onDeleteCustomFont: ((fontFileName: String) -> Unit)? = null
) : ArrayAdapter<Pair<String, String>>(context, 0, fonts) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createViewFromResource(position, convertView, parent, isSelectionView = true)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createViewFromResource(position, convertView, parent, isSelectionView = false)
    }

    private fun createViewFromResource(
        position: Int,
        convertView: View?,
        parent: ViewGroup,
        isSelectionView: Boolean
    ): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_font_spinner, parent, false)
        val tvName = view.findViewById<TextView>(R.id.tv_font_name)
        val tvPreview = view.findViewById<TextView>(R.id.tv_font_preview)
        val btnDelete = view.findViewById<ImageView>(R.id.btn_delete_font)

        val fontPair = fonts[position]
        val fontName = fontPair.second
        val isActionItem = fontName == PICK_CUSTOM_FONT_SENTINEL
        val isCustomFont = fontName.startsWith("custom:")

        tvName.text = fontPair.first

        // Action item ("Choose custom font…"): no preview text, no custom typeface
        if (isActionItem) {
            tvPreview.visibility = View.GONE
            tvName.typeface = Typeface.DEFAULT
            btnDelete.visibility = View.GONE
            return view
        }

        // Preview text: shown only in dropdown, hidden in the collapsed selection view
        if (isSelectionView) {
            tvPreview.visibility = View.GONE
            btnDelete.visibility = View.GONE
        } else {
            tvPreview.visibility = View.VISIBLE
            tvPreview.text = previewText

            // Delete button: shown only for custom fonts in dropdown
            if (isCustomFont) {
                btnDelete.visibility = View.VISIBLE
                btnDelete.setOnClickListener {
                    val fileName = fontName.removePrefix("custom:")
                    onDeleteCustomFont?.invoke(fileName)
                }
            } else {
                btnDelete.visibility = View.GONE
                btnDelete.setOnClickListener(null)
            }
        }

        // Apply typeface
        val typeface = resolveTypeface(context, fontName)
        tvName.typeface = typeface
        tvPreview.typeface = typeface

        return view
    }

    private fun resolveTypeface(context: Context, fontName: String): Typeface {
        if (fontName.startsWith("custom:")) {
            val fileName = fontName.removePrefix("custom:")
            val file = fontsDir?.let { File(it, fileName) }
            return if (file != null && file.exists()) {
                try { Typeface.createFromFile(file) } catch (_: Exception) { Typeface.DEFAULT }
            } else Typeface.DEFAULT
        }
        val baseName = fontName.lowercase()
        if (baseName == "default" || baseName.isEmpty()) return Typeface.DEFAULT
        return try {
            val resId = context.resources.getIdentifier(baseName, "font", context.packageName)
            if (resId != 0) androidx.core.content.res.ResourcesCompat.getFont(context, resId) ?: Typeface.DEFAULT
            else Typeface.DEFAULT
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
    }
}

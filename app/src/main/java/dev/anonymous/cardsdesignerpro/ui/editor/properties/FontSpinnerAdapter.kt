package dev.anonymous.cardsdesignerpro.ui.editor.properties

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import dev.anonymous.cardsdesignerpro.R

class FontSpinnerAdapter(
    context: Context,
    private val fonts: List<Pair<String, String>>,
    var previewText: String = "نص تجريبي"
) : ArrayAdapter<Pair<String, String>>(context, 0, fonts) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createViewFromResource(position, convertView, parent, true)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createViewFromResource(position, convertView, parent, false)
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

        val fontPair = fonts[position]
        tvName.text = fontPair.first
        
        if (isSelectionView) {
            tvPreview.visibility = View.GONE
        } else {
            tvPreview.visibility = View.VISIBLE
            tvPreview.text = previewText
        }

        val fontName = fontPair.second
        val typeface = resolveTypeface(context, fontName)
        tvName.typeface = typeface
        tvPreview.typeface = typeface

        return view
    }

    private fun resolveTypeface(context: Context, fontName: String): Typeface {
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

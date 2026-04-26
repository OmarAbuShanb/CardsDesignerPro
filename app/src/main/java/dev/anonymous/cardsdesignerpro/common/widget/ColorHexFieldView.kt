package dev.anonymous.cardsdesignerpro.common.widget

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import dev.anonymous.cardsdesignerpro.R

/**
 * Reusable clickable field that shows a color hex value with an edit icon.
 * Used next to [ColorPreviewView] across property fragments.
 */
class ColorHexFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val valueTextView: TextView

    var text: CharSequence
        get() = valueTextView.text
        set(value) {
            valueTextView.text = value
        }

    init {
        val density = resources.displayMetrics.density
        radius = 6f * density
        cardElevation = 0f
        strokeWidth = (1f * density).toInt().coerceAtLeast(1)
        strokeColor = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOutlineVariant,
            Color.LTGRAY
        )
        setCardBackgroundColor(
            MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorSurfaceVariant,
                Color.WHITE
            )
        )
        isClickable = true
        isFocusable = true
        foreground = resolveBorderlessRipple()

        LayoutInflater.from(context).inflate(R.layout.view_color_hex_field, this, true)
        valueTextView = findViewById(R.id.tv_hex_value)
    }

    private fun resolveBorderlessRipple() =
        TypedValue().let { typedValue ->
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)) {
                AppCompatResources.getDrawable(context, typedValue.resourceId)
            } else {
                null
            }
        }
}

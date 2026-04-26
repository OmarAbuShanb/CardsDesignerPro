package dev.anonymous.cardsdesignerpro.common.widget

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.graphics.drawable.GradientDrawable
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import androidx.core.graphics.toColorInt

/**
 * A small square view showing a color. Tapping it opens a [ColorPickerDialog].
 *
 * @property enableAlpha When `true` (default), an alpha (transparency) slider is shown
 *   and the returned hex will be `#AARRGGBB`. When `false`, hex is `#RRGGBB`.
 * @property onColorSelected Callback invoked with the chosen hex string.
 */
class ColorPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Show the alpha (opacity) slider in the color picker dialog. Default: true. */
    var enableAlpha: Boolean = true

    var colorHex: String = "#FFFFFF"
        set(value) {
            field = value
            try {
                val drawable = GradientDrawable()
                drawable.shape = GradientDrawable.RECTANGLE
                drawable.setColor(value.toColorInt())
                drawable.setStroke(
                    (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    "#44888888".toColorInt()
                )
                drawable.cornerRadius = 4 * resources.displayMetrics.density
                background = drawable
            } catch (_: Exception) {}
        }

    var onColorSelected: ((String) -> Unit)? = null
    /** Optional click callback used when [openPickerOnClick] is disabled. */
    var onPreviewClick: (() -> Unit)? = null
    /** When false, tapping the swatch won't open the picker dialog. */
    var openPickerOnClick: Boolean = true

    /** Guard: prevents showing the dialog twice on a fast double-tap. */
    private var isPickerShowing = false

    init {
        val size = (32 * resources.displayMetrics.density).toInt()
        minimumWidth = size
        minimumHeight = size
        isClickable = true
        isFocusable = true
        elevation = 3 * resources.displayMetrics.density
        // Initialize the visual appearance
        colorHex = "#FFFFFF"
        setOnClickListener {
            if (openPickerOnClick) showPicker() else onPreviewClick?.invoke()
        }
    }

    private fun showPicker() {
        if (isPickerShowing) return
        isPickerShowing = true

        // ── Resolve visual initial color ──────────────────────────────────────
        // For pure black (#000000): brightness slider would be at 0, making every
        // hue appear black. We instead open with full brightness so the user can
        // pick any color, then optionally drag brightness down to get black.
        val initialColor: Int
        try {
            val parsed = colorHex.toColorInt()
            val hsv = FloatArray(3)
            Color.colorToHSV(parsed, hsv)
            initialColor = if (hsv[2] < 0.01f) {
                hsv[2] = 1.0f
                Color.HSVToColor(Color.alpha(parsed), hsv)
            } else {
                parsed
            }
        } catch (_: Exception) {
            isPickerShowing = false
            return
        }

        // trackedHex starts as the VISUAL initial color (what the user sees when the
        // dialog opens). This way pressing OK without touching anything correctly
        // returns the displayed color, not the internal hex (which could differ for black).
        var trackedHex: String = if (enableAlpha)
            "#%08X".format(initialColor)
        else
            "#%06X".format(initialColor and 0xFFFFFF)

        val builder = ColorPickerDialog.Builder(context)
            .setTitle("اختيار اللون")
            .setPositiveButton("موافق") { _, _ ->
                onColorSelected?.invoke(trackedHex)
                colorHex = trackedHex
                isPickerShowing = false
            }
            .setNegativeButton("إلغاء") { dialog, _ ->
                dialog.dismiss()
                isPickerShowing = false
            }
            .attachAlphaSlideBar(enableAlpha)
            .attachBrightnessSlideBar(true)

        builder.colorPickerView.setInitialColor(initialColor)

        builder.colorPickerView.setColorListener(
            ColorEnvelopeListener { envelope, _ ->
                // Track every change (fromUser or not) so the picker's displayed
                // color is always what gets returned on OK.
                trackedHex = if (enableAlpha) "#${envelope.hexCode}"
                             else "#${envelope.hexCode.substring(2)}"
            }
        )

        val dialog = builder.show()
        dialog.setOnDismissListener { isPickerShowing = false }
    }
}

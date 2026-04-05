package dev.anonymous.cardsdesignerpro.common.widget

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.View
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener

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
            try { background = ColorDrawable(Color.parseColor(value)) }
            catch (_: Exception) {}
        }

    var onColorSelected: ((String) -> Unit)? = null

    /** Guard: prevents showing the dialog twice on a fast double-tap. */
    private var isPickerShowing = false

    init {
        val size = (32 * resources.displayMetrics.density).toInt()
        minimumWidth = size
        minimumHeight = size
        colorHex = "#FFFFFF"
        isClickable = true
        isFocusable = true
        background = ColorDrawable(Color.WHITE)
        setOnClickListener { showPicker() }
    }

    private fun showPicker() {
        if (isPickerShowing) return
        isPickerShowing = true

        // trackedHex: always starts at the current color.
        // If the user presses OK without touching anything, the original color is re-applied (no-op).
        var trackedHex: String = colorHex

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

        // ── Initial color for the picker selectors ────────────────────────────
        // Root cause: in HSV, when Value (brightness) == 0 the entire color is black
        // regardless of Hue or Saturation.  Moving the hue wheel while V=0 still fires
        // onColorSelected(BLACK, fromUser=true), so trackedHex never actually changes.
        // Fix: if the stored color is very dark (V < 0.25), lift V to 0.30 for the
        // picker so the wheel is immediately usable.  trackedHex still holds the
        // original hex, so pressing OK with no wheel movement keeps the original color.
        try {
            val parsed = Color.parseColor(colorHex)
            val alpha  = Color.alpha(parsed)
            val hsv    = FloatArray(3)
            Color.colorToHSV(parsed, hsv)
            if (hsv[2] < 0.25f) hsv[2] = 0.30f          // lift dark → hue wheel usable
            val pickerInitColor = Color.HSVToColor(alpha, hsv)
            builder.colorPickerView.setInitialColor(pickerInitColor)
        } catch (_: Exception) {}

        // Track every user-initiated color change (wheel, brightness slider, alpha slider).
        // fromUser=false fires on programmatic updates (setInitialColor, layout) — skip those
        // to avoid overwriting a hue/saturation change the user just made on the wheel.
        builder.colorPickerView.setColorListener(
            ColorEnvelopeListener { envelope, fromUser ->
                if (fromUser) {
                    trackedHex = if (enableAlpha) "#${envelope.hexCode}"
                                 else "#${envelope.hexCode.substring(2)}"
                }
            }
        )

        builder.show()
    }
}


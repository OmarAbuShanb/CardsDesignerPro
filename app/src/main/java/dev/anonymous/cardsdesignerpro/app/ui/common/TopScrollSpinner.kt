package dev.anonymous.cardsdesignerpro.app.ui.common

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSpinner
import androidx.appcompat.widget.ListPopupWindow

/**
 * A Spinner that always scrolls the dropdown to the top when opened,
 * instead of the default behavior of scrolling to the selected item.
 */
class TopScrollSpinner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.spinnerStyle
) : AppCompatSpinner(context, attrs, defStyleAttr) {

    override fun performClick(): Boolean {
        val result = super.performClick()
        // After the popup opens, scroll it to position 0
        post {
            try {
                val popupField = AppCompatSpinner::class.java.getDeclaredField("mPopup")
                popupField.isAccessible = true
                val popup = popupField.get(this)
                if (popup is ListPopupWindow) {
                    popup.listView?.setSelection(0)
                }
            } catch (_: Exception) { /* silently ignore on future API changes */ }
        }
        return result
    }
}

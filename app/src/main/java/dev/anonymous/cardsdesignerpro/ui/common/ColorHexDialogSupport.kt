package dev.anonymous.cardsdesignerpro.ui.common

import android.os.Bundle
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import java.util.Locale

object ColorHexDialogSupport {

    fun registerResultListener(
        fragment: Fragment,
        owner: LifecycleOwner,
        requestKey: String,
        onColorSelected: (String) -> Unit
    ) {
        fragment.parentFragmentManager.setFragmentResultListener(requestKey, owner) { _, bundle ->
            val hex = bundle.getString(ColorHexInputDialogFragment.RESULT_HEX) ?: return@setFragmentResultListener
            onColorSelected(hex)
        }
    }

    fun showDialog(
        fragment: Fragment,
        requestKey: String,
        dialogTag: String,
        initialHex: String,
        enableAlpha: Boolean
    ) {
        if (fragment.parentFragmentManager.findFragmentByTag(dialogTag) != null) return
        ColorHexInputDialogFragment.newInstance(
            requestKey = requestKey,
            initialHex = sanitizeInitialHex(
                candidate = initialHex,
                enableAlpha = enableAlpha,
                fallback = if (enableAlpha) "#FFFFFFFF" else "#FFFFFF"
            ),
            enableAlpha = enableAlpha
        ).show(fragment.parentFragmentManager, dialogTag)
    }

    fun sanitizeInitialHex(candidate: String?, enableAlpha: Boolean, fallback: String): String {
        return normalizeHex(candidate, enableAlpha)
            ?: normalizeHex(fallback, enableAlpha)
            ?: if (enableAlpha) "#FFFFFFFF" else "#FFFFFF"
    }

    fun isHexColor(text: String?): Boolean = normalizeHex(text, enableAlpha = true) != null

    /** Normalizes user-entered hex to `#RRGGBB` or `#AARRGGBB` (when alpha is enabled). */
    fun normalizeHex(rawInput: String?, enableAlpha: Boolean): String? {
        val raw = rawInput?.trim().orEmpty()
        if (raw.isBlank()) return null

        val noHash = raw.removePrefix("#")
        if (noHash.isBlank()) return null

        val upper = noHash.uppercase(Locale.US)
        if (!HEX_PATTERN.matches(upper)) return null

        var expanded = when (upper.length) {
            3, 4 -> buildString(upper.length * 2) {
                upper.forEach { c ->
                    append(c)
                    append(c)
                }
            }
            6, 8 -> upper
            else -> return null
        }

        if (!enableAlpha && expanded.length == 8) {
            expanded = expanded.substring(2)
        }

        val normalized = "#$expanded"
        return try {
            normalized.toColorInt()
            normalized
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private val HEX_PATTERN = Regex("^[0-9A-F]+$")
}

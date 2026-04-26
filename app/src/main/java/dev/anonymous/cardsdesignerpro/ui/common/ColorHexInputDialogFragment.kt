package dev.anonymous.cardsdesignerpro.ui.common

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.common.widget.ColorPreviewView
import dev.anonymous.cardsdesignerpro.databinding.DialogColorHexInputBinding

class ColorHexInputDialogFragment : DialogFragment() {

    private var _binding: DialogColorHexInputBinding? = null
    private val binding get() = _binding!!
    private val presetSwatches = mutableListOf<PresetSwatch>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogColorHexInputBinding.inflate(layoutInflater)
        val requestKey = requireArguments().getString(ARG_REQUEST_KEY).orEmpty()
        val initialHex = requireArguments().getString(ARG_INITIAL_HEX).orEmpty()
        val enableAlpha = requireArguments().getBoolean(ARG_ENABLE_ALPHA, true)

        binding.etColorHex.setText(initialHex)
        binding.etColorHex.setSelection(binding.etColorHex.text?.length ?: 0)
        binding.tilColorHex.error = null
        binding.etColorHex.doAfterTextChanged {
            binding.tilColorHex.error = null
            updatePresetSelection(enableAlpha)
        }
        binding.etColorHex.setOnEditorActionListener { _, _, _ ->
            submitHex(requestKey, enableAlpha)
            true
        }

        bindPresetColors(enableAlpha)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.color_hex_dialog_title)
            .setView(binding.root)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_confirm, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                submitHex(requestKey, enableAlpha)
            }
            focusAndShowKeyboard()
        }

        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )

        return dialog
    }

    private fun bindPresetColors(enableAlpha: Boolean) {
        binding.groupQuickColors.removeAllViews()
        presetSwatches.clear()
        val swatchSizePx = (36 * resources.displayMetrics.density).toInt()
        val marginPx = (4 * resources.displayMetrics.density).toInt()

        DEFAULT_PRESET_COLORS.forEach { preset ->
            val normalized = ColorHexDialogSupport.normalizeHex(preset, enableAlpha) ?: return@forEach
            val colorInt = try {
                normalized.toColorInt()
            } catch (_: IllegalArgumentException) {
                return@forEach
            }

            val swatch = ColorPreviewView(requireContext()).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                colorHex = normalized
                openPickerOnClick = false
                // In quick-colors mode, tapping should only fill the input.
                onPreviewClick = { applyPresetToInput(normalized, enableAlpha) }
                // Keep the overlay check icon above this swatch.
                elevation = 0f
            }

            val checkView = AppCompatImageView(requireContext()).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (16 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt(),
                    Gravity.CENTER
                )
                setImageResource(R.drawable.ic_check_24)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    if (ColorUtils.calculateLuminance(colorInt) > 0.55) Color.BLACK else Color.WHITE
                )
                elevation = 8 * resources.displayMetrics.density
                visibility = ImageView.GONE
            }

            val container = FrameLayout(requireContext()).apply {
                layoutParams = ViewGroup.MarginLayoutParams(swatchSizePx, swatchSizePx).apply {
                    marginEnd = marginPx
                    bottomMargin = marginPx
                }
                contentDescription = getString(R.string.color_hex_dialog_color_item_cd, preset)
                isClickable = true
                isFocusable = true
                setOnClickListener { applyPresetToInput(normalized, enableAlpha) }
                addView(swatch)
                addView(checkView)
            }

            binding.groupQuickColors.addView(container)
            presetSwatches += PresetSwatch(
                rgb = colorInt and 0x00FFFFFF,
                checkView = checkView
            )
        }

        updatePresetSelection(enableAlpha)
    }

    private fun applyPresetToInput(colorHex: String, enableAlpha: Boolean) {
        binding.tilColorHex.error = null
        binding.etColorHex.setText(colorHex)
        binding.etColorHex.setSelection(binding.etColorHex.text?.length ?: 0)
        updatePresetSelection(enableAlpha)
    }

    private fun updatePresetSelection(enableAlpha: Boolean) {
        if (presetSwatches.isEmpty()) return
        val selectedColorInt = ColorHexDialogSupport.normalizeHex(
            rawInput = binding.etColorHex.text?.toString(),
            enableAlpha = enableAlpha
        )?.let {
            runCatching { it.toColorInt() }.getOrNull()
        }
        val selectedRgb = selectedColorInt?.and(0x00FFFFFF)
        presetSwatches.forEach { swatch ->
            swatch.checkView.visibility =
                if (selectedRgb != null && swatch.rgb == selectedRgb) {
                    ImageView.VISIBLE
                } else {
                    ImageView.GONE
                }
        }
    }

    private fun submitHex(requestKey: String, enableAlpha: Boolean) {
        val normalized = ColorHexDialogSupport.normalizeHex(
            binding.etColorHex.text?.toString(),
            enableAlpha
        )
        if (normalized == null) {
            binding.tilColorHex.error = getString(R.string.color_hex_dialog_invalid)
            return
        }
        binding.tilColorHex.error = null
        deliverResult(requestKey, normalized)
    }

    private fun deliverResult(requestKey: String, colorHex: String) {
        parentFragmentManager.setFragmentResult(
            requestKey,
            Bundle().apply { putString(RESULT_HEX, colorHex) }
        )
        dismiss()
    }

    private fun focusAndShowKeyboard() {
        binding.etColorHex.requestFocus()
        binding.etColorHex.post {
            val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
            imm?.showSoftInput(binding.etColorHex, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ColorHexInputDialog"
        const val RESULT_HEX = "result_hex"

        private const val ARG_REQUEST_KEY = "arg_request_key"
        private const val ARG_INITIAL_HEX = "arg_initial_hex"
        private const val ARG_ENABLE_ALPHA = "arg_enable_alpha"

        // Add or remove presets here; the dialog UI updates automatically.
        private val DEFAULT_PRESET_COLORS = listOf(
            "#FFFFFF", "#000000", "#F44336", "#E91E63", "#9C27B0",
            "#3F51B5", "#2196F3", "#009688", "#4CAF50", "#FFC107"
        )

        fun newInstance(
            requestKey: String,
            initialHex: String,
            enableAlpha: Boolean
        ): ColorHexInputDialogFragment {
            return ColorHexInputDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_REQUEST_KEY, requestKey)
                    putString(ARG_INITIAL_HEX, initialHex)
                    putBoolean(ARG_ENABLE_ALPHA, enableAlpha)
                }
            }
        }
    }

    private data class PresetSwatch(
        val rgb: Int,
        val checkView: ImageView
    )
}

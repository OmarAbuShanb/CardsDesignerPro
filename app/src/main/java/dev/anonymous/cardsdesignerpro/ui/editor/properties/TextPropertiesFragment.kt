package dev.anonymous.cardsdesignerpro.ui.editor.properties

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.data.model.TextAlign
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropTextBinding
import dev.anonymous.cardsdesignerpro.ui.common.ColorHexDialogSupport
import dev.anonymous.cardsdesignerpro.ui.editor.EditorUiState
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel.Companion.MAX_TEXT_SIZE_SP
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel.Companion.MIN_TEXT_SIZE_SP

fun getAvailableFonts(context: android.content.Context) = listOf(
    context.getString(R.string.font_default) to "default",
    "Abril Fatface" to "abril_fatface_regular",
    "Almarai" to "almarai",
    "Aref Ruqaa" to "aref_ruqaa",
    "Cairo" to "cairo",
    "Inter" to "inter_18pt",
    "JetBrains Mono" to "jet_brains_mono",
    "Lora" to "lora",
    "Montserrat" to "montserrat",
    "Oswald" to "oswald",
    "Playfair Display" to "playfair_display",
    "Prata" to "prata_regular",
    "Special Elite" to "special_elite_regular"
)

class TextPropertiesFragment : Fragment(), PropertyFragment {

    private var _binding: FragmentPropTextBinding? = null
    private val binding get() = _binding!!
    val viewModel: EditorViewModel by activityViewModels()
    private var updating = false
    private var fontAdapter: FontSpinnerAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPropTextBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupColorHexDialogResultListeners()
        setupFontSpinner()
        populateFrom(viewModel.selectedElement as? TemplateElement.TextElement ?: return)
        setupListeners()
    }

    private fun setupColorHexDialogResultListeners() {
        ColorHexDialogSupport.registerResultListener(
            fragment = this,
            owner = viewLifecycleOwner,
            requestKey = REQ_TEXT_COLOR_HEX
        ) { hex ->
            applyTextColor(hex)
        }
        ColorHexDialogSupport.registerResultListener(
            fragment = this,
            owner = viewLifecycleOwner,
            requestKey = REQ_BG_COLOR_HEX
        ) { hex ->
            applyBgColor(hex)
        }
        ColorHexDialogSupport.registerResultListener(
            fragment = this,
            owner = viewLifecycleOwner,
            requestKey = REQ_STROKE_COLOR_HEX
        ) { hex ->
            applyStrokeColor(hex)
        }
    }

    private fun setupFontSpinner() {
        fontAdapter = FontSpinnerAdapter(requireContext(), getAvailableFonts(requireContext()))
        binding.spinnerFont.adapter = fontAdapter
    }

    private fun populateFrom(el: TemplateElement.TextElement) {
        updating = true

        val showText = if (el.text == getString(R.string.default_text_placeholder)) "" else el.text
        if (binding.etText.text.toString() != showText) {
            binding.etText.setText(showText)
            binding.etText.setSelection(showText.length)
        }

        if (binding.cpvTextColor.colorHex != el.textColor) binding.cpvTextColor.colorHex = el.textColor
        binding.fieldTextColorHex.text = el.textColor

        if (el.bgColor != null) {
            if (binding.cpvBgColor.colorHex != el.bgColor) binding.cpvBgColor.colorHex = el.bgColor
            binding.fieldBgColorHex.text = el.bgColor
            binding.btnClearBgColor.visibility = View.VISIBLE
        } else {
            binding.cpvBgColor.colorHex = "#FFFFFFFF"
            binding.fieldBgColorHex.text = getString(R.string.prop_no_bg_color)
            binding.btnClearBgColor.visibility = View.GONE
        }

        if (binding.cbBold.isChecked != el.isBold) binding.cbBold.isChecked = el.isBold

        val targetSize = kotlin.math.round(el.textSizeSp).coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
        if (binding.sliderFontSize.value != targetSize) {
            binding.sliderFontSize.value = targetSize
        }
        binding.tvFontSizeLabel.text = getString(R.string.prop_font_size) + ": ${targetSize.toInt()}"

        val hasStroke = el.textStrokeWidth > 0f
        if (binding.cbTextStroke.isChecked != hasStroke) binding.cbTextStroke.isChecked = hasStroke
        binding.layoutTextStrokeOptions.visibility = if (hasStroke) View.VISIBLE else View.GONE

        val targetStroke = el.textStrokeWidth.coerceAtLeast(1f).coerceAtMost(10f)
        if (binding.sliderTextStroke.value != targetStroke) {
            binding.sliderTextStroke.value = targetStroke
        }
        binding.tvStrokeSizeLabel.text = getString(R.string.prop_text_stroke) + ": ${targetStroke.toInt()}"
        if (binding.cpvStrokeColor.colorHex != el.textStrokeColor) binding.cpvStrokeColor.colorHex = el.textStrokeColor
        binding.fieldStrokeColorHex.text = el.textStrokeColor

        val previewStr = if (showText.isBlank()) "نص تجريبي" else showText
        if (fontAdapter?.previewText != previewStr) {
            fontAdapter?.previewText = previewStr
            fontAdapter?.notifyDataSetChanged()
        }

        val fontIdx = getAvailableFonts(requireContext()).indexOfFirst { it.second == el.fontName }.coerceAtLeast(0)
        if (binding.spinnerFont.selectedItemPosition != fontIdx) binding.spinnerFont.setSelection(fontIdx)

        val alignBtnId = when (el.textAlign) {
            TextAlign.START -> R.id.btn_align_start
            TextAlign.CENTER -> R.id.btn_align_center
            TextAlign.END -> R.id.btn_align_end
        }
        if (binding.toggleTextAlign.checkedButtonId != alignBtnId) {
            binding.toggleTextAlign.check(alignBtnId)
        }

        updating = false
    }

    private fun setupListeners() {
        binding.sliderFontSize.valueFrom = MIN_TEXT_SIZE_SP
        binding.sliderFontSize.valueTo = MAX_TEXT_SIZE_SP

        binding.etText.doAfterTextChanged { text ->
            if (updating) return@doAfterTextChanged
            val el = viewModel.selectedElement as? TemplateElement.TextElement ?: return@doAfterTextChanged
            val newText = text.toString().ifEmpty { getString(R.string.default_text_placeholder) }
            if (el.text == newText) return@doAfterTextChanged
            val updated = el.copy(text = newText)
            viewModel.updateElement(updated)
            viewModel.resizeTextElementToFit(updated)
        }

        binding.cpvTextColor.onColorSelected = { hex -> applyTextColor(hex) }
        binding.fieldTextColorHex.setOnClickListener { openTextColorHexDialog() }

        binding.cpvBgColor.onColorSelected = { hex -> applyBgColor(hex) }
        binding.fieldBgColorHex.setOnClickListener { openBgColorHexDialog() }
        binding.btnClearBgColor.setOnClickListener { clearBgColor() }

        binding.cbBold.setOnCheckedChangeListener { _, isChecked ->
            if (updating) return@setOnCheckedChangeListener
            val el = viewModel.selectedElement as? TemplateElement.TextElement ?: return@setOnCheckedChangeListener
            if (el.isBold == isChecked) return@setOnCheckedChangeListener
            viewModel.updateElement(el.copy(isBold = isChecked))
        }

        binding.sliderFontSize.addOnChangeListener { _, size, _ ->
            binding.tvFontSizeLabel.text = getString(R.string.prop_font_size) + ": ${size.toInt()}"
            if (!updating) {
                val el = viewModel.selectedElement as? TemplateElement.TextElement
                if (el != null && el.textSizeSp != size) {
                    val scaleFactor = size / el.textSizeSp.coerceAtLeast(0.1f)
                    val newW = el.width * scaleFactor
                    viewModel.scaleTextFontSize(el.id, size, newW)
                }
            }
        }

        binding.cbTextStroke.setOnCheckedChangeListener { _, isChecked ->
            if (updating) return@setOnCheckedChangeListener
            val el = viewModel.selectedElement as? TemplateElement.TextElement ?: return@setOnCheckedChangeListener
            val newStroke = if (isChecked) binding.sliderTextStroke.value else 0f
            if (el.textStrokeWidth != newStroke) {
                viewModel.updateElement(el.copy(textStrokeWidth = newStroke))
            }
        }

        binding.cpvStrokeColor.onColorSelected = { hex -> applyStrokeColor(hex) }
        binding.fieldStrokeColorHex.setOnClickListener { openStrokeColorHexDialog() }

        binding.sliderTextStroke.addOnChangeListener { _, size, _ ->
            binding.tvStrokeSizeLabel.text = getString(R.string.prop_text_stroke) + ": ${size.toInt()}"
            if (!updating && binding.cbTextStroke.isChecked) {
                val el = viewModel.selectedElement as? TemplateElement.TextElement
                if (el != null && el.textStrokeWidth != size) {
                    viewModel.updateElement(el.copy(textStrokeWidth = size))
                }
            }
        }

        binding.spinnerFont.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (updating) return
                val el = viewModel.selectedElement as? TemplateElement.TextElement ?: return
                val newFont = getAvailableFonts(requireContext())[pos].second
                if (el.fontName == newFont) return
                val updated = el.copy(fontName = newFont)
                viewModel.updateElement(updated)
                viewModel.resizeTextElementToFit(updated)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.toggleTextAlign.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updating) return@addOnButtonCheckedListener
            val el = viewModel.selectedElement as? TemplateElement.TextElement ?: return@addOnButtonCheckedListener
            val newAlign = when (checkedId) {
                R.id.btn_align_start -> TextAlign.START
                R.id.btn_align_end -> TextAlign.END
                else -> TextAlign.CENTER
            }
            if (el.textAlign != newAlign) viewModel.updateElement(el.copy(textAlign = newAlign))
        }
    }

    override fun onUiStateChanged(state: EditorUiState) {
        if (_binding == null) return
        val el = viewModel.currentElements
            .firstOrNull { it.id == state.selectedElementId }
            as? TemplateElement.TextElement ?: return
        populateFrom(el)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun openTextColorHexDialog() {
        val el = viewModel.selectedElement as? TemplateElement.TextElement ?: return
        ColorHexDialogSupport.showDialog(
            fragment = this,
            requestKey = REQ_TEXT_COLOR_HEX,
            dialogTag = TEXT_COLOR_DIALOG_TAG,
            initialHex = el.textColor,
            enableAlpha = binding.cpvTextColor.enableAlpha
        )
    }

    private fun openBgColorHexDialog() {
        val el = viewModel.selectedElement as? TemplateElement.TextElement ?: return
        ColorHexDialogSupport.showDialog(
            fragment = this,
            requestKey = REQ_BG_COLOR_HEX,
            dialogTag = BG_COLOR_DIALOG_TAG,
            initialHex = el.bgColor ?: binding.cpvBgColor.colorHex,
            enableAlpha = binding.cpvBgColor.enableAlpha
        )
    }

    private fun openStrokeColorHexDialog() {
        val el = viewModel.selectedElement as? TemplateElement.TextElement ?: return
        ColorHexDialogSupport.showDialog(
            fragment = this,
            requestKey = REQ_STROKE_COLOR_HEX,
            dialogTag = STROKE_COLOR_DIALOG_TAG,
            initialHex = el.textStrokeColor,
            enableAlpha = binding.cpvStrokeColor.enableAlpha
        )
    }

    private fun applyTextColor(hex: String) {
        binding.fieldTextColorHex.text = hex
        if (binding.cpvTextColor.colorHex != hex) binding.cpvTextColor.colorHex = hex
        val el = viewModel.selectedElement as? TemplateElement.TextElement
        if (el != null && el.textColor != hex) viewModel.updateElement(el.copy(textColor = hex))
    }

    private fun applyBgColor(hex: String) {
        binding.fieldBgColorHex.text = hex
        binding.btnClearBgColor.visibility = View.VISIBLE
        if (binding.cpvBgColor.colorHex != hex) binding.cpvBgColor.colorHex = hex
        val el = viewModel.selectedElement as? TemplateElement.TextElement
        if (el != null && el.bgColor != hex) viewModel.updateElement(el.copy(bgColor = hex))
    }

    private fun clearBgColor() {
        val el = viewModel.selectedElement as? TemplateElement.TextElement ?: return
        if (el.bgColor == null) return
        viewModel.updateElement(el.copy(bgColor = null))
        binding.cpvBgColor.colorHex = "#FFFFFFFF"
        binding.fieldBgColorHex.text = getString(R.string.prop_no_bg_color)
        binding.btnClearBgColor.visibility = View.GONE
    }

    private fun applyStrokeColor(hex: String) {
        binding.fieldStrokeColorHex.text = hex
        if (binding.cpvStrokeColor.colorHex != hex) binding.cpvStrokeColor.colorHex = hex
        val el = viewModel.selectedElement as? TemplateElement.TextElement
        if (el != null && el.textStrokeColor != hex) viewModel.updateElement(el.copy(textStrokeColor = hex))
    }

    companion object {
        private const val REQ_TEXT_COLOR_HEX = "req_text_color_hex"
        private const val REQ_BG_COLOR_HEX = "req_text_bg_color_hex"
        private const val REQ_STROKE_COLOR_HEX = "req_text_stroke_color_hex"
        private const val TEXT_COLOR_DIALOG_TAG = "text_color_hex_dialog"
        private const val BG_COLOR_DIALOG_TAG = "text_bg_color_hex_dialog"
        private const val STROKE_COLOR_DIALOG_TAG = "text_stroke_color_hex_dialog"
    }
}

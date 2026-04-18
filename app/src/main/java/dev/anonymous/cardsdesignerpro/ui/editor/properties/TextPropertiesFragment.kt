package dev.anonymous.cardsdesignerpro.ui.editor.properties

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropTextBinding
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
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
        setupFontSpinner()
        populateFrom(viewModel.selectedElement as? TemplateElement.TextElement ?: return)
        setupListeners()
    }

    private fun setupFontSpinner() {
        fontAdapter = FontSpinnerAdapter(requireContext(), getAvailableFonts(requireContext()))
        binding.spinnerFont.adapter = fontAdapter
    }

    private fun populateFrom(el: TemplateElement.TextElement) {
        updating = true
        // Text: only update if changed (setText triggers re-measure → scroll reset)
        val showText = if (el.text == getString(R.string.default_text_placeholder)) "" else el.text
        if (binding.etText.text.toString() != showText) {
            binding.etText.setText(showText)
            binding.etText.setSelection(showText.length)
        }
        // Text color
        if (binding.cpvTextColor.colorHex != el.textColor) binding.cpvTextColor.colorHex = el.textColor
        binding.tvTextColorHex.text = el.textColor

        // Background color — null means "no background"
        if (el.bgColor != null) {
            if (binding.cpvBgColor.colorHex != el.bgColor) binding.cpvBgColor.colorHex = el.bgColor
            binding.tvBgColorHex.text = el.bgColor
            binding.btnClearBgColor.visibility = View.VISIBLE
        } else {
            // Show opaque white in the picker (so alpha starts at 255 when user opens it)
            // but visually communicate "no color" via text.
            binding.cpvBgColor.colorHex = "#FFFFFFFF"
            binding.tvBgColorHex.text = getString(R.string.prop_no_bg_color)
            binding.btnClearBgColor.visibility = View.GONE
        }

        // Bold: guard to avoid spurious setOnCheckedChangeListener → requestLayout
        if (binding.cbBold.isChecked != el.isBold) binding.cbBold.isChecked = el.isBold

        // Slider: guard setValue (also fires listener which could loop)
        val targetSize = kotlin.math.round(el.textSizeSp).toFloat().coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
        if (binding.sliderFontSize.value != targetSize) {
            binding.sliderFontSize.value = targetSize
        }
        binding.tvFontSizeLabel.text = getString(R.string.prop_font_size) + ": ${targetSize.toInt()}"
        
        // Text stroke
        val hasStroke = el.textStrokeWidth > 0f
        if (binding.cbTextStroke.isChecked != hasStroke) binding.cbTextStroke.isChecked = hasStroke
        binding.layoutTextStrokeOptions.visibility = if (hasStroke) View.VISIBLE else View.GONE
        
        val targetStroke = el.textStrokeWidth.coerceAtLeast(1f).coerceAtMost(10f)
        if (binding.sliderTextStroke.value != targetStroke) {
            binding.sliderTextStroke.value = targetStroke
        }
        binding.tvStrokeSizeLabel.text = getString(R.string.prop_text_stroke) + ": ${targetStroke.toInt()}"
        if (binding.cpvStrokeColor.colorHex != el.textStrokeColor) binding.cpvStrokeColor.colorHex = el.textStrokeColor
        binding.tvStrokeColorHex.text = el.textStrokeColor

        // Update Font Adapter preview
        val previewStr = if (showText.isBlank()) "نص تجريبي" else showText
        if (fontAdapter?.previewText != previewStr) {
            fontAdapter?.previewText = previewStr
            fontAdapter?.notifyDataSetChanged()
        }

        // Spinner: Spinner.setSelection() ALWAYS calls requestLayout() even for same pos.
        // Guard it so we only call setSelection when position truly changes → no scroll reset.
        val fontIdx = getAvailableFonts(requireContext()).indexOfFirst { it.second == el.fontName }.coerceAtLeast(0)
        if (binding.spinnerFont.selectedItemPosition != fontIdx) binding.spinnerFont.setSelection(fontIdx)

        updating = false
    }

    private fun setupListeners() {
        binding.sliderFontSize.valueFrom = MIN_TEXT_SIZE_SP
        binding.sliderFontSize.valueTo = MAX_TEXT_SIZE_SP

        binding.etText.doAfterTextChanged { text ->
            if (updating) return@doAfterTextChanged
            val el = viewModel.selectedElement as? TemplateElement.TextElement ?: return@doAfterTextChanged
            val newText = text.toString().ifEmpty { getString(R.string.default_text_placeholder) }
            if (el.text == newText) return@doAfterTextChanged   // no real change
            viewModel.updateElement(el.copy(text = newText))
        }
        binding.cpvTextColor.onColorSelected = { hex ->
            binding.tvTextColorHex.text = hex
            val el = viewModel.selectedElement as? TemplateElement.TextElement
            if (el != null && el.textColor != hex) viewModel.updateElement(el.copy(textColor = hex))
        }
        binding.cpvBgColor.onColorSelected = { hex ->
            binding.tvBgColorHex.text = hex
            val el = viewModel.selectedElement as? TemplateElement.TextElement
            if (el != null && el.bgColor != hex) viewModel.updateElement(el.copy(bgColor = hex))
        }
        binding.btnClearBgColor.setOnClickListener {
            val el = viewModel.selectedElement as? TemplateElement.TextElement ?: return@setOnClickListener
            viewModel.updateElement(el.copy(bgColor = null))
        }
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
                if (el != null && el.textSizeSp != size)
                    viewModel.updateElement(el.copy(textSizeSp = size))
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
        binding.cpvStrokeColor.onColorSelected = { hex ->
            binding.tvStrokeColorHex.text = hex
            val el = viewModel.selectedElement as? TemplateElement.TextElement
            if (el != null && el.textStrokeColor != hex) viewModel.updateElement(el.copy(textStrokeColor = hex))
        }
        binding.sliderTextStroke.addOnChangeListener { _, size, _ ->
            binding.tvStrokeSizeLabel.text = getString(R.string.prop_text_stroke) + ": ${size.toInt()}"
            if (!updating && binding.cbTextStroke.isChecked) {
                val el = viewModel.selectedElement as? TemplateElement.TextElement
                if (el != null && el.textStrokeWidth != size)
                    viewModel.updateElement(el.copy(textStrokeWidth = size))
            }
        }
        binding.spinnerFont.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (updating) return
                val el = viewModel.selectedElement as? TemplateElement.TextElement ?: return
                val newFont = getAvailableFonts(requireContext())[pos].second
                if (el.fontName == newFont) return   // spurious fire from setSelection()
                viewModel.updateElement(el.copy(fontName = newFont))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
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
}

package dev.anonymous.cardsdesignerpro.ui.editor.properties

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropCredentialBinding
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.ui.editor.EditorUiState
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel.Companion.MAX_TEXT_SIZE_SP
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel.Companion.MIN_TEXT_SIZE_SP

/** Shared base for Username and Password property fragments. */
abstract class CredentialPropertiesFragment : Fragment(), PropertyFragment {

    private var _binding: FragmentPropCredentialBinding? = null
    protected val binding get() = _binding!!
    val viewModel: EditorViewModel by activityViewModels()
    protected var updating = false
    private var fontAdapter: FontSpinnerAdapter? = null

    abstract fun getElement(): TemplateElement?
    abstract fun copyWithDigitCount(el: TemplateElement, count: Int): TemplateElement
    abstract fun copyWithColor(el: TemplateElement, hex: String): TemplateElement
    abstract fun copyWithBgColor(el: TemplateElement, hex: String?): TemplateElement
    abstract fun copyWithBold(el: TemplateElement, bold: Boolean): TemplateElement
    abstract fun copyWithFont(el: TemplateElement, font: String): TemplateElement
    abstract fun copyWithSize(el: TemplateElement, size: Float): TemplateElement
    abstract fun copyWithTextStroke(el: TemplateElement, stroke: Float): TemplateElement
    abstract fun copyWithTextStrokeColor(el: TemplateElement, hex: String): TemplateElement
    abstract fun digitCount(el: TemplateElement): Int
    abstract fun textColor(el: TemplateElement): String
    abstract fun bgColor(el: TemplateElement): String?
    abstract fun isBold(el: TemplateElement): Boolean
    abstract fun fontName(el: TemplateElement): String
    abstract fun textSize(el: TemplateElement): Float
    abstract fun textStroke(el: TemplateElement): Float
    abstract fun textStrokeColor(el: TemplateElement): String
    abstract fun isShortVariant(el: TemplateElement): Boolean

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPropCredentialBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.sliderFontSize.valueFrom = MIN_TEXT_SIZE_SP
        binding.sliderFontSize.valueTo = MAX_TEXT_SIZE_SP
        setupFontSpinner()
        getElement()?.let { populateFrom(it) }
        setupListeners()
    }

    private fun setupFontSpinner() {
        fontAdapter = FontSpinnerAdapter(requireContext(), getAvailableFonts(requireContext()))
        binding.spinnerFont.adapter = fontAdapter
    }

    fun populateFrom(el: TemplateElement) {
        updating = true
        val minDigits = if (el is TemplateElement.UsernameElement) {
            if (isShortVariant(el)) 3 else 4
        } else {
            3
        }
        
        val maxDigits = if (el is TemplateElement.UsernameElement) {
            if (isShortVariant(el)) 10 else 15
        } else {
            if (isShortVariant(el)) 6 else 9
        }
        
        binding.stepperDigitCount.minValue = minDigits
        binding.stepperDigitCount.maxValue = maxDigits
        
        val dc = digitCount(el).coerceIn(minDigits, maxDigits)
        if (binding.stepperDigitCount.value != dc) binding.stepperDigitCount.value = dc
        val tc = textColor(el)
        if (binding.cpvTextColor.colorHex != tc) binding.cpvTextColor.colorHex = tc
        binding.tvTextColorHex.text = tc
        // Background color
        val bg = bgColor(el)
        if (bg != null) {
            if (binding.cpvBgColor.colorHex != bg) binding.cpvBgColor.colorHex = bg
            binding.tvBgColorHex.text = bg
            binding.btnClearBgColor.visibility = View.VISIBLE
        } else {
            binding.cpvBgColor.colorHex = "#FFFFFFFF"
            binding.tvBgColorHex.text = getString(R.string.prop_no_bg_color)
            binding.btnClearBgColor.visibility = View.GONE
        }
        val bold = isBold(el)
        if (binding.cbBold.isChecked != bold) binding.cbBold.isChecked = bold
        val targetSize = kotlin.math.round(textSize(el)).coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
        if (binding.sliderFontSize.value != targetSize) {
            binding.sliderFontSize.value = targetSize
        }
        binding.tvFontSizeLabel.text = getString(R.string.prop_font_size) + ": ${targetSize.toInt()}"
        
        // Text stroke
        val hasStroke = textStroke(el) > 0f
        if (binding.cbTextStroke.isChecked != hasStroke) binding.cbTextStroke.isChecked = hasStroke
        binding.layoutTextStrokeOptions.visibility = if (hasStroke) View.VISIBLE else View.GONE
        
        val targetStroke = textStroke(el).coerceAtLeast(1f).coerceAtMost(10f)
        if (binding.sliderTextStroke.value != targetStroke) {
            binding.sliderTextStroke.value = targetStroke
        }
        binding.tvStrokeSizeLabel.text = getString(R.string.prop_text_stroke) + ": ${targetStroke.toInt()}"
        if (binding.cpvStrokeColor.colorHex != textStrokeColor(el)) binding.cpvStrokeColor.colorHex = textStrokeColor(el)
        binding.tvStrokeColorHex.text = textStrokeColor(el)

        // Update Font Adapter preview (Show actual dummy digits like in canvas)
        val previewStr = (1..dc).joinToString("") { (it % 10).toString() }
        if (fontAdapter?.previewText != previewStr) {
            fontAdapter?.previewText = previewStr
            fontAdapter?.notifyDataSetChanged()
        }

        val fontIdx = getAvailableFonts(requireContext()).indexOfFirst { it.second == fontName(el) }.coerceAtLeast(0)
        if (binding.spinnerFont.selectedItemPosition != fontIdx) binding.spinnerFont.setSelection(fontIdx)
        updating = false
    }

    private fun setupListeners() {
        binding.stepperDigitCount.onValueChanged = {
            if (!updating) getElement()?.let { el -> viewModel.updateElement(copyWithDigitCount(el, it)) }
        }
        binding.cpvTextColor.onColorSelected = { hex ->
            binding.tvTextColorHex.text = hex
            getElement()?.let { el -> viewModel.updateElement(copyWithColor(el, hex)) }
        }
        binding.cpvBgColor.onColorSelected = { hex ->
            binding.tvBgColorHex.text = hex
            getElement()?.let { el -> viewModel.updateElement(copyWithBgColor(el, hex)) }
        }
        binding.btnClearBgColor.setOnClickListener {
            binding.tvBgColorHex.text = "—"
            binding.cpvBgColor.colorHex = "#00000000"
            getElement()?.let { el -> viewModel.updateElement(copyWithBgColor(el, null)) }
        }
        binding.cbBold.setOnCheckedChangeListener { _, checked ->
            if (!updating) getElement()?.let { el -> viewModel.updateElement(copyWithBold(el, checked)) }
        }
        binding.sliderFontSize.addOnChangeListener { _, size, _ ->
            binding.tvFontSizeLabel.text = getString(R.string.prop_font_size) + ": ${size.toInt()}"
            if (!updating) getElement()?.let { el -> viewModel.updateElement(copyWithSize(el, size)) }
        }
        binding.cbTextStroke.setOnCheckedChangeListener { _, isChecked ->
            if (!updating) {
                getElement()?.let { el ->
                    val newStroke = if (isChecked) binding.sliderTextStroke.value else 0f
                    if (textStroke(el) != newStroke) viewModel.updateElement(copyWithTextStroke(el, newStroke))
                }
            }
        }
        binding.cpvStrokeColor.onColorSelected = { hex ->
            binding.tvStrokeColorHex.text = hex
            getElement()?.let { el -> viewModel.updateElement(copyWithTextStrokeColor(el, hex)) }
        }
        binding.sliderTextStroke.addOnChangeListener { _, size, _ ->
            binding.tvStrokeSizeLabel.text = getString(R.string.prop_text_stroke) + ": ${size.toInt()}"
            if (!updating && binding.cbTextStroke.isChecked) getElement()?.let { el -> viewModel.updateElement(copyWithTextStroke(el, size)) }
        }
        binding.spinnerFont.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!updating) {
                    val newFont = getAvailableFonts(requireContext())[pos].second
                    getElement()?.let { el ->
                        if (fontName(el) == newFont) return   // spurious fire from setSelection()
                        viewModel.updateElement(copyWithFont(el, newFont))
                    }
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    override fun onUiStateChanged(state: EditorUiState) {
        if (_binding == null) return
        getElement()?.let { populateFrom(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class UsernamePropertiesFragment : CredentialPropertiesFragment() {
    override fun getElement() = viewModel.selectedElement as? TemplateElement.UsernameElement
    override fun copyWithDigitCount(el: TemplateElement, count: Int) = (el as TemplateElement.UsernameElement).copy(digitCount = count)
    override fun copyWithColor(el: TemplateElement, hex: String) = (el as TemplateElement.UsernameElement).copy(textColor = hex)
    override fun copyWithBgColor(el: TemplateElement, hex: String?) = (el as TemplateElement.UsernameElement).copy(bgColor = hex)
    override fun copyWithBold(el: TemplateElement, bold: Boolean) = (el as TemplateElement.UsernameElement).copy(isBold = bold)
    override fun copyWithFont(el: TemplateElement, font: String) = (el as TemplateElement.UsernameElement).copy(fontName = font)
    override fun copyWithSize(el: TemplateElement, size: Float) = (el as TemplateElement.UsernameElement).copy(textSizeSp = size)
    override fun copyWithTextStroke(el: TemplateElement, stroke: Float) = (el as TemplateElement.UsernameElement).copy(textStrokeWidth = stroke)
    override fun copyWithTextStrokeColor(el: TemplateElement, hex: String) = (el as TemplateElement.UsernameElement).copy(textStrokeColor = hex)
    override fun digitCount(el: TemplateElement) = (el as TemplateElement.UsernameElement).digitCount
    override fun textColor(el: TemplateElement) = (el as TemplateElement.UsernameElement).textColor
    override fun bgColor(el: TemplateElement) = (el as TemplateElement.UsernameElement).bgColor
    override fun isBold(el: TemplateElement) = (el as TemplateElement.UsernameElement).isBold
    override fun fontName(el: TemplateElement) = (el as TemplateElement.UsernameElement).fontName
    override fun textSize(el: TemplateElement) = (el as TemplateElement.UsernameElement).textSizeSp
    override fun textStroke(el: TemplateElement) = (el as TemplateElement.UsernameElement).textStrokeWidth
    override fun textStrokeColor(el: TemplateElement) = (el as TemplateElement.UsernameElement).textStrokeColor
    override fun isShortVariant(el: TemplateElement) = (el as TemplateElement.UsernameElement).isShortVariant
}

class PasswordPropertiesFragment : CredentialPropertiesFragment() {
    override fun getElement() = viewModel.selectedElement as? TemplateElement.PasswordElement
    override fun copyWithDigitCount(el: TemplateElement, count: Int) = (el as TemplateElement.PasswordElement).copy(digitCount = count)
    override fun copyWithColor(el: TemplateElement, hex: String) = (el as TemplateElement.PasswordElement).copy(textColor = hex)
    override fun copyWithBgColor(el: TemplateElement, hex: String?) = (el as TemplateElement.PasswordElement).copy(bgColor = hex)
    override fun copyWithBold(el: TemplateElement, bold: Boolean) = (el as TemplateElement.PasswordElement).copy(isBold = bold)
    override fun copyWithFont(el: TemplateElement, font: String) = (el as TemplateElement.PasswordElement).copy(fontName = font)
    override fun copyWithSize(el: TemplateElement, size: Float) = (el as TemplateElement.PasswordElement).copy(textSizeSp = size)
    override fun copyWithTextStroke(el: TemplateElement, stroke: Float) = (el as TemplateElement.PasswordElement).copy(textStrokeWidth = stroke)
    override fun copyWithTextStrokeColor(el: TemplateElement, hex: String) = (el as TemplateElement.PasswordElement).copy(textStrokeColor = hex)
    override fun digitCount(el: TemplateElement) = (el as TemplateElement.PasswordElement).digitCount
    override fun textColor(el: TemplateElement) = (el as TemplateElement.PasswordElement).textColor
    override fun bgColor(el: TemplateElement) = (el as TemplateElement.PasswordElement).bgColor
    override fun isBold(el: TemplateElement) = (el as TemplateElement.PasswordElement).isBold
    override fun fontName(el: TemplateElement) = (el as TemplateElement.PasswordElement).fontName
    override fun textSize(el: TemplateElement) = (el as TemplateElement.PasswordElement).textSizeSp
    override fun textStroke(el: TemplateElement) = (el as TemplateElement.PasswordElement).textStrokeWidth
    override fun textStrokeColor(el: TemplateElement) = (el as TemplateElement.PasswordElement).textStrokeColor
    override fun isShortVariant(el: TemplateElement) = (el as TemplateElement.PasswordElement).isShortVariant
}

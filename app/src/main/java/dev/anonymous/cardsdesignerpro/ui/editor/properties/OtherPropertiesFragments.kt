package dev.anonymous.cardsdesignerpro.ui.editor.properties

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.data.model.DateFormat
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropDateBinding
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropFrameBinding
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropNoSelectionBinding
import dev.anonymous.cardsdesignerpro.ui.editor.EditorUiState
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel.Companion.MAX_TEXT_SIZE_SP
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel.Companion.MIN_TEXT_SIZE_SP

// ── Date Properties ──────────────────────────────────────────────────────────

class DatePropertiesFragment : Fragment(), PropertyFragment {
    private var _b: FragmentPropDateBinding? = null
    private val b get() = _b!!
    val viewModel: EditorViewModel by activityViewModels()
    private var updating = false
    private var fontAdapter: FontSpinnerAdapter? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentPropDateBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.sliderFontSize.valueFrom = MIN_TEXT_SIZE_SP
        b.sliderFontSize.valueTo = MAX_TEXT_SIZE_SP
        val formats = DateFormat.values()
        b.spinnerDateFormat.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item, formats.map { it.displayName })
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        fontAdapter = FontSpinnerAdapter(requireContext(), getAvailableFonts(requireContext()))
        b.spinnerFont.adapter = fontAdapter

        val el = viewModel.selectedElement as? TemplateElement.DateElement ?: return
        populate(el, formats)

        b.spinnerDateFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updating) return
                val e = viewModel.selectedElement as? TemplateElement.DateElement ?: return
                if (e.format == formats[pos]) return   // spurious fire
                viewModel.updateElement(e.copy(format = formats[pos]))
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        b.cpvTextColor.onColorSelected = { hex ->
            b.tvColorHex.text = hex
            val e = viewModel.selectedElement as? TemplateElement.DateElement
            if (e != null) viewModel.updateElement(e.copy(textColor = hex))
        }
        // Background color
        b.cpvBgColor.onColorSelected = { hex ->
            b.tvBgColorHex.text = hex
            val e = viewModel.selectedElement as? TemplateElement.DateElement
            if (e != null) viewModel.updateElement(e.copy(bgColor = hex))
        }
        b.btnClearBgColor.setOnClickListener {
            b.tvBgColorHex.text = "—"
            b.cpvBgColor.colorHex = "#00000000"
            val e = viewModel.selectedElement as? TemplateElement.DateElement
            if (e != null) viewModel.updateElement(e.copy(bgColor = null))
        }
        b.cbBold.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                val e = viewModel.selectedElement as? TemplateElement.DateElement
                    ?: return@setOnCheckedChangeListener
                viewModel.updateElement(e.copy(isBold = checked))
            }
        }
        b.sliderFontSize.addOnChangeListener { _, v, _ ->
            if (!updating) {
                val e = viewModel.selectedElement as? TemplateElement.DateElement
                if (e != null && e.textSizeSp != v) viewModel.updateElement(e.copy(textSizeSp = v))
            }
        }
        b.cbTextStroke.setOnCheckedChangeListener { _, isChecked ->
            if (updating) return@setOnCheckedChangeListener
            val e = viewModel.selectedElement as? TemplateElement.DateElement ?: return@setOnCheckedChangeListener
            val newStroke = if (isChecked) b.sliderTextStroke.value else 0f
            if (e.textStrokeWidth != newStroke) viewModel.updateElement(e.copy(textStrokeWidth = newStroke))
        }
        b.cpvStrokeColor.onColorSelected = { hex ->
            b.tvStrokeColorHex.text = hex
            val e = viewModel.selectedElement as? TemplateElement.DateElement
            if (e != null && e.textStrokeColor != hex) viewModel.updateElement(e.copy(textStrokeColor = hex))
        }
        b.sliderTextStroke.addOnChangeListener { _, v, _ ->
            if (!updating && b.cbTextStroke.isChecked) {
                val e = viewModel.selectedElement as? TemplateElement.DateElement
                if (e != null && e.textStrokeWidth != v) viewModel.updateElement(e.copy(textStrokeWidth = v))
            }
        }
        b.spinnerFont.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updating) return
                val e = viewModel.selectedElement as? TemplateElement.DateElement ?: return
                val newFont = getAvailableFonts(requireContext())[pos].second
                if (e.fontName == newFont) return   // spurious fire
                viewModel.updateElement(e.copy(fontName = newFont))
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun populate(e: TemplateElement.DateElement, formats: Array<DateFormat>) {
        updating = true
        val fIdx = formats.indexOf(e.format).coerceAtLeast(0)
        if (b.spinnerDateFormat.selectedItemPosition != fIdx) b.spinnerDateFormat.setSelection(fIdx)
        if (b.cpvTextColor.colorHex != e.textColor) b.cpvTextColor.colorHex = e.textColor
        b.tvColorHex.text = e.textColor
        if (e.bgColor != null) {
            if (b.cpvBgColor.colorHex != e.bgColor) b.cpvBgColor.colorHex = e.bgColor
            b.tvBgColorHex.text = e.bgColor
            b.btnClearBgColor.visibility = View.VISIBLE
        } else {
            b.cpvBgColor.colorHex = "#FFFFFFFF"
            b.tvBgColorHex.text = getString(R.string.prop_no_bg_color)
            b.btnClearBgColor.visibility = View.GONE
        }
        if (b.cbBold.isChecked != e.isBold) b.cbBold.isChecked = e.isBold
        val s = kotlin.math.round(e.textSizeSp).toFloat().coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
        if (b.sliderFontSize.value != s) b.sliderFontSize.value = s
        
        // Text stroke
        val hasStroke = e.textStrokeWidth > 0f
        if (b.cbTextStroke.isChecked != hasStroke) b.cbTextStroke.isChecked = hasStroke
        b.layoutTextStrokeOptions.visibility = if (hasStroke) View.VISIBLE else View.GONE
        
        val targetStroke = e.textStrokeWidth.coerceAtLeast(1f).coerceAtMost(10f)
        if (b.sliderTextStroke.value != targetStroke) b.sliderTextStroke.value = targetStroke
        if (b.cpvStrokeColor.colorHex != e.textStrokeColor) b.cpvStrokeColor.colorHex = e.textStrokeColor
        b.tvStrokeColorHex.text = e.textStrokeColor

        // Update Font Adapter preview
        val previewStr = try {
            java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern(e.format.pattern))
        } catch (ex: Exception) {
            "معاينة التاريخ"
        }
        if (fontAdapter?.previewText != previewStr) {
            fontAdapter?.previewText = previewStr
            fontAdapter?.notifyDataSetChanged()
        }

        val fontIdx = getAvailableFonts(requireContext()).indexOfFirst { it.second == e.fontName }.coerceAtLeast(0)
        if (b.spinnerFont.selectedItemPosition != fontIdx) b.spinnerFont.setSelection(fontIdx)
        updating = false
    }

    override fun onUiStateChanged(state: EditorUiState) {
        if (_b == null) return
        val el = viewModel.currentElements.firstOrNull { it.id == state.selectedElementId }
                as? TemplateElement.DateElement ?: return
        populate(
            el, DateFormat.values()
        )
    }

    override fun onDestroyView() {
        super.onDestroyView(); _b = null
    }
}

// ── Frame Properties ─────────────────────────────────────────────────────────

class FramePropertiesFragment : Fragment(), PropertyFragment {
    private var _b: FragmentPropFrameBinding? = null
    private val b get() = _b!!
    val viewModel: EditorViewModel by activityViewModels()
    private var updating = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentPropFrameBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val el = viewModel.selectedElement as? TemplateElement.FrameElement ?: return
        populate(el)

        b.cpvFrameColor.onColorSelected = { hex ->
            b.tvColorHex.text = hex
            update { it.copy(color = hex) }
        }
        b.stepperThickness.onValueChanged =
            { update { el2 -> el2.copy(strokeWidthDp = it.toFloat()) } }
        b.sliderCornerRadius.addOnChangeListener { _, value, _ -> update { it.copy(cornerRadiusDp = value) } }
        b.sliderPadding.addOnChangeListener { _, value, _ -> update { it.copy(paddingDp = value) } }
        b.cbDashed.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                update { it.copy(isDashed = checked) }
                b.layoutDashOptions.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }
        b.sliderDashLength.addOnChangeListener { _, value, _ -> update { it.copy(dashLengthDp = value) } }
        b.sliderDashGap.addOnChangeListener { _, value, _ -> update { it.copy(dashGapDp = value) } }
        b.cbDashRounded.setOnCheckedChangeListener { _, checked ->
            if (!updating) update { it.copy(isDashRounded = checked) }
        }
    }

    private fun populate(el: TemplateElement.FrameElement) {
        updating = true
        if (b.cpvFrameColor.colorHex != el.color) b.cpvFrameColor.colorHex = el.color
        b.tvColorHex.text = el.color
        val thickness = el.strokeWidthDp.toInt()
        if (b.stepperThickness.value != thickness) {
            b.stepperThickness.minValue = 1; b.stepperThickness.maxValue = 20
            b.stepperThickness.value = thickness
        } else {
            b.stepperThickness.minValue = 1; b.stepperThickness.maxValue = 20
        }
        val corner = el.cornerRadiusDp.coerceIn(0f, 100f)
        if (b.sliderCornerRadius.value != corner) b.sliderCornerRadius.value = corner
        val padding = el.paddingDp.coerceIn(0f, 60f)
        if (b.sliderPadding.value != padding) b.sliderPadding.value = padding
        // Checkbox: guard to prevent requestLayout from firing when value same
        if (b.cbDashed.isChecked != el.isDashed) {
            b.cbDashed.isChecked = el.isDashed
            b.layoutDashOptions.visibility = if (el.isDashed) View.VISIBLE else View.GONE
        }
        val dashLen = el.dashLengthDp.coerceIn(2f, 60f)
        if (b.sliderDashLength.value != dashLen) b.sliderDashLength.value = dashLen
        val dashGap = el.dashGapDp.coerceIn(0f, 40f)
        if (b.sliderDashGap.value != dashGap) b.sliderDashGap.value = dashGap
        if (b.cbDashRounded.isChecked != el.isDashRounded) b.cbDashRounded.isChecked =
            el.isDashRounded
        updating = false
    }

    private fun update(transform: (TemplateElement.FrameElement) -> TemplateElement.FrameElement) {
        if (updating) return
        val el = viewModel.selectedElement as? TemplateElement.FrameElement ?: return
        viewModel.updateElement(transform(el))
    }

    override fun onUiStateChanged(state: EditorUiState) {
        if (_b == null) return
        val el = state.template.elements.firstOrNull { it.id == state.selectedElementId }
                as? TemplateElement.FrameElement ?: return
        populate(el)
    }

    override fun onDestroyView() {
        super.onDestroyView(); _b = null
    }
}

// ── No Selection ──────────────────────────────────────────────────────────────

class NoSelectionFragment : Fragment(), PropertyFragment {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        FragmentPropNoSelectionBinding.inflate(i, c, false).root

    override fun onUiStateChanged(state: EditorUiState) {}
}

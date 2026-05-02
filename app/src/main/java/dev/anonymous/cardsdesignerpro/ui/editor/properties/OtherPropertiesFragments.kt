package dev.anonymous.cardsdesignerpro.ui.editor.properties

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.data.model.DateFormat
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropDateBinding
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropFrameBinding
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropNoSelectionBinding
import dev.anonymous.cardsdesignerpro.ui.common.ColorHexDialogSupport
import dev.anonymous.cardsdesignerpro.ui.editor.EditorUiState
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel.Companion.MAX_TEXT_SIZE_SP
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel.Companion.MIN_TEXT_SIZE_SP

class DatePropertiesFragment : Fragment(), PropertyFragment {
    private var _b: FragmentPropDateBinding? = null
    private val b get() = _b!!
    val viewModel: EditorViewModel by activityViewModels()
    private var updating = false
    private var fontAdapter: FontSpinnerAdapter? = null

    private val fontPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        importCustomFont(uri)
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentPropDateBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ColorHexDialogSupport.registerResultListener(
            fragment = this,
            owner = viewLifecycleOwner,
            requestKey = REQ_DATE_TEXT_COLOR_HEX
        ) { hex ->
            applyTextColor(hex)
        }
        ColorHexDialogSupport.registerResultListener(
            fragment = this,
            owner = viewLifecycleOwner,
            requestKey = REQ_DATE_BG_COLOR_HEX
        ) { hex ->
            applyBgColor(hex)
        }
        ColorHexDialogSupport.registerResultListener(
            fragment = this,
            owner = viewLifecycleOwner,
            requestKey = REQ_DATE_STROKE_COLOR_HEX
        ) { hex ->
            applyStrokeColor(hex)
        }

        b.sliderFontSize.valueFrom = MIN_TEXT_SIZE_SP
        b.sliderFontSize.valueTo = MAX_TEXT_SIZE_SP
        val formats = DateFormat.entries.toTypedArray()
        b.spinnerDateFormat.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            formats.map { getString(it.displayNameRes) }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        rebuildFontAdapter()
        b.spinnerFont.adapter = fontAdapter

        val el = viewModel.selectedElement as? TemplateElement.DateElement ?: return
        populate(el, formats)

        b.spinnerDateFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updating) return
                val e = viewModel.selectedElement as? TemplateElement.DateElement ?: return
                if (e.format == formats[pos]) return
                viewModel.updateElement(e.copy(format = formats[pos]))
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        b.cpvTextColor.onColorSelected = { hex -> applyTextColor(hex) }
        b.fieldTextColorHex.setOnClickListener { openTextColorHexDialog() }

        b.cpvBgColor.onColorSelected = { hex -> applyBgColor(hex) }
        b.fieldBgColorHex.setOnClickListener { openBgColorHexDialog() }
        b.btnClearBgColor.setOnClickListener { clearBgColor() }

        b.cbBold.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                val e = viewModel.selectedElement as? TemplateElement.DateElement ?: return@setOnCheckedChangeListener
                viewModel.updateElement(e.copy(isBold = checked))
            }
        }
        b.sliderFontSize.addOnChangeListener { _, v, _ ->
            b.tvFontSizeLabel.text = getString(R.string.prop_font_size) + ": ${v.toInt()}"
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
        b.cpvStrokeColor.onColorSelected = { hex -> applyStrokeColor(hex) }
        b.fieldStrokeColorHex.setOnClickListener { openStrokeColorHexDialog() }

        b.sliderTextStroke.addOnChangeListener { _, v, _ ->
            b.tvStrokeSizeLabel.text = getString(R.string.prop_text_stroke) + ": ${v.toInt()}"
            if (!updating && b.cbTextStroke.isChecked) {
                val e = viewModel.selectedElement as? TemplateElement.DateElement
                if (e != null && e.textStrokeWidth != v) viewModel.updateElement(e.copy(textStrokeWidth = v))
            }
        }
        b.spinnerFont.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updating) return
                val allFonts = getAvailableFonts(requireContext(), viewModel.getCustomFontsForCurrentTemplate())
                val newFont = allFonts[pos].second
                if (newFont == PICK_CUSTOM_FONT_SENTINEL) {
                    fontPickerLauncher.launch(arrayOf(
                        "font/*", "application/x-font-ttf",
                        "application/x-font-opentype", "application/octet-stream"
                    ))
                    val e = viewModel.selectedElement as? TemplateElement.DateElement
                    if (e != null) {
                        val idx = allFonts.indexOfFirst { it.second == e.fontName }.coerceAtLeast(0)
                        b.spinnerFont.setSelection(idx)
                    }
                    return
                }
                val e = viewModel.selectedElement as? TemplateElement.DateElement ?: return
                if (e.fontName == newFont) return
                viewModel.updateElement(e.copy(fontName = newFont))
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun rebuildFontAdapter(selectFontName: String? = null) {
        val oldPreview = fontAdapter?.previewText ?: "نص تجريبي"
        val customFonts = viewModel.getCustomFontsForCurrentTemplate()
        val fonts = getAvailableFonts(requireContext(), customFonts)
        fontAdapter = FontSpinnerAdapter(
            requireContext(), fonts,
            previewText = oldPreview,
            fontsDir = viewModel.getFontDirForCurrentTemplate()
        ).also { adapter ->
            adapter.onDeleteCustomFont = { fileName -> confirmDeleteFont(fileName) }
        }
        b.spinnerFont.adapter = fontAdapter
        if (selectFontName != null) {
            val idx = fonts.indexOfFirst { it.second == selectFontName }.coerceAtLeast(0)
            b.spinnerFont.setSelection(idx)
        }
    }

    private fun confirmDeleteFont(fileName: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.font_delete_confirm_title)
            .setMessage(R.string.font_delete_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.deleteCustomFont(fileName)
                rebuildFontAdapter()
                val e = viewModel.selectedElement as? TemplateElement.DateElement
                if (e != null) {
                    val allFonts = getAvailableFonts(requireContext(), viewModel.getCustomFontsForCurrentTemplate())
                    val idx = allFonts.indexOfFirst { it.second == e.fontName }.coerceAtLeast(0)
                    b.spinnerFont.setSelection(idx)
                }
            }
            .show()
    }

    private fun importCustomFont(uri: Uri) {
        val ctx = requireContext()
        val fontsDir = viewModel.ensureFontDirForCurrentTemplate()
        val displayName = ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIdx >= 0) cursor.getString(nameIdx) else null
        } ?: "custom_font_${System.currentTimeMillis()}.ttf"
        val ext = displayName.substringAfterLast('.', "ttf").lowercase()
        if (ext != "ttf" && ext != "otf") {
            android.widget.Toast.makeText(ctx, R.string.font_invalid_format, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val outFile = java.io.File(fontsDir, displayName)
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        val customKey = "custom:${outFile.name}"
        val e = viewModel.selectedElement as? TemplateElement.DateElement ?: return
        viewModel.updateElement(e.copy(fontName = customKey))
        rebuildFontAdapter(selectFontName = customKey)
    }

    private fun populate(e: TemplateElement.DateElement, formats: Array<DateFormat>) {
        updating = true
        val fIdx = formats.indexOf(e.format).coerceAtLeast(0)
        if (b.spinnerDateFormat.selectedItemPosition != fIdx) b.spinnerDateFormat.setSelection(fIdx)
        if (b.cpvTextColor.colorHex != e.textColor) b.cpvTextColor.colorHex = e.textColor
        b.fieldTextColorHex.text = e.textColor
        if (e.bgColor != null) {
            if (b.cpvBgColor.colorHex != e.bgColor) b.cpvBgColor.colorHex = e.bgColor
            b.fieldBgColorHex.text = e.bgColor
            b.btnClearBgColor.visibility = View.VISIBLE
        } else {
            b.cpvBgColor.colorHex = "#FFFFFFFF"
            b.fieldBgColorHex.text = getString(R.string.prop_no_bg_color)
            b.btnClearBgColor.visibility = View.GONE
        }
        if (b.cbBold.isChecked != e.isBold) b.cbBold.isChecked = e.isBold
        val s = kotlin.math.round(e.textSizeSp).coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
        if (b.sliderFontSize.value != s) b.sliderFontSize.value = s
        b.tvFontSizeLabel.text = getString(R.string.prop_font_size) + ": ${s.toInt()}"

        val hasStroke = e.textStrokeWidth > 0f
        if (b.cbTextStroke.isChecked != hasStroke) b.cbTextStroke.isChecked = hasStroke
        b.layoutTextStrokeOptions.visibility = if (hasStroke) View.VISIBLE else View.GONE

        val targetStroke = e.textStrokeWidth.coerceAtLeast(1f).coerceAtMost(10f)
        if (b.sliderTextStroke.value != targetStroke) b.sliderTextStroke.value = targetStroke
        b.tvStrokeSizeLabel.text = getString(R.string.prop_text_stroke) + ": ${targetStroke.toInt()}"
        if (b.cpvStrokeColor.colorHex != e.textStrokeColor) b.cpvStrokeColor.colorHex = e.textStrokeColor
        b.fieldStrokeColorHex.text = e.textStrokeColor

        val previewStr = try {
            java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern(e.format.pattern))
        } catch (_: Exception) {
            "معاينة التاريخ"
        }
        if (fontAdapter?.previewText != previewStr) {
            fontAdapter?.previewText = previewStr
            fontAdapter?.notifyDataSetChanged()
        }

        val allFonts = getAvailableFonts(requireContext(), viewModel.getCustomFontsForCurrentTemplate())
        val fontIdx = allFonts.indexOfFirst { it.second == e.fontName }.coerceAtLeast(0)
        if (b.spinnerFont.selectedItemPosition != fontIdx) b.spinnerFont.setSelection(fontIdx)
        updating = false
    }

    private fun openTextColorHexDialog() {
        val e = viewModel.selectedElement as? TemplateElement.DateElement ?: return
        ColorHexDialogSupport.showDialog(
            fragment = this,
            requestKey = REQ_DATE_TEXT_COLOR_HEX,
            dialogTag = DATE_TEXT_COLOR_DIALOG_TAG,
            initialHex = e.textColor,
            enableAlpha = b.cpvTextColor.enableAlpha
        )
    }

    private fun openBgColorHexDialog() {
        val e = viewModel.selectedElement as? TemplateElement.DateElement ?: return
        ColorHexDialogSupport.showDialog(
            fragment = this,
            requestKey = REQ_DATE_BG_COLOR_HEX,
            dialogTag = DATE_BG_COLOR_DIALOG_TAG,
            initialHex = e.bgColor ?: b.cpvBgColor.colorHex,
            enableAlpha = b.cpvBgColor.enableAlpha
        )
    }

    private fun openStrokeColorHexDialog() {
        val e = viewModel.selectedElement as? TemplateElement.DateElement ?: return
        ColorHexDialogSupport.showDialog(
            fragment = this,
            requestKey = REQ_DATE_STROKE_COLOR_HEX,
            dialogTag = DATE_STROKE_COLOR_DIALOG_TAG,
            initialHex = e.textStrokeColor,
            enableAlpha = b.cpvStrokeColor.enableAlpha
        )
    }

    private fun applyTextColor(hex: String) {
        b.fieldTextColorHex.text = hex
        if (b.cpvTextColor.colorHex != hex) b.cpvTextColor.colorHex = hex
        val e = viewModel.selectedElement as? TemplateElement.DateElement ?: return
        if (e.textColor != hex) viewModel.updateElement(e.copy(textColor = hex))
    }

    private fun applyBgColor(hex: String) {
        b.fieldBgColorHex.text = hex
        b.btnClearBgColor.visibility = View.VISIBLE
        if (b.cpvBgColor.colorHex != hex) b.cpvBgColor.colorHex = hex
        val e = viewModel.selectedElement as? TemplateElement.DateElement ?: return
        if (e.bgColor != hex) viewModel.updateElement(e.copy(bgColor = hex))
    }

    private fun clearBgColor() {
        val e = viewModel.selectedElement as? TemplateElement.DateElement ?: return
        if (e.bgColor == null) return
        viewModel.updateElement(e.copy(bgColor = null))
        b.cpvBgColor.colorHex = "#FFFFFFFF"
        b.fieldBgColorHex.text = getString(R.string.prop_no_bg_color)
        b.btnClearBgColor.visibility = View.GONE
    }

    private fun applyStrokeColor(hex: String) {
        b.fieldStrokeColorHex.text = hex
        if (b.cpvStrokeColor.colorHex != hex) b.cpvStrokeColor.colorHex = hex
        val e = viewModel.selectedElement as? TemplateElement.DateElement ?: return
        if (e.textStrokeColor != hex) viewModel.updateElement(e.copy(textStrokeColor = hex))
    }

    override fun onUiStateChanged(state: EditorUiState) {
        if (_b == null) return
        val el = viewModel.currentElements.firstOrNull { it.id == state.selectedElementId }
            as? TemplateElement.DateElement ?: return
        populate(el, DateFormat.entries.toTypedArray())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        private const val REQ_DATE_TEXT_COLOR_HEX = "req_date_text_color_hex"
        private const val REQ_DATE_BG_COLOR_HEX = "req_date_bg_color_hex"
        private const val REQ_DATE_STROKE_COLOR_HEX = "req_date_stroke_color_hex"
        private const val DATE_TEXT_COLOR_DIALOG_TAG = "date_text_color_hex_dialog"
        private const val DATE_BG_COLOR_DIALOG_TAG = "date_bg_color_hex_dialog"
        private const val DATE_STROKE_COLOR_DIALOG_TAG = "date_stroke_color_hex_dialog"
    }
}

class FramePropertiesFragment : Fragment(), PropertyFragment {
    private var _b: FragmentPropFrameBinding? = null
    private val b get() = _b!!
    val viewModel: EditorViewModel by activityViewModels()
    private var updating = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentPropFrameBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ColorHexDialogSupport.registerResultListener(
            fragment = this,
            owner = viewLifecycleOwner,
            requestKey = REQ_FRAME_COLOR_HEX
        ) { hex ->
            applyFrameColor(hex)
        }

        val el = viewModel.selectedElement as? TemplateElement.FrameElement ?: return
        populate(el)

        b.cpvFrameColor.onColorSelected = { hex -> applyFrameColor(hex) }
        b.fieldFrameColorHex.setOnClickListener { openFrameColorHexDialog() }

        b.stepperThickness.onValueChanged = { update { el2 -> el2.copy(strokeWidthDp = it.toFloat()) } }
        b.sliderCornerRadius.addOnChangeListener { _, value, _ ->
            b.tvCornerRadiusLabel.text = getString(R.string.prop_frame_corner_radius) + ": ${value.toInt()}"
            update { it.copy(cornerRadiusDp = value) }
        }
        b.sliderPadding.addOnChangeListener { _, value, _ ->
            b.tvPaddingLabel.text = getString(R.string.prop_frame_padding) + ": ${value.toInt()}"
            update { it.copy(paddingDp = value) }
        }
        b.cbDashed.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                update { it.copy(isDashed = checked) }
                b.layoutDashOptions.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }
        b.sliderDashLength.addOnChangeListener { _, value, _ ->
            b.tvDashLengthLabel.text = getString(R.string.prop_frame_dash_length) + ": ${value.toInt()}"
            update { it.copy(dashLengthDp = value) }
        }
        b.sliderDashGap.addOnChangeListener { _, value, _ ->
            b.tvDashGapLabel.text = getString(R.string.prop_frame_dash_gap) + ": ${value.toInt()}"
            update { it.copy(dashGapDp = value) }
        }
        b.cbDashRounded.setOnCheckedChangeListener { _, checked ->
            if (!updating) update { it.copy(isDashRounded = checked) }
        }
    }

    private fun populate(el: TemplateElement.FrameElement) {
        updating = true
        if (b.cpvFrameColor.colorHex != el.color) b.cpvFrameColor.colorHex = el.color
        b.fieldFrameColorHex.text = el.color
        val thickness = el.strokeWidthDp.toInt()
        if (b.stepperThickness.value != thickness) {
            b.stepperThickness.minValue = 1
            b.stepperThickness.maxValue = 20
            b.stepperThickness.value = thickness
        } else {
            b.stepperThickness.minValue = 1
            b.stepperThickness.maxValue = 20
        }
        val corner = el.cornerRadiusDp.coerceIn(0f, 100f)
        if (b.sliderCornerRadius.value != corner) b.sliderCornerRadius.value = corner
        b.tvCornerRadiusLabel.text = getString(R.string.prop_frame_corner_radius) + ": ${corner.toInt()}"

        val padding = el.paddingDp.coerceIn(0f, 60f)
        if (b.sliderPadding.value != padding) b.sliderPadding.value = padding
        b.tvPaddingLabel.text = getString(R.string.prop_frame_padding) + ": ${padding.toInt()}"

        if (b.cbDashed.isChecked != el.isDashed) {
            b.cbDashed.isChecked = el.isDashed
            b.layoutDashOptions.visibility = if (el.isDashed) View.VISIBLE else View.GONE
        }
        val dashLen = el.dashLengthDp.coerceIn(2f, 60f)
        if (b.sliderDashLength.value != dashLen) b.sliderDashLength.value = dashLen
        b.tvDashLengthLabel.text = getString(R.string.prop_frame_dash_length) + ": ${dashLen.toInt()}"

        val dashGap = el.dashGapDp.coerceIn(0f, 40f)
        if (b.sliderDashGap.value != dashGap) b.sliderDashGap.value = dashGap
        b.tvDashGapLabel.text = getString(R.string.prop_frame_dash_gap) + ": ${dashGap.toInt()}"
        if (b.cbDashRounded.isChecked != el.isDashRounded) b.cbDashRounded.isChecked = el.isDashRounded
        updating = false
    }

    private fun openFrameColorHexDialog() {
        val el = viewModel.selectedElement as? TemplateElement.FrameElement ?: return
        ColorHexDialogSupport.showDialog(
            fragment = this,
            requestKey = REQ_FRAME_COLOR_HEX,
            dialogTag = FRAME_COLOR_DIALOG_TAG,
            initialHex = el.color,
            enableAlpha = b.cpvFrameColor.enableAlpha
        )
    }

    private fun applyFrameColor(hex: String) {
        b.fieldFrameColorHex.text = hex
        if (b.cpvFrameColor.colorHex != hex) b.cpvFrameColor.colorHex = hex
        update { it.copy(color = hex) }
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
        super.onDestroyView()
        _b = null
    }

    companion object {
        private const val REQ_FRAME_COLOR_HEX = "req_frame_color_hex"
        private const val FRAME_COLOR_DIALOG_TAG = "frame_color_hex_dialog"
    }
}

class NoSelectionFragment : Fragment(), PropertyFragment {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        FragmentPropNoSelectionBinding.inflate(i, c, false).root

    override fun onUiStateChanged(state: EditorUiState) {}
}

package dev.anonymous.cardsdesignerpro.app.ui.editor.properties

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dev.anonymous.cardsdesignerpro.app.R
import dev.anonymous.cardsdesignerpro.app.data.model.CredentialMode
import dev.anonymous.cardsdesignerpro.app.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.app.databinding.FragmentPropCredentialBinding
import dev.anonymous.cardsdesignerpro.app.ui.common.ColorHexDialogSupport
import dev.anonymous.cardsdesignerpro.app.ui.editor.EditorUiState
import dev.anonymous.cardsdesignerpro.app.ui.editor.EditorViewModel
import dev.anonymous.cardsdesignerpro.app.ui.editor.EditorViewModel.Companion.MAX_TEXT_SIZE_SP
import dev.anonymous.cardsdesignerpro.app.ui.editor.EditorViewModel.Companion.MIN_TEXT_SIZE_SP

/** Shared base for Username and Password property fragments. */
abstract class CredentialPropertiesFragment : Fragment(), PropertyFragment {

    private var _binding: FragmentPropCredentialBinding? = null
    protected val binding get() = _binding!!
    val viewModel: EditorViewModel by activityViewModels()
    protected var updating = false
    private var fontAdapter: FontSpinnerAdapter? = null

    private val fontPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        importCustomFont(uri)
    }

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
        setupColorHexDialogResultListeners()
        binding.sliderFontSize.valueFrom = MIN_TEXT_SIZE_SP
        binding.sliderFontSize.valueTo = MAX_TEXT_SIZE_SP
        setupFontSpinner()
        getElement()?.let { populateFrom(it) }
        setupListeners()
    }

    private fun setupColorHexDialogResultListeners() {
        ColorHexDialogSupport.registerResultListener(
            fragment = this,
            owner = viewLifecycleOwner,
            requestKey = REQ_CREDENTIAL_TEXT_COLOR_HEX
        ) { hex ->
            applyTextColor(hex)
        }
        ColorHexDialogSupport.registerResultListener(
            fragment = this,
            owner = viewLifecycleOwner,
            requestKey = REQ_CREDENTIAL_BG_COLOR_HEX
        ) { hex ->
            applyBgColor(hex)
        }
        ColorHexDialogSupport.registerResultListener(
            fragment = this,
            owner = viewLifecycleOwner,
            requestKey = REQ_CREDENTIAL_STROKE_COLOR_HEX
        ) { hex ->
            applyStrokeColor(hex)
        }
    }

    private fun setupFontSpinner() {
        rebuildFontAdapter()
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
        binding.spinnerFont.adapter = fontAdapter
        if (selectFontName != null) {
            val idx = fonts.indexOfFirst { it.second == selectFontName }.coerceAtLeast(0)
            binding.spinnerFont.setSelection(idx)
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
                getElement()?.let { el ->
                    val allFonts = getAvailableFonts(requireContext(), viewModel.getCustomFontsForCurrentTemplate())
                    val idx = allFonts.indexOfFirst { it.second == fontName(el) }.coerceAtLeast(0)
                    binding.spinnerFont.setSelection(idx)
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
        getElement()?.let { el ->
            viewModel.updateElement(copyWithFont(el, customKey))
        }
        rebuildFontAdapter(selectFontName = customKey)
    }

    fun populateFrom(el: TemplateElement) {
        updating = true

        // Hide the link-styles switch in USERNAME_ONLY mode (no password to link with)
        val isUsernameOnly = viewModel.currentTemplate.credentialMode == CredentialMode.USERNAME_ONLY
        binding.switchLinkStyles.visibility = if (isUsernameOnly) View.GONE else View.VISIBLE

        val isLinked = viewModel.activeCardStyle.linkCredentialsStyle
        if (binding.switchLinkStyles.isChecked != isLinked) binding.switchLinkStyles.isChecked = isLinked

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
        binding.fieldTextColorHex.text = tc

        val bg = bgColor(el)
        if (bg != null) {
            if (binding.cpvBgColor.colorHex != bg) binding.cpvBgColor.colorHex = bg
            binding.fieldBgColorHex.text = bg
            binding.btnClearBgColor.visibility = View.VISIBLE
        } else {
            binding.cpvBgColor.colorHex = "#FFFFFFFF"
            binding.fieldBgColorHex.text = getString(R.string.prop_no_bg_color)
            binding.btnClearBgColor.visibility = View.GONE
        }

        val bold = isBold(el)
        if (binding.cbBold.isChecked != bold) binding.cbBold.isChecked = bold

        val targetSize = kotlin.math.round(textSize(el)).coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
        if (binding.sliderFontSize.value != targetSize) {
            binding.sliderFontSize.value = targetSize
        }
        binding.tvFontSizeLabel.text = getString(R.string.prop_font_size) + ": ${targetSize.toInt()}"

        val hasStroke = textStroke(el) > 0f
        if (binding.cbTextStroke.isChecked != hasStroke) binding.cbTextStroke.isChecked = hasStroke
        binding.layoutTextStrokeOptions.visibility = if (hasStroke) View.VISIBLE else View.GONE

        val targetStroke = textStroke(el).coerceAtLeast(1f).coerceAtMost(10f)
        if (binding.sliderTextStroke.value != targetStroke) {
            binding.sliderTextStroke.value = targetStroke
        }
        binding.tvStrokeSizeLabel.text = getString(R.string.prop_text_stroke) + ": ${targetStroke.toInt()}"
        val strokeColor = textStrokeColor(el)
        if (binding.cpvStrokeColor.colorHex != strokeColor) binding.cpvStrokeColor.colorHex = strokeColor
        binding.fieldStrokeColorHex.text = strokeColor

        val previewStr = (1..dc).joinToString("") { (it % 10).toString() }
        if (fontAdapter?.previewText != previewStr) {
            fontAdapter?.previewText = previewStr
            fontAdapter?.notifyDataSetChanged()
        }

        val allFonts = getAvailableFonts(requireContext(), viewModel.getCustomFontsForCurrentTemplate())
        val fontIdx = allFonts.indexOfFirst { it.second == fontName(el) }.coerceAtLeast(0)
        if (binding.spinnerFont.selectedItemPosition != fontIdx) binding.spinnerFont.setSelection(fontIdx)

        updating = false
    }

    private fun setupListeners() {
        binding.switchLinkStyles.setOnCheckedChangeListener { _, isChecked ->
            if (!updating) viewModel.setLinkCredentialsStyle(isChecked)
        }

        binding.stepperDigitCount.onValueChanged = {
            if (!updating) getElement()?.let { el -> viewModel.updateElement(copyWithDigitCount(el, it)) }
        }

        binding.cpvTextColor.onColorSelected = { hex -> applyTextColor(hex) }
        binding.fieldTextColorHex.setOnClickListener { openTextColorHexDialog() }

        binding.cpvBgColor.onColorSelected = { hex -> applyBgColor(hex) }
        binding.fieldBgColorHex.setOnClickListener { openBgColorHexDialog() }
        binding.btnClearBgColor.setOnClickListener { clearBgColor() }

        binding.cbBold.setOnCheckedChangeListener { _, checked ->
            if (!updating) getElement()?.let { el -> viewModel.updateElement(copyWithBold(el, checked)) }
        }

        binding.sliderFontSize.addOnChangeListener { _, size, _ ->
            binding.tvFontSizeLabel.text = getString(R.string.prop_font_size) + ": ${size.toInt()}"
            if (!updating) getElement()?.let { el -> viewModel.updateElement(copyWithSize(el, size)) }
        }

        binding.cbTextStroke.setOnCheckedChangeListener { _, isChecked ->
            if (updating) return@setOnCheckedChangeListener
            getElement()?.let { el ->
                val newStroke = if (isChecked) binding.sliderTextStroke.value else 0f
                if (textStroke(el) != newStroke) viewModel.updateElement(copyWithTextStroke(el, newStroke))
            }
        }

        binding.cpvStrokeColor.onColorSelected = { hex -> applyStrokeColor(hex) }
        binding.fieldStrokeColorHex.setOnClickListener { openStrokeColorHexDialog() }

        binding.sliderTextStroke.addOnChangeListener { _, size, _ ->
            binding.tvStrokeSizeLabel.text = getString(R.string.prop_text_stroke) + ": ${size.toInt()}"
            if (!updating && binding.cbTextStroke.isChecked) {
                getElement()?.let { el -> viewModel.updateElement(copyWithTextStroke(el, size)) }
            }
        }

        binding.spinnerFont.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updating) return
                val allFonts = getAvailableFonts(requireContext(), viewModel.getCustomFontsForCurrentTemplate())
                val newFont = allFonts[pos].second
                if (newFont == PICK_CUSTOM_FONT_SENTINEL) {
                    fontPickerLauncher.launch(arrayOf(
                        "font/*", "application/x-font-ttf",
                        "application/x-font-opentype", "application/octet-stream"
                    ))
                    getElement()?.let { el ->
                        val idx = allFonts.indexOfFirst { it.second == fontName(el) }.coerceAtLeast(0)
                        binding.spinnerFont.setSelection(idx)
                    }
                    return
                }
                getElement()?.let { el ->
                    if (fontName(el) == newFont) return
                    viewModel.updateElement(copyWithFont(el, newFont))
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

    private fun openTextColorHexDialog() {
        val el = getElement() ?: return
        ColorHexDialogSupport.showDialog(
            fragment = this,
            requestKey = REQ_CREDENTIAL_TEXT_COLOR_HEX,
            dialogTag = CREDENTIAL_TEXT_COLOR_DIALOG_TAG,
            initialHex = textColor(el),
            enableAlpha = binding.cpvTextColor.enableAlpha
        )
    }

    private fun openBgColorHexDialog() {
        val el = getElement() ?: return
        ColorHexDialogSupport.showDialog(
            fragment = this,
            requestKey = REQ_CREDENTIAL_BG_COLOR_HEX,
            dialogTag = CREDENTIAL_BG_COLOR_DIALOG_TAG,
            initialHex = bgColor(el) ?: binding.cpvBgColor.colorHex,
            enableAlpha = binding.cpvBgColor.enableAlpha
        )
    }

    private fun openStrokeColorHexDialog() {
        val el = getElement() ?: return
        ColorHexDialogSupport.showDialog(
            fragment = this,
            requestKey = REQ_CREDENTIAL_STROKE_COLOR_HEX,
            dialogTag = CREDENTIAL_STROKE_COLOR_DIALOG_TAG,
            initialHex = textStrokeColor(el),
            enableAlpha = binding.cpvStrokeColor.enableAlpha
        )
    }

    private fun applyTextColor(hex: String) {
        binding.fieldTextColorHex.text = hex
        if (binding.cpvTextColor.colorHex != hex) binding.cpvTextColor.colorHex = hex
        getElement()?.let { el -> viewModel.updateElement(copyWithColor(el, hex)) }
    }

    private fun applyBgColor(hex: String) {
        binding.fieldBgColorHex.text = hex
        binding.btnClearBgColor.visibility = View.VISIBLE
        if (binding.cpvBgColor.colorHex != hex) binding.cpvBgColor.colorHex = hex
        getElement()?.let { el -> viewModel.updateElement(copyWithBgColor(el, hex)) }
    }

    private fun clearBgColor() {
        getElement()?.let { el ->
            if (bgColor(el) == null) return
            viewModel.updateElement(copyWithBgColor(el, null))
        }
        binding.fieldBgColorHex.text = getString(R.string.prop_no_bg_color)
        binding.cpvBgColor.colorHex = "#FFFFFFFF"
        binding.btnClearBgColor.visibility = View.GONE
    }

    private fun applyStrokeColor(hex: String) {
        binding.fieldStrokeColorHex.text = hex
        if (binding.cpvStrokeColor.colorHex != hex) binding.cpvStrokeColor.colorHex = hex
        getElement()?.let { el -> viewModel.updateElement(copyWithTextStrokeColor(el, hex)) }
    }

    companion object {
        private const val REQ_CREDENTIAL_TEXT_COLOR_HEX = "req_credential_text_color_hex"
        private const val REQ_CREDENTIAL_BG_COLOR_HEX = "req_credential_bg_color_hex"
        private const val REQ_CREDENTIAL_STROKE_COLOR_HEX = "req_credential_stroke_color_hex"
        private const val CREDENTIAL_TEXT_COLOR_DIALOG_TAG = "credential_text_color_hex_dialog"
        private const val CREDENTIAL_BG_COLOR_DIALOG_TAG = "credential_bg_color_hex_dialog"
        private const val CREDENTIAL_STROKE_COLOR_DIALOG_TAG = "credential_stroke_color_hex_dialog"
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

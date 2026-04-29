package dev.anonymous.cardsdesignerpro.ui.editor.properties

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropLineBinding
import dev.anonymous.cardsdesignerpro.ui.common.ColorHexDialogSupport
import dev.anonymous.cardsdesignerpro.ui.editor.EditorUiState
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel

class LinePropertiesFragment : Fragment(), PropertyFragment {

    private var _b: FragmentPropLineBinding? = null
    private val b get() = _b!!
    private val viewModel: EditorViewModel by activityViewModels()
    private var updating = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentPropLineBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.stepperThickness.minValue = 1
        b.stepperThickness.maxValue = 20

        ColorHexDialogSupport.registerResultListener(
            fragment = this,
            owner = viewLifecycleOwner,
            requestKey = REQ_LINE_COLOR_HEX
        ) { hex ->
            applyColor(hex)
        }

        val el = viewModel.selectedElement as? TemplateElement.LineElement ?: return
        populate(el)
        setupListeners()
    }

    private fun populate(el: TemplateElement.LineElement) {
        updating = true
        if (b.cpvLineColor.colorHex != el.color) b.cpvLineColor.colorHex = el.color
        b.fieldLineColorHex.text = el.color
        val thicknessVal = el.height.toInt().coerceIn(1, 20)
        if (b.stepperThickness.value != thicknessVal) b.stepperThickness.value = thicknessVal
        if (b.cbRoundedCaps.isChecked != el.roundedCaps) b.cbRoundedCaps.isChecked = el.roundedCaps
        updating = false
    }

    private fun setupListeners() {
        b.cpvLineColor.onColorSelected = { hex -> applyColor(hex) }
        b.fieldLineColorHex.setOnClickListener { openColorHexDialog() }

        b.stepperThickness.onValueChanged = { v ->
            update { it.copy(height = v.toFloat()) }
        }

        b.cbRoundedCaps.setOnCheckedChangeListener { _, checked ->
            if (!updating) update { it.copy(roundedCaps = checked) }
        }
    }

    private fun openColorHexDialog() {
        val el = viewModel.selectedElement as? TemplateElement.LineElement ?: return
        ColorHexDialogSupport.showDialog(
            fragment = this,
            requestKey = REQ_LINE_COLOR_HEX,
            dialogTag = LINE_COLOR_DIALOG_TAG,
            initialHex = el.color,
            enableAlpha = b.cpvLineColor.enableAlpha
        )
    }

    private fun applyColor(hex: String) {
        b.fieldLineColorHex.text = hex
        if (b.cpvLineColor.colorHex != hex) b.cpvLineColor.colorHex = hex
        update { it.copy(color = hex) }
    }

    private fun update(transform: (TemplateElement.LineElement) -> TemplateElement.LineElement) {
        if (updating) return
        val el = viewModel.selectedElement as? TemplateElement.LineElement ?: return
        viewModel.updateElement(transform(el))
    }

    override fun onUiStateChanged(state: EditorUiState) {
        if (_b == null) return
        val el = viewModel.currentElements
            .firstOrNull { it.id == state.selectedElementId }
            as? TemplateElement.LineElement ?: return
        populate(el)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        private const val REQ_LINE_COLOR_HEX = "req_line_color_hex"
        private const val LINE_COLOR_DIALOG_TAG = "line_color_hex_dialog"
    }
}

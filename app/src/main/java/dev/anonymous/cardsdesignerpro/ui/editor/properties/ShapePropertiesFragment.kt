package dev.anonymous.cardsdesignerpro.ui.editor.properties

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropShapeBinding
import dev.anonymous.cardsdesignerpro.ui.common.ColorHexDialogSupport
import dev.anonymous.cardsdesignerpro.ui.editor.EditorUiState
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel
import kotlin.math.roundToInt

class ShapePropertiesFragment : Fragment(), PropertyFragment {

    private var _b: FragmentPropShapeBinding? = null
    private val b get() = _b!!
    private val viewModel: EditorViewModel by activityViewModels()
    private var updating = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentPropShapeBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.stepperStrokeWidth.minValue = 0
        b.stepperStrokeWidth.maxValue = 20

        ColorHexDialogSupport.registerResultListener(
            fragment = this,
            owner = viewLifecycleOwner,
            requestKey = REQ_SHAPE_FILL_COLOR_HEX
        ) { hex ->
            applyFillColor(hex)
        }
        ColorHexDialogSupport.registerResultListener(
            fragment = this,
            owner = viewLifecycleOwner,
            requestKey = REQ_SHAPE_STROKE_COLOR_HEX
        ) { hex ->
            applyStrokeColor(hex)
        }

        val el = viewModel.selectedElement as? TemplateElement.ShapeElement ?: return
        populate(el)
        setupListeners()
    }

    private fun populate(el: TemplateElement.ShapeElement) {
        updating = true

        if (b.cpvFillColor.colorHex != el.fillColor) b.cpvFillColor.colorHex = el.fillColor
        b.fieldFillColorHex.text = el.fillColor

        if (b.cpvStrokeColor.colorHex != el.strokeColor) b.cpvStrokeColor.colorHex = el.strokeColor
        b.fieldStrokeColorHex.text = el.strokeColor

        val swVal = el.strokeWidthDp.toInt().coerceIn(0, 20)
        if (b.stepperStrokeWidth.value != swVal) b.stepperStrokeWidth.value = swVal

        val crVal = normalizeCornerRadius(el.cornerRadiusDp)
        if (b.sliderCornerRadius.value != crVal) b.sliderCornerRadius.value = crVal
        b.tvCornerRadiusLabel.text = getString(R.string.prop_shape_corner_radius) + ": ${crVal.toInt()}"

        if (b.cbDashed.isChecked != el.isDashed) {
            b.cbDashed.isChecked = el.isDashed
            b.layoutDashOptions.visibility = if (el.isDashed) View.VISIBLE else View.GONE
        } else {
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

    private fun setupListeners() {
        b.cpvFillColor.onColorSelected = { hex -> applyFillColor(hex) }
        b.fieldFillColorHex.setOnClickListener { openFillColorHexDialog() }

        b.cpvStrokeColor.onColorSelected = { hex -> applyStrokeColor(hex) }
        b.fieldStrokeColorHex.setOnClickListener { openStrokeColorHexDialog() }

        b.stepperStrokeWidth.onValueChanged = { v ->
            update { it.copy(strokeWidthDp = v.toFloat()) }
        }
        b.sliderCornerRadius.addOnChangeListener { _, v, _ ->
            b.tvCornerRadiusLabel.text = getString(R.string.prop_shape_corner_radius) + ": ${v.toInt()}"
            update { it.copy(cornerRadiusDp = v) }
        }
        b.cbDashed.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                update { it.copy(isDashed = checked) }
                b.layoutDashOptions.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }
        b.sliderDashLength.addOnChangeListener { _, v, _ ->
            b.tvDashLengthLabel.text = getString(R.string.prop_frame_dash_length) + ": ${v.toInt()}"
            update { it.copy(dashLengthDp = v) }
        }
        b.sliderDashGap.addOnChangeListener { _, v, _ ->
            b.tvDashGapLabel.text = getString(R.string.prop_frame_dash_gap) + ": ${v.toInt()}"
            update { it.copy(dashGapDp = v) }
        }
        b.cbDashRounded.setOnCheckedChangeListener { _, checked ->
            if (!updating) update { it.copy(isDashRounded = checked) }
        }
    }

    private fun openFillColorHexDialog() {
        val el = viewModel.selectedElement as? TemplateElement.ShapeElement ?: return
        ColorHexDialogSupport.showDialog(
            fragment = this,
            requestKey = REQ_SHAPE_FILL_COLOR_HEX,
            dialogTag = SHAPE_FILL_COLOR_DIALOG_TAG,
            initialHex = el.fillColor,
            enableAlpha = b.cpvFillColor.enableAlpha
        )
    }

    private fun openStrokeColorHexDialog() {
        val el = viewModel.selectedElement as? TemplateElement.ShapeElement ?: return
        ColorHexDialogSupport.showDialog(
            fragment = this,
            requestKey = REQ_SHAPE_STROKE_COLOR_HEX,
            dialogTag = SHAPE_STROKE_COLOR_DIALOG_TAG,
            initialHex = el.strokeColor,
            enableAlpha = b.cpvStrokeColor.enableAlpha
        )
    }

    private fun applyFillColor(hex: String) {
        b.fieldFillColorHex.text = hex
        if (b.cpvFillColor.colorHex != hex) b.cpvFillColor.colorHex = hex
        update { it.copy(fillColor = hex) }
    }

    private fun applyStrokeColor(hex: String) {
        b.fieldStrokeColorHex.text = hex
        if (b.cpvStrokeColor.colorHex != hex) b.cpvStrokeColor.colorHex = hex
        update { it.copy(strokeColor = hex) }
    }

    private fun update(transform: (TemplateElement.ShapeElement) -> TemplateElement.ShapeElement) {
        if (updating) return
        val el = viewModel.selectedElement as? TemplateElement.ShapeElement ?: return
        viewModel.updateElement(transform(el))
    }

    private fun normalizeCornerRadius(value: Float): Float {
        val clamped = value.coerceIn(0f, 200f)
        return (clamped / 2f).roundToInt() * 2f
    }

    override fun onUiStateChanged(state: EditorUiState) {
        if (_b == null) return
        val el = viewModel.currentElements
            .firstOrNull { it.id == state.selectedElementId }
            as? TemplateElement.ShapeElement ?: return
        populate(el)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        private const val REQ_SHAPE_FILL_COLOR_HEX = "req_shape_fill_color_hex"
        private const val REQ_SHAPE_STROKE_COLOR_HEX = "req_shape_stroke_color_hex"
        private const val SHAPE_FILL_COLOR_DIALOG_TAG = "shape_fill_color_hex_dialog"
        private const val SHAPE_STROKE_COLOR_DIALOG_TAG = "shape_stroke_color_hex_dialog"
    }
}

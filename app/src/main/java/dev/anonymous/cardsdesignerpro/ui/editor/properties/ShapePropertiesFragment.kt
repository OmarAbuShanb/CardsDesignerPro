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
import dev.anonymous.cardsdesignerpro.ui.editor.EditorUiState
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel

class ShapePropertiesFragment : Fragment(), PropertyFragment {

    private var _b: FragmentPropShapeBinding? = null
    private val b get() = _b!!
    private val viewModel: EditorViewModel by activityViewModels()
    private var updating = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentPropShapeBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val el = viewModel.selectedElement as? TemplateElement.ShapeElement ?: return
        populate(el)
        setupListeners()
    }

    private fun populate(el: TemplateElement.ShapeElement) {
        updating = true

        if (b.cpvFillColor.colorHex != el.fillColor) b.cpvFillColor.colorHex = el.fillColor
        b.tvFillColorHex.text = el.fillColor

        if (b.cpvStrokeColor.colorHex != el.strokeColor) b.cpvStrokeColor.colorHex = el.strokeColor
        b.tvStrokeColorHex.text = el.strokeColor

        val strokeVal = el.strokeWidthDp.coerceIn(0f, 20f)
        if (b.sliderStrokeWidth.value != strokeVal) b.sliderStrokeWidth.value = strokeVal
        b.tvStrokeWidthLabel.text = getString(R.string.prop_shape_stroke_width) + ": ${strokeVal.toInt()}"

        val crVal = el.cornerRadiusDp.coerceIn(0f, 100f)
        if (b.sliderCornerRadius.value != crVal) b.sliderCornerRadius.value = crVal
        b.tvCornerRadiusLabel.text = getString(R.string.prop_shape_corner_radius) + ": ${crVal.toInt()}"

        updating = false
    }

    private fun setupListeners() {
        b.cpvFillColor.onColorSelected = { hex ->
            b.tvFillColorHex.text = hex
            val el = viewModel.selectedElement as? TemplateElement.ShapeElement
            if (el != null) viewModel.updateElement(el.copy(fillColor = hex))
        }
        b.cpvStrokeColor.onColorSelected = { hex ->
            b.tvStrokeColorHex.text = hex
            val el = viewModel.selectedElement as? TemplateElement.ShapeElement
            if (el != null) viewModel.updateElement(el.copy(strokeColor = hex))
        }
        b.sliderStrokeWidth.addOnChangeListener { _, v, _ ->
            b.tvStrokeWidthLabel.text = getString(R.string.prop_shape_stroke_width) + ": ${v.toInt()}"
            if (!updating) {
                val el = viewModel.selectedElement as? TemplateElement.ShapeElement
                if (el != null && el.strokeWidthDp != v) viewModel.updateElement(el.copy(strokeWidthDp = v))
            }
        }
        b.sliderCornerRadius.addOnChangeListener { _, v, _ ->
            b.tvCornerRadiusLabel.text = getString(R.string.prop_shape_corner_radius) + ": ${v.toInt()}"
            if (!updating) {
                val el = viewModel.selectedElement as? TemplateElement.ShapeElement
                if (el != null && el.cornerRadiusDp != v) viewModel.updateElement(el.copy(cornerRadiusDp = v))
            }
        }
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
}

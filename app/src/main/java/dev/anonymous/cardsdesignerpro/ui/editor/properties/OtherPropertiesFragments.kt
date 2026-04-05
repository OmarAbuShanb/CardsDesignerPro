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
import dev.anonymous.cardsdesignerpro.data.model.DecorationShape
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import dev.anonymous.cardsdesignerpro.util.ImageUtils
import java.io.File
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropDateBinding
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropFrameBinding
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropDecorationBinding
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropNoSelectionBinding
import dev.anonymous.cardsdesignerpro.ui.editor.EditorUiState
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel

// ── Date Properties ──────────────────────────────────────────────────────────

class DatePropertiesFragment : Fragment(), PropertyFragment {
    private var _b: FragmentPropDateBinding? = null
    private val b get() = _b!!
    val viewModel: EditorViewModel by activityViewModels()
    private var updating = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentPropDateBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val formats = DateFormat.values()
        b.spinnerDateFormat.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, formats.map { it.displayName })
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val fontNames = listOf("Default" to "default", "Serif" to "serif",
            "Sans-serif" to "sans-serif", "Monospace" to "monospace")
        b.spinnerFont.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, fontNames.map { it.first })
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val el = viewModel.selectedElement as? TemplateElement.DateElement ?: return
        populate(el, formats, fontNames)

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
        b.cbBold.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                val e = viewModel.selectedElement as? TemplateElement.DateElement ?: return@setOnCheckedChangeListener
                viewModel.updateElement(e.copy(isBold = checked))
            }
        }
        b.stepperFontSize.onValueChanged = { v ->
            if (!updating) {
                val e = viewModel.selectedElement as? TemplateElement.DateElement
                if (e != null) viewModel.updateElement(e.copy(textSizeSp = v.toFloat()))
            }
        }
        b.spinnerFont.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updating) return
                val e = viewModel.selectedElement as? TemplateElement.DateElement ?: return
                if (e.fontName == fontNames[pos].second) return   // spurious fire
                viewModel.updateElement(e.copy(fontName = fontNames[pos].second))
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun populate(el: TemplateElement.DateElement, formats: Array<DateFormat>,
                         fontNames: List<Pair<String,String>>) {
        updating = true
        b.spinnerDateFormat.setSelection(formats.indexOf(el.format).coerceAtLeast(0))
        b.cpvTextColor.colorHex = el.textColor
        b.tvColorHex.text = el.textColor
        b.cbBold.isChecked = el.isBold
        b.stepperFontSize.minValue = 6; b.stepperFontSize.maxValue = 72
        b.stepperFontSize.value = el.textSizeSp.toInt()
        b.spinnerFont.setSelection(fontNames.indexOfFirst { it.second == el.fontName }.coerceAtLeast(0))
        updating = false
    }

    override fun onUiStateChanged(state: EditorUiState) {
        if (_b == null) return
        val el = state.template.elements.firstOrNull { it.id == state.selectedElementId }
            as? TemplateElement.DateElement ?: return
        populate(el, DateFormat.values(), listOf("Default" to "default", "Serif" to "serif",
            "Sans-serif" to "sans-serif", "Monospace" to "monospace"))
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
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
        b.stepperThickness.onValueChanged = { update { el2 -> el2.copy(strokeWidthDp = it.toFloat()) } }
        b.stepperCornerRadius.onValueChanged = { update { el2 -> el2.copy(cornerRadiusDp = it.toFloat()) } }
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
        b.cpvFrameColor.colorHex = el.color; b.tvColorHex.text = el.color
        b.stepperThickness.minValue = 1; b.stepperThickness.maxValue = 20
        b.stepperThickness.value = el.strokeWidthDp.toInt()
        b.stepperCornerRadius.minValue = 0; b.stepperCornerRadius.maxValue = 80
        b.stepperCornerRadius.value = el.cornerRadiusDp.toInt()
        b.sliderPadding.value = el.paddingDp.coerceIn(0f, 60f)
        b.cbDashed.isChecked = el.isDashed
        b.layoutDashOptions.visibility = if (el.isDashed) View.VISIBLE else View.GONE
        b.sliderDashLength.value = el.dashLengthDp.coerceIn(2f, 60f)
        b.sliderDashGap.value = el.dashGapDp.coerceIn(0f, 40f)
        b.cbDashRounded.isChecked = el.isDashRounded
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

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

// ── Background Decoration Properties ─────────────────────────────────────────

class BackgroundDecorationPropertiesFragment : Fragment(), PropertyFragment {
    private var _b: FragmentPropDecorationBinding? = null
    private val b get() = _b!!
    val viewModel: EditorViewModel by activityViewModels()
    private var updating = false

    private val pickBgImageLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { copyImage(it) }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentPropDecorationBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val shapes = DecorationShape.values()
        b.spinnerShape.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, shapes.map { it.displayName })
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val el = viewModel.selectedElement as? TemplateElement.BackgroundDecorationElement ?: return
        populate(el, shapes)

        b.spinnerShape.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updating) return
                val e = viewModel.selectedElement as? TemplateElement.BackgroundDecorationElement ?: return
                val newShape = shapes[pos]
                if (e.shapeType == newShape) return   // spurious fire
                if (newShape == DecorationShape.CUSTOM_IMAGE && e.shapeType != DecorationShape.CUSTOM_IMAGE) {
                    if (e.customImagePath == null) {
                        pickBgImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
                viewModel.updateElement(e.copy(shapeType = newShape))
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        b.sliderDensity.addOnChangeListener { _, value, _ ->
            val e = viewModel.selectedElement as? TemplateElement.BackgroundDecorationElement ?: return@addOnChangeListener
            viewModel.updateElement(e.copy(density = value))
        }
        b.cpvDecoColor.onColorSelected = { hex ->
            b.tvColorHex.text = hex
            val e = viewModel.selectedElement as? TemplateElement.BackgroundDecorationElement
            if (e != null) viewModel.updateElement(e.copy(color = hex))
        }

        b.btnRemoveCustomImage.setOnClickListener {
            val e = viewModel.selectedElement as? TemplateElement.BackgroundDecorationElement ?: return@setOnClickListener
            viewModel.updateElement(e.copy(customImagePath = null))
        }
    }

    private fun copyImage(uri: Uri) {
        val dir = viewModel.getImageDirForCurrentTemplate()
        val result = ImageUtils.copyAndFixExif(requireContext(), uri, dir) ?: return
        val e = viewModel.selectedElement as? TemplateElement.BackgroundDecorationElement ?: return
        viewModel.updateElement(e.copy(customImagePath = result.file.absolutePath))
    }

    private fun populate(el: TemplateElement.BackgroundDecorationElement, shapes: Array<DecorationShape>) {
        updating = true
        b.spinnerShape.setSelection(shapes.indexOf(el.shapeType).coerceAtLeast(0))
        b.sliderDensity.value = el.density.coerceIn(0f, 1f)
        b.cpvDecoColor.colorHex = el.color; b.tvColorHex.text = el.color
        
        if (el.shapeType == DecorationShape.CUSTOM_IMAGE && el.customImagePath != null) {
            b.layoutCustomImage.visibility = View.VISIBLE
            b.tvCustomImageName.text = File(el.customImagePath).name
        } else {
            b.layoutCustomImage.visibility = View.GONE
        }
        
        updating = false
    }

    override fun onUiStateChanged(state: EditorUiState) {
        if (_b == null) return
        val el = state.template.elements.firstOrNull { it.id == state.selectedElementId }
            as? TemplateElement.BackgroundDecorationElement ?: return
        populate(el, DecorationShape.values())
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

// ── No Selection ──────────────────────────────────────────────────────────────

class NoSelectionFragment : Fragment(), PropertyFragment {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        FragmentPropNoSelectionBinding.inflate(i, c, false).root

    override fun onUiStateChanged(state: EditorUiState) {}
}

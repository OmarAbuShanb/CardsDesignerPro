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
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropTextBinding
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.ui.editor.EditorUiState
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel

private val FONTS = listOf("Default" to "default", "Serif" to "serif",
    "Sans-serif" to "sans-serif", "Monospace" to "monospace")

class TextPropertiesFragment : Fragment(), PropertyFragment {

    private var _binding: FragmentPropTextBinding? = null
    private val binding get() = _binding!!
    val viewModel: EditorViewModel by activityViewModels()
    private var updating = false

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
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            FONTS.map { it.first }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerFont.adapter = adapter
    }

    private fun populateFrom(el: TemplateElement.TextElement) {
        updating = true
        if (binding.etText.text.toString() != el.text) {
            binding.etText.setText(el.text)
            binding.etText.setSelection(el.text.length.coerceAtMost(binding.etText.text?.length ?: 0))
        }
        binding.cpvTextColor.colorHex = el.textColor
        binding.tvTextColorHex.text = el.textColor
        binding.cpvBgColor.colorHex = el.bgColor ?: "#FFFFFF"
        binding.tvBgColorHex.text = el.bgColor ?: "none"
        binding.cbBold.isChecked = el.isBold
        binding.stepperFontSize.minValue = 6; binding.stepperFontSize.maxValue = 72
        binding.stepperFontSize.value = el.textSizeSp.toInt()
        val fontIdx = FONTS.indexOfFirst { it.second == el.fontName }.coerceAtLeast(0)
        binding.spinnerFont.setSelection(fontIdx)
        updating = false
    }

    private fun setupListeners() {
        binding.etText.doAfterTextChanged { text ->
            if (updating) return@doAfterTextChanged
            val el = viewModel.selectedElement as? TemplateElement.TextElement ?: return@doAfterTextChanged
            val newText = text.toString()
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
        binding.cbBold.setOnCheckedChangeListener { _, isChecked ->
            if (updating) return@setOnCheckedChangeListener
            val el = viewModel.selectedElement as? TemplateElement.TextElement ?: return@setOnCheckedChangeListener
            if (el.isBold == isChecked) return@setOnCheckedChangeListener
            viewModel.updateElement(el.copy(isBold = isChecked))
        }
        binding.stepperFontSize.onValueChanged = { size ->
            if (!updating) {
                val el = viewModel.selectedElement as? TemplateElement.TextElement
                if (el != null && el.textSizeSp != size.toFloat())
                    viewModel.updateElement(el.copy(textSizeSp = size.toFloat()))
            }
        }
        binding.spinnerFont.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (updating) return
                val el = viewModel.selectedElement as? TemplateElement.TextElement ?: return
                val newFont = FONTS[pos].second
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

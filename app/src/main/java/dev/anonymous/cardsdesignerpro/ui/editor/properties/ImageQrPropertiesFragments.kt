package dev.anonymous.cardsdesignerpro.ui.editor.properties

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropImageBinding
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropQrBinding
import dev.anonymous.cardsdesignerpro.ui.editor.EditorUiState
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel
import dev.anonymous.cardsdesignerpro.util.ImageUtils

// ── Image Properties ──────────────────────────────────────────────────────────

class ImagePropertiesFragment : Fragment(), PropertyFragment {
    private var _binding: FragmentPropImageBinding? = null
    private val binding get() = _binding!!
    val viewModel: EditorViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPropImageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val el = viewModel.selectedElement as? TemplateElement.ImageElement ?: return
        binding.cpvTint.colorHex = el.tintColor ?: "#FFFFFF"
        binding.tvTintHex.text = el.tintColor ?: "none"

        binding.cpvTint.onColorSelected = { hex ->
            binding.tvTintHex.text = hex
            val e = viewModel.selectedElement as? TemplateElement.ImageElement
            if (e != null) viewModel.updateElement(e.copy(tintColor = hex))
        }
        binding.btnClearTint.setOnClickListener {
            val e = viewModel.selectedElement as? TemplateElement.ImageElement ?: return@setOnClickListener
            viewModel.updateElement(e.copy(tintColor = null))
            binding.tvTintHex.text = "none"
        }
    }

    override fun onUiStateChanged(state: EditorUiState) {
        if (_binding == null) return
        val el = state.template.elements.firstOrNull { it.id == state.selectedElementId }
            as? TemplateElement.ImageElement ?: return
        binding.cpvTint.colorHex = el.tintColor ?: "#FFFFFF"
        binding.tvTintHex.text = el.tintColor ?: "none"
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ── QR Properties ────────────────────────────────────────────────────────────

class QrPropertiesFragment : Fragment(), PropertyFragment {
    private var _binding: FragmentPropQrBinding? = null
    private val binding get() = _binding!!
    val viewModel: EditorViewModel by activityViewModels()
    private var updating = false

    private val pickLogoLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { copyLogo(it) }
    }

    // Shape option arrays
    private val pixelShapeValues = listOf("square", "round", "circle")
    private val eyeShapeValues   = listOf("square", "round", "circle")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPropQrBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val pixelLabels = resources.getStringArray(R.array.qr_pixel_shapes)
        val eyeLabels   = resources.getStringArray(R.array.qr_eye_shapes)

        binding.spinnerPixelShape.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, pixelLabels)
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerEyeShape.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, eyeLabels)
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val el = viewModel.selectedElement as? TemplateElement.QrElement ?: return
        populateFrom(el)

        // Host
        binding.etHost.setOnEditorActionListener { _, _, _ ->
            val e = viewModel.selectedElement as? TemplateElement.QrElement ?: return@setOnEditorActionListener false
            viewModel.updateElement(e.copy(host = binding.etHost.text.toString()))
            false
        }

        // Colors
        binding.cpvQrColor.onColorSelected = { hex ->
            binding.tvQrColorHex.text = hex
            val e = viewModel.selectedElement as? TemplateElement.QrElement
            if (e != null) viewModel.updateElement(e.copy(qrColor = hex))
        }
        binding.cpvQrBgColor.onColorSelected = { hex ->
            binding.tvQrBgColorHex.text = hex
            val e = viewModel.selectedElement as? TemplateElement.QrElement
            if (e != null) viewModel.updateElement(e.copy(backgroundColor = hex))
        }

        // Pixel shape spinner
        binding.spinnerPixelShape.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updating) return
                val e = viewModel.selectedElement as? TemplateElement.QrElement ?: return
                viewModel.updateElement(e.copy(pixelShape = pixelShapeValues[pos]))
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Eye shape spinner
        binding.spinnerEyeShape.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updating) return
                val e = viewModel.selectedElement as? TemplateElement.QrElement ?: return
                viewModel.updateElement(e.copy(eyeShape = eyeShapeValues[pos]))
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Logo
        binding.btnPickLogo.setOnClickListener {
            pickLogoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnRemoveLogo.setOnClickListener {
            val e = viewModel.selectedElement as? TemplateElement.QrElement ?: return@setOnClickListener
            viewModel.updateElement(e.copy(logoPath = null))
        }
        binding.cbTintLogo.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                val e = viewModel.selectedElement as? TemplateElement.QrElement ?: return@setOnCheckedChangeListener
                viewModel.updateElement(e.copy(tintLogo = checked))
            }
        }
        
        binding.sliderLogoSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updating) {
                val e = viewModel.selectedElement as? TemplateElement.QrElement ?: return@addOnChangeListener
                viewModel.updateElement(e.copy(logoSizeFraction = value))
            }
        }

        // Padding
        binding.sliderQrPadding.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updating) {
                val e = viewModel.selectedElement as? TemplateElement.QrElement ?: return@addOnChangeListener
                viewModel.updateElement(e.copy(qrPadding = value))
            }
        }
    }

    private fun populateFrom(el: TemplateElement.QrElement) {
        updating = true
        binding.etHost.setText(el.host)
        binding.cpvQrColor.colorHex = el.qrColor
        binding.tvQrColorHex.text = el.qrColor
        binding.cpvQrBgColor.colorHex = el.backgroundColor
        binding.tvQrBgColorHex.text = el.backgroundColor
        binding.spinnerPixelShape.setSelection(pixelShapeValues.indexOf(el.pixelShape).coerceAtLeast(0))
        binding.spinnerEyeShape.setSelection(eyeShapeValues.indexOf(el.eyeShape).coerceAtLeast(0))
        binding.sliderLogoSize.value = el.logoSizeFraction.coerceIn(0.05f, 0.6f)
        binding.sliderQrPadding.value = el.qrPadding.coerceIn(0f, 0.25f)
        
        if (el.logoPath != null) {
            binding.layoutSelectedLogo.visibility = View.VISIBLE
            binding.tvLogoName.text = java.io.File(el.logoPath).name
        } else {
            binding.layoutSelectedLogo.visibility = View.GONE
        }
        
        binding.cbTintLogo.isChecked = el.tintLogo
        
        updating = false
    }

    private fun copyLogo(uri: Uri) {
        val dir = viewModel.getImageDirForCurrentTemplate()
        val result = ImageUtils.copyAndFixExif(requireContext(), uri, dir) ?: return
        val e = viewModel.selectedElement as? TemplateElement.QrElement ?: return
        viewModel.updateElement(e.copy(logoPath = result.file.absolutePath))
    }

    override fun onUiStateChanged(state: EditorUiState) {
        if (_binding == null) return
        val el = state.template.elements.firstOrNull { it.id == state.selectedElementId }
            as? TemplateElement.QrElement ?: return
        populateFrom(el)
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

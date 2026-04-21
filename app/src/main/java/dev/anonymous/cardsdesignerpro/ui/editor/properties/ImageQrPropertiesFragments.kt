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

    /** Pick a new image to replace the current one — preserves size and position. */
    private val changeImageLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { changeImage(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPropImageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val el = viewModel.selectedElement as? TemplateElement.ImageElement ?: return
        binding.cpvTint.colorHex = el.tintColor ?: "#FFFFFF"
        binding.tvTintHex.text = el.tintColor ?: getString(R.string.prop_tint_none)
        binding.btnClearTint.visibility = if (el.tintColor != null) View.VISIBLE else View.GONE

        binding.cpvTint.onColorSelected = { hex ->
            binding.tvTintHex.text = hex
            binding.btnClearTint.visibility = View.VISIBLE
            val e = viewModel.selectedElement as? TemplateElement.ImageElement
            if (e != null) viewModel.updateElement(e.copy(tintColor = hex))
        }
        binding.btnClearTint.setOnClickListener {
            val e = viewModel.selectedElement as? TemplateElement.ImageElement ?: return@setOnClickListener
            viewModel.updateElement(e.copy(tintColor = null))
            binding.tvTintHex.text = getString(R.string.prop_tint_none)
            binding.btnClearTint.visibility = View.GONE
        }
        binding.btnChangeImage.setOnClickListener {
            val e = viewModel.selectedElement as? TemplateElement.ImageElement ?: return@setOnClickListener
            if (e.imagePath.startsWith("pack:")) {
                val sheet = dev.anonymous.cardsdesignerpro.ui.editor.addelem.PackBrowserBottomSheet()
                sheet.show(parentFragmentManager, "pack_change")
            } else {
                changeImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }

        // Listen for pack browser result (replacing a pack image)
        parentFragmentManager.setFragmentResultListener(
            dev.anonymous.cardsdesignerpro.ui.editor.addelem.PackBrowserBottomSheet.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val packPath = bundle.getString(
                dev.anonymous.cardsdesignerpro.ui.editor.addelem.PackBrowserBottomSheet.KEY_PACK_PATH
            ) ?: return@setFragmentResultListener
            val e = viewModel.selectedElement as? TemplateElement.ImageElement ?: return@setFragmentResultListener
            
            val assetPath = packPath.removePrefix("pack:")
            val (srcW, srcH) = readSvgDimensions(assetPath)
            
            val oldW = e.width
            val oldH = e.height
            var newW = oldW
            var newH = oldH
            if (srcW > 0 && srcH > 0) {
                val aspect = srcW.toFloat() / srcH.toFloat()
                if (oldW / aspect <= oldH) {
                    newW = oldW
                    newH = oldW / aspect
                } else {
                    newH = oldH
                    newW = oldH * aspect
                }
            }
            
            val cx = e.x + oldW / 2f
            val cy = e.y + oldH / 2f
            
            viewModel.updateElement(e.copy(
                imagePath = packPath,
                width = newW,
                height = newH,
                x = cx - newW / 2f,
                y = cy - newH / 2f
            ))
        }
    }

    private fun readSvgDimensions(assetPath: String): Pair<Int, Int> {
        return runCatching {
            val svg = requireContext().assets.open(assetPath).use {
                com.caverock.androidsvg.SVG.getFromInputStream(it)
            }
            val w = if (svg.documentWidth > 0f) svg.documentWidth.toInt() else 24
            val h = if (svg.documentHeight > 0f) svg.documentHeight.toInt() else 24
            w to h
        }.getOrDefault(0 to 0)
    }

    private fun changeImage(uri: Uri) {
        val dir = viewModel.getImageDirForCurrentTemplate()
        val result = ImageUtils.copyAndFixExif(requireContext(), uri, dir) ?: return
        val e = viewModel.selectedElement as? TemplateElement.ImageElement ?: return

        // Fit new image aspect ratio within old element bounds (no stretching)
        val oldW = e.width
        val oldH = e.height
        val srcW = result.width
        val srcH = result.height
        var newW = oldW
        var newH = oldH
        if (srcW > 0 && srcH > 0) {
            val aspect = srcW.toFloat() / srcH.toFloat()
            if (oldW / aspect <= oldH) {
                newW = oldW
                newH = oldW / aspect
            } else {
                newH = oldH
                newW = oldH * aspect
            }
        }
        // Keep element centered at the same position
        val cx = e.x + oldW / 2f
        val cy = e.y + oldH / 2f
        viewModel.updateElement(e.copy(
            imagePath = result.file.absolutePath,
            width = newW,
            height = newH,
            x = cx - newW / 2f,
            y = cy - newH / 2f
        ))
    }

    override fun onUiStateChanged(state: EditorUiState) {
        if (_binding == null) return
        val el = state.template.elements.firstOrNull { it.id == state.selectedElementId }
            as? TemplateElement.ImageElement ?: return
        binding.cpvTint.colorHex = el.tintColor ?: "#FFFFFF"
        binding.tvTintHex.text = el.tintColor ?: getString(R.string.prop_tint_none)
        binding.btnClearTint.visibility = if (el.tintColor != null) View.VISIBLE else View.GONE
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

        val el = viewModel.currentElements.firstOrNull { it.id == viewModel.uiState.value.selectedElementId } as? TemplateElement.QrElement ?: return
        populateFrom(el)

        // Host
        binding.etHost.setOnEditorActionListener { _, _, _ ->
            val e = viewModel.selectedElement as? TemplateElement.QrElement ?: return@setOnEditorActionListener false
            viewModel.updateElement(e.copy(host = binding.etHost.text.toString()))
            false
        }

        // Short Numbers Switch Link
        binding.switchQrShortNumbers.setOnCheckedChangeListener { _, isChecked ->
            if (!updating) {
                val e = viewModel.selectedElement as? TemplateElement.QrElement ?: return@setOnCheckedChangeListener
                viewModel.updateElement(e.copy(linkToShortNumbers = isChecked))
            }
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
        
        binding.sliderLogoSize.setLabelFormatter { value ->
            String.format("%.2f", value / 100f)
        }
        
        binding.sliderLogoSize.addOnChangeListener { _, value, fromUser ->
            val fraction = String.format("%.2f", value / 100f)
            binding.tvLogoSizeLabel.text = getString(R.string.prop_logo_size) + ": $fraction"
            if (fromUser && !updating) {
                val e = viewModel.selectedElement as? TemplateElement.QrElement ?: return@addOnChangeListener
                viewModel.updateElement(e.copy(logoSizeFraction = value / 100f))
            }
        }

        // Padding
        binding.sliderQrPadding.setLabelFormatter { value ->
            String.format("%.2f", value / 100f)
        }
        
        binding.sliderQrPadding.addOnChangeListener { _, value, fromUser ->
            val fraction = String.format("%.2f", value / 100f)
            binding.tvPaddingLabel.text = getString(R.string.prop_qr_padding) + ": $fraction"
            if (fromUser && !updating) {
                val e = viewModel.selectedElement as? TemplateElement.QrElement ?: return@addOnChangeListener
                viewModel.updateElement(e.copy(qrPadding = value / 100f))
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
        val logoVal = (el.logoSizeFraction * 100f).coerceIn(5f, 33f)
        binding.sliderLogoSize.value = logoVal
        binding.tvLogoSizeLabel.text = getString(R.string.prop_logo_size) + ": " + String.format("%.2f", logoVal / 100f)
        
        val padVal = (el.qrPadding * 100f).coerceIn(0f, 25f)
        binding.sliderQrPadding.value = padVal
        binding.tvPaddingLabel.text = getString(R.string.prop_qr_padding) + ": " + String.format("%.2f", padVal / 100f)
        
        if (el.logoPath != null) {
            binding.layoutSelectedLogo.visibility = View.VISIBLE
            binding.tvLogoName.text = java.io.File(el.logoPath).name
            binding.tvLogoSizeLabel.visibility = View.VISIBLE
            binding.sliderLogoSize.visibility = View.VISIBLE
        } else {
            binding.layoutSelectedLogo.visibility = View.GONE
            binding.tvLogoSizeLabel.visibility = View.GONE
            binding.sliderLogoSize.visibility = View.GONE
        }
        
        binding.cbTintLogo.isChecked = el.tintLogo
        
        if (viewModel.currentTemplate.isShortNumbersEnabled) {
            binding.switchQrShortNumbers.visibility = View.VISIBLE
            binding.switchQrShortNumbers.isChecked = el.linkToShortNumbers
        } else {
            binding.switchQrShortNumbers.visibility = View.GONE
        }
        
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
        val el = viewModel.currentElements.firstOrNull { it.id == state.selectedElementId }
            as? TemplateElement.QrElement ?: return
        populateFrom(el)
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

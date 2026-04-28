package dev.anonymous.cardsdesignerpro.ui.editor.properties

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropImageBinding
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropQrBinding
import dev.anonymous.cardsdesignerpro.ui.common.ColorHexDialogSupport
import dev.anonymous.cardsdesignerpro.ui.common.ImageSourceBottomSheet
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
        ColorHexDialogSupport.registerResultListener(
            fragment = this,
            owner = viewLifecycleOwner,
            requestKey = REQ_IMAGE_TINT_HEX
        ) { hex ->
            applyTintColor(hex)
        }
        val el = viewModel.selectedElement as? TemplateElement.ImageElement ?: return
        binding.cpvTint.colorHex = el.tintColor ?: "#FFFFFF"
        binding.fieldTintHex.text = el.tintColor ?: getString(R.string.prop_tint_none)
        binding.btnClearTint.visibility = if (el.tintColor != null) View.VISIBLE else View.GONE

        binding.cpvTint.onColorSelected = { hex -> applyTintColor(hex) }
        binding.fieldTintHex.setOnClickListener { openTintHexDialog() }
        binding.btnClearTint.setOnClickListener {
            clearTintColor()
        }
        binding.btnChangeImage.setOnClickListener {
            val e = viewModel.selectedElement as? TemplateElement.ImageElement ?: return@setOnClickListener
            if (e.imagePath.startsWith("pack:")) {
                val sheet = dev.anonymous.cardsdesignerpro.ui.editor.addelem.PackBrowserBottomSheet()
                sheet.show(parentFragmentManager, "pack_change")
            } else {
                ImageSourceBottomSheet.show(parentFragmentManager, REQ_CHANGE_IMAGE)
            }
        }

        // Listen for image source result (replacing a file-based image)
        parentFragmentManager.setFragmentResultListener(
            REQ_CHANGE_IMAGE, viewLifecycleOwner
        ) { _, bundle ->
            @Suppress("DEPRECATION")
            val uri = bundle.getParcelable<Uri>(ImageSourceBottomSheet.KEY_URI)
                ?: return@setFragmentResultListener
            changeImage(uri)
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
        binding.fieldTintHex.text = el.tintColor ?: getString(R.string.prop_tint_none)
        binding.btnClearTint.visibility = if (el.tintColor != null) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    private fun openTintHexDialog() {
        val el = viewModel.selectedElement as? TemplateElement.ImageElement ?: return
        ColorHexDialogSupport.showDialog(
            fragment = this,
            requestKey = REQ_IMAGE_TINT_HEX,
            dialogTag = IMAGE_TINT_DIALOG_TAG,
            initialHex = el.tintColor ?: binding.cpvTint.colorHex,
            enableAlpha = binding.cpvTint.enableAlpha
        )
    }

    private fun applyTintColor(hex: String) {
        binding.fieldTintHex.text = hex
        binding.btnClearTint.visibility = View.VISIBLE
        if (binding.cpvTint.colorHex != hex) binding.cpvTint.colorHex = hex
        val e = viewModel.selectedElement as? TemplateElement.ImageElement ?: return
        if (e.tintColor != hex) viewModel.updateElement(e.copy(tintColor = hex))
    }

    private fun clearTintColor() {
        val e = viewModel.selectedElement as? TemplateElement.ImageElement ?: return
        if (e.tintColor == null) return
        viewModel.updateElement(e.copy(tintColor = null))
        binding.cpvTint.colorHex = "#FFFFFF"
        binding.fieldTintHex.text = getString(R.string.prop_tint_none)
        binding.btnClearTint.visibility = View.GONE
    }

    companion object {
        private const val REQ_IMAGE_TINT_HEX = "req_image_tint_hex"
        private const val IMAGE_TINT_DIALOG_TAG = "image_tint_hex_dialog"
        private const val REQ_CHANGE_IMAGE = "req_change_image"
    }
}

// ── QR Properties ────────────────────────────────────────────────────────────

class QrPropertiesFragment : Fragment(), PropertyFragment {
    private var _binding: FragmentPropQrBinding? = null
    private val binding get() = _binding!!
    val viewModel: EditorViewModel by activityViewModels()
    private var updating = false

    // Shape option arrays
    private val pixelShapeValues = listOf("square", "round", "circle")
    private val eyeShapeValues   = listOf("square", "round", "circle")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPropQrBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ColorHexDialogSupport.registerResultListener(
            fragment = this,
            owner = viewLifecycleOwner,
            requestKey = REQ_QR_COLOR_HEX
        ) { hex ->
            applyQrColor(hex)
        }
        ColorHexDialogSupport.registerResultListener(
            fragment = this,
            owner = viewLifecycleOwner,
            requestKey = REQ_QR_BG_COLOR_HEX
        ) { hex ->
            applyQrBgColor(hex)
        }

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
        binding.cpvQrColor.onColorSelected = { hex -> applyQrColor(hex) }
        binding.fieldQrColorHex.setOnClickListener { openQrColorHexDialog() }
        binding.cpvQrBgColor.onColorSelected = { hex -> applyQrBgColor(hex) }
        binding.fieldQrBgColorHex.setOnClickListener { openQrBgColorHexDialog() }

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
            ImageSourceBottomSheet.show(parentFragmentManager, REQ_QR_LOGO)
        }

        // Listen for logo picker result
        parentFragmentManager.setFragmentResultListener(
            REQ_QR_LOGO, viewLifecycleOwner
        ) { _, bundle ->
            @Suppress("DEPRECATION")
            val uri = bundle.getParcelable<Uri>(ImageSourceBottomSheet.KEY_URI)
                ?: return@setFragmentResultListener
            copyLogo(uri)
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
        binding.fieldQrColorHex.text = el.qrColor
        binding.cpvQrBgColor.colorHex = el.backgroundColor
        binding.fieldQrBgColorHex.text = el.backgroundColor
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

    private fun openQrColorHexDialog() {
        val e = viewModel.selectedElement as? TemplateElement.QrElement ?: return
        ColorHexDialogSupport.showDialog(
            fragment = this,
            requestKey = REQ_QR_COLOR_HEX,
            dialogTag = QR_COLOR_DIALOG_TAG,
            initialHex = e.qrColor,
            enableAlpha = binding.cpvQrColor.enableAlpha
        )
    }

    private fun openQrBgColorHexDialog() {
        val e = viewModel.selectedElement as? TemplateElement.QrElement ?: return
        ColorHexDialogSupport.showDialog(
            fragment = this,
            requestKey = REQ_QR_BG_COLOR_HEX,
            dialogTag = QR_BG_COLOR_DIALOG_TAG,
            initialHex = e.backgroundColor,
            enableAlpha = binding.cpvQrBgColor.enableAlpha
        )
    }

    private fun applyQrColor(hex: String) {
        binding.fieldQrColorHex.text = hex
        if (binding.cpvQrColor.colorHex != hex) binding.cpvQrColor.colorHex = hex
        val e = viewModel.selectedElement as? TemplateElement.QrElement ?: return
        if (e.qrColor != hex) viewModel.updateElement(e.copy(qrColor = hex))
    }

    private fun applyQrBgColor(hex: String) {
        binding.fieldQrBgColorHex.text = hex
        if (binding.cpvQrBgColor.colorHex != hex) binding.cpvQrBgColor.colorHex = hex
        val e = viewModel.selectedElement as? TemplateElement.QrElement ?: return
        if (e.backgroundColor != hex) viewModel.updateElement(e.copy(backgroundColor = hex))
    }

    companion object {
        private const val REQ_QR_COLOR_HEX = "req_qr_color_hex"
        private const val REQ_QR_BG_COLOR_HEX = "req_qr_bg_color_hex"
        private const val QR_COLOR_DIALOG_TAG = "qr_color_hex_dialog"
        private const val QR_BG_COLOR_DIALOG_TAG = "qr_bg_color_hex_dialog"
        private const val REQ_QR_LOGO = "req_qr_logo"
    }
}

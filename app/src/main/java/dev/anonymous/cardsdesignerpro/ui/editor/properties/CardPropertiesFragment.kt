package dev.anonymous.cardsdesignerpro.ui.editor.properties

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dev.anonymous.cardsdesignerpro.data.model.DecorationShape
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropCardBinding
import dev.anonymous.cardsdesignerpro.ui.editor.EditorUiState
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel
import dev.anonymous.cardsdesignerpro.util.ImageUtils
import java.io.File

class CardPropertiesFragment : Fragment(), PropertyFragment {

    private var _binding: FragmentPropCardBinding? = null
    private val binding get() = _binding!!
    val viewModel: EditorViewModel by activityViewModels()
    private var updating = false

    private val pickBgImageLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { copyBgImage(it) } }

    private val pickPatternImageLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            copyPatternImage(uri)
            // Image picked successfully — NOW change shape to CUSTOM_IMAGE
            viewModel.updatePatternShape(DecorationShape.CUSTOM_IMAGE)
        } else {
            // User cancelled — revert spinner to previous shape
            updating = true
            binding.spinnerPatternShape.setSelection(viewModel.previousPatternShapeIndex)
            updating = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPropCardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val card = viewModel.activeCardStyle
        val shapes = DecorationShape.values()

        // ── Pattern shape spinner setup ───────────────────────────────────────
        binding.spinnerPatternShape.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, shapes.map { it.displayName })
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        populate(card, shapes)

        // ── Background color ─────────────────────────────────────────────────
        binding.cpvBgColor.onColorSelected = { hex ->
            binding.tvBgColorHex.text = hex
            viewModel.updateCardBackgroundColor(hex)
        }
        binding.btnChooseBgImage.setOnClickListener {
            pickBgImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnDeleteBgImage.setOnClickListener {
            viewModel.updateCardBackgroundImage(null)
            updateImageLabel(null)
        }

        // ── Pattern toggle ───────────────────────────────────────────────────
        binding.switchPattern.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                viewModel.updatePatternEnabled(checked)
                binding.layoutPatternOptions.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }

        // ── Pattern shape ────────────────────────────────────────────────────
        binding.spinnerPatternShape.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updating) return
                val currentCard = viewModel.activeCardStyle
                val newShape = shapes[pos]
                if (currentCard.patternShape == newShape) return
                if (newShape == DecorationShape.CUSTOM_IMAGE) {
                    // Save current shape index in ViewModel (survives config changes)
                    viewModel.previousPatternShapeIndex = shapes.indexOf(currentCard.patternShape).coerceAtLeast(0)
                    if (currentCard.patternCustomImagePath == null) {
                        // No image yet — open picker, DON'T change shape yet
                        pickPatternImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        // Revert spinner visually until image is picked
                        updating = true
                        binding.spinnerPatternShape.setSelection(viewModel.previousPatternShapeIndex)
                        updating = false
                        return
                    }
                }
                viewModel.updatePatternShape(newShape)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // ── Pattern density ──────────────────────────────────────────────────
        binding.sliderPatternDensity.addOnChangeListener { _, value, _ ->
            if (!updating) viewModel.updatePatternDensity(value)
        }

        // ── Pattern color ────────────────────────────────────────────────────
        binding.cpvPatternColor.onColorSelected = { hex ->
            binding.tvPatternColorHex.text = hex
            viewModel.updatePatternColor(hex)
        }

        // ── Pattern custom image tint ────────────────────────────────────────
        binding.cbPatternCustomTint.setOnCheckedChangeListener { _, isChecked ->
            if (!updating) viewModel.updatePatternCustomImageTint(isChecked)
        }

        // ── Remove custom pattern image ──────────────────────────────────────
        binding.btnRemovePatternImage.setOnClickListener {
            viewModel.updatePatternCustomImage(null)
            viewModel.updatePatternShape(DecorationShape.STARS_FOUR_POINT)
        }
    }

    override fun onUiStateChanged(state: EditorUiState) {
        if (_binding == null) return
        val card = viewModel.activeCardStyle
        populate(card, DecorationShape.values())
    }

    private fun populate(card: dev.anonymous.cardsdesignerpro.data.model.CardStyle, shapes: Array<DecorationShape>) {
        updating = true
        // Background
        if (binding.cpvBgColor.colorHex != card.backgroundColor) binding.cpvBgColor.colorHex = card.backgroundColor
        binding.tvBgColorHex.text = card.backgroundColor
        updateImageLabel(card.backgroundImagePath)

        // Pattern toggle
        if (binding.switchPattern.isChecked != card.patternEnabled) {
            binding.switchPattern.isChecked = card.patternEnabled
        }
        binding.layoutPatternOptions.visibility = if (card.patternEnabled) View.VISIBLE else View.GONE

        // Pattern shape
        val shapeIdx = shapes.indexOf(card.patternShape).coerceAtLeast(0)
        if (binding.spinnerPatternShape.selectedItemPosition != shapeIdx) binding.spinnerPatternShape.setSelection(shapeIdx)

        // Custom image info (shown below spinner when custom image is set)
        if (card.patternShape == DecorationShape.CUSTOM_IMAGE && card.patternCustomImagePath != null) {
            binding.layoutPatternCustomImage.visibility = View.VISIBLE
            binding.tvPatternCustomImageName.text = File(card.patternCustomImagePath).name
        } else {
            binding.layoutPatternCustomImage.visibility = View.GONE
        }

        // Pattern density
        val density = card.patternDensity.coerceIn(0f, 1f)
        if (binding.sliderPatternDensity.value != density) binding.sliderPatternDensity.value = density

        // Pattern color
        if (binding.cpvPatternColor.colorHex != card.patternColor) binding.cpvPatternColor.colorHex = card.patternColor
        binding.tvPatternColorHex.text = card.patternColor

        // Custom image options
        if (card.patternShape == DecorationShape.CUSTOM_IMAGE) {
            binding.cbPatternCustomTint.visibility = View.VISIBLE
            if (binding.cbPatternCustomTint.isChecked != card.patternCustomImageTintEnabled) {
                binding.cbPatternCustomTint.isChecked = card.patternCustomImageTintEnabled
            }
            binding.layoutPatternColor.visibility = if (card.patternCustomImageTintEnabled) View.VISIBLE else View.GONE
        } else {
            binding.cbPatternCustomTint.visibility = View.GONE
            binding.layoutPatternColor.visibility = View.VISIBLE
        }
        updating = false
    }

    private fun copyBgImage(uri: Uri) {
        val dir = viewModel.getImageDirForCurrentTemplate()
        val result = ImageUtils.copyAndFixExif(requireContext(), uri, dir) ?: return
        viewModel.updateCardBackgroundImage(result.file.absolutePath)
        updateImageLabel(result.file.absolutePath)
    }

    private fun copyPatternImage(uri: Uri) {
        val dir = viewModel.getImageDirForCurrentTemplate()
        val result = ImageUtils.copyAndFixExif(requireContext(), uri, dir) ?: return
        viewModel.updatePatternCustomImage(result.file.absolutePath)
    }

    private fun updateImageLabel(path: String?) {
        if (_binding == null) return
        if (path != null) {
            binding.tvBgImagePath.text = File(path).name
            binding.layoutImageInfo.visibility = View.VISIBLE
        } else {
            binding.layoutImageInfo.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

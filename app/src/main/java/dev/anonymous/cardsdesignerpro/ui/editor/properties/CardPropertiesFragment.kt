package dev.anonymous.cardsdesignerpro.ui.editor.properties

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dev.anonymous.cardsdesignerpro.data.model.ImageScaleType
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropCardBinding
import dev.anonymous.cardsdesignerpro.ui.common.ColorHexDialogSupport
import dev.anonymous.cardsdesignerpro.ui.common.ImageSourceBottomSheet
import dev.anonymous.cardsdesignerpro.ui.editor.EditorUiState
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel
import dev.anonymous.cardsdesignerpro.util.ImageUtils
import java.io.File

class CardPropertiesFragment : Fragment(), PropertyFragment {

    private var _binding: FragmentPropCardBinding? = null
    private val binding get() = _binding!!
    val viewModel: EditorViewModel by activityViewModels()
    private var updating = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPropCardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ColorHexDialogSupport.registerResultListener(
            fragment = this,
            owner = viewLifecycleOwner,
            requestKey = REQ_CARD_BG_COLOR_HEX
        ) { hex ->
            applyBgColor(hex)
        }

        populate(viewModel.activeCardStyle)

        binding.cpvBgColor.onColorSelected = { hex -> applyBgColor(hex) }
        binding.fieldBgColorHex.setOnClickListener { openBgColorHexDialog() }

        binding.btnChooseBgImage.setOnClickListener {
            ImageSourceBottomSheet.show(parentFragmentManager, REQ_BG_IMAGE)
        }

        // Listen for image source result
        parentFragmentManager.setFragmentResultListener(
            REQ_BG_IMAGE, viewLifecycleOwner
        ) { _, bundle ->
            @Suppress("DEPRECATION")
            val uri = bundle.getParcelable<Uri>(ImageSourceBottomSheet.KEY_URI)
                ?: return@setFragmentResultListener
            copyBgImage(uri)
        }
        binding.btnDeleteBgImage.setOnClickListener {
            viewModel.updateCardBackgroundImage(null)
            updateImageLabel(null)
        }

        binding.toggleBgScale.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (updating || !isChecked) return@addOnButtonCheckedListener
            val type = if (checkedId == binding.btnScaleFit.id) {
                ImageScaleType.FIT_XY.name
            } else {
                ImageScaleType.CENTER_CROP.name
            }
            viewModel.updateCardBackgroundScale(type)
        }
    }

    override fun onUiStateChanged(state: EditorUiState) {
        if (_binding == null) return
        populate(viewModel.activeCardStyle)
    }

    private fun populate(card: dev.anonymous.cardsdesignerpro.data.model.CardStyle) {
        updating = true
        if (binding.cpvBgColor.colorHex != card.backgroundColor) binding.cpvBgColor.colorHex = card.backgroundColor
        binding.fieldBgColorHex.text = card.backgroundColor
        updateImageLabel(card.backgroundImagePath)

        val activeBtn = if (card.backgroundImageScaleType == ImageScaleType.CENTER_CROP.name) {
            binding.btnScaleCrop.id
        } else {
            binding.btnScaleFit.id
        }
        if (binding.toggleBgScale.checkedButtonId != activeBtn) {
            binding.toggleBgScale.check(activeBtn)
        }
        updating = false
    }

    private fun openBgColorHexDialog() {
        ColorHexDialogSupport.showDialog(
            fragment = this,
            requestKey = REQ_CARD_BG_COLOR_HEX,
            dialogTag = CARD_BG_COLOR_DIALOG_TAG,
            initialHex = viewModel.activeCardStyle.backgroundColor,
            enableAlpha = binding.cpvBgColor.enableAlpha
        )
    }

    private fun applyBgColor(hex: String) {
        binding.fieldBgColorHex.text = hex
        if (binding.cpvBgColor.colorHex != hex) binding.cpvBgColor.colorHex = hex
        viewModel.updateCardBackgroundColor(hex)
    }

    private fun copyBgImage(uri: Uri) {
        val dir = viewModel.getImageDirForCurrentTemplate()
        val result = ImageUtils.copyAndFixExif(requireContext(), uri, dir) ?: return
        viewModel.updateCardBackgroundImage(result.file.absolutePath)
        updateImageLabel(result.file.absolutePath)
    }

    private fun updateImageLabel(path: String?) {
        if (_binding == null) return
        if (path != null) {
            binding.tvBgImagePath.text = File(path).name
            binding.layoutImageInfo.visibility = View.VISIBLE
            binding.toggleBgScale.visibility = View.VISIBLE
        } else {
            binding.layoutImageInfo.visibility = View.GONE
            binding.toggleBgScale.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val REQ_CARD_BG_COLOR_HEX = "req_card_bg_color_hex"
        private const val CARD_BG_COLOR_DIALOG_TAG = "card_bg_color_hex_dialog"
        private const val REQ_BG_IMAGE = "req_bg_image"
    }
}

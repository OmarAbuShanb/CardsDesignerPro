package dev.anonymous.cardsdesignerpro.ui.editor.properties

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dev.anonymous.cardsdesignerpro.databinding.FragmentPropCardBinding
import dev.anonymous.cardsdesignerpro.ui.editor.EditorUiState
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel
import dev.anonymous.cardsdesignerpro.util.ImageUtils
import java.io.File

class CardPropertiesFragment : Fragment(), PropertyFragment {

    private var _binding: FragmentPropCardBinding? = null
    private val binding get() = _binding!!
    val viewModel: EditorViewModel by activityViewModels()

    private val pickBgImageLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { copyBgImage(it) } }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPropCardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val card = viewModel.currentTemplate.card
        binding.cpvBgColor.colorHex = card.backgroundColor
        binding.tvBgColorHex.text = card.backgroundColor
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
        updateImageLabel(card.backgroundImagePath)
    }

    override fun onUiStateChanged(state: EditorUiState) {
        if (_binding == null) return
        val card = state.template.card
        binding.cpvBgColor.colorHex = card.backgroundColor
        binding.tvBgColorHex.text = card.backgroundColor
        updateImageLabel(card.backgroundImagePath)
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
        } else {
            binding.layoutImageInfo.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

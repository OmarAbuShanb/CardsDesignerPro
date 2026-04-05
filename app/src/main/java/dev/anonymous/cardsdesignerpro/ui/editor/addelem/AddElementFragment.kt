package dev.anonymous.cardsdesignerpro.ui.editor.addelem

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dev.anonymous.cardsdesignerpro.databinding.FragmentAddElementBinding
import dev.anonymous.cardsdesignerpro.ui.editor.EditorUiState
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel
import dev.anonymous.cardsdesignerpro.util.ImageUtils

class AddElementFragment : Fragment() {

    private var _binding: FragmentAddElementBinding? = null
    private val binding get() = _binding!!
    val viewModel: EditorViewModel by activityViewModels()

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { addImage(it) } }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddElementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
        updateButtonStates()
    }

    private fun setupButtons() {
        binding.btnAddText.setOnClickListener { viewModel.addTextElement() }
        binding.btnAddDate.setOnClickListener { viewModel.addDateElement() }
        binding.btnAddImage.setOnClickListener {
            pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnAddUsername.setOnClickListener {
            if (!viewModel.hasUsernameElement()) viewModel.addUsernameElement()
        }
        binding.btnAddPassword.setOnClickListener {
            if (!viewModel.hasPasswordElement()) viewModel.addPasswordElement()
        }
        binding.btnAddQr.setOnClickListener {
            if (!viewModel.hasQrElement()) viewModel.addQrElement()
        }
        binding.btnAddFrame.setOnClickListener {
            if (!viewModel.hasFrameElement()) viewModel.addFrameElement()
        }
        binding.btnAddDecoration.setOnClickListener {
            if (!viewModel.hasDecorationElement()) viewModel.addBackgroundDecoration()
        }
        binding.btnAddPack.setOnClickListener {
            PackBrowserBottomSheet().show(parentFragmentManager, "pack_browser")
        }
    }

    fun onUiStateChanged(state: EditorUiState) {
        if (_binding == null) return
        updateButtonStates()
    }

    private fun updateButtonStates() {
        if (_binding == null) return
        binding.btnAddUsername.isEnabled   = !viewModel.hasUsernameElement()
        binding.btnAddPassword.isEnabled   = !viewModel.hasPasswordElement()
        binding.btnAddQr.isEnabled         = !viewModel.hasQrElement()
        binding.btnAddFrame.isEnabled      = !viewModel.hasFrameElement()
        binding.btnAddDecoration.isEnabled = !viewModel.hasDecorationElement()
        binding.btnAddDate.isEnabled       = !viewModel.hasDateElement()
    }

    private fun addImage(uri: Uri) {
        val dir = viewModel.getImageDirForCurrentTemplate()
        val result = ImageUtils.copyAndFixExif(requireContext(), uri, dir) ?: return
        viewModel.addImageElement(result.file.absolutePath, result.width, result.height)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

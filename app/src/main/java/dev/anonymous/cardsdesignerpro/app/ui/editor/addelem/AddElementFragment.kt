package dev.anonymous.cardsdesignerpro.app.ui.editor.addelem

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import dev.anonymous.cardsdesignerpro.app.R
import dev.anonymous.cardsdesignerpro.app.ui.editor.addelem.PackBrowserBottomSheet
import dev.anonymous.cardsdesignerpro.app.data.license.LicenseManager
import dev.anonymous.cardsdesignerpro.app.data.license.PremiumFeature
import dev.anonymous.cardsdesignerpro.app.databinding.FragmentAddElementBinding
import dev.anonymous.cardsdesignerpro.app.ui.common.ImageSourceBottomSheet
import dev.anonymous.cardsdesignerpro.app.ui.editor.EditorViewModel
import dev.anonymous.cardsdesignerpro.app.ui.license.LicenseDialogs
import dev.anonymous.cardsdesignerpro.app.util.ImageUtils

class AddElementFragment : Fragment() {

    private var _binding: FragmentAddElementBinding? = null
    private val binding get() = _binding!!
    val viewModel: EditorViewModel by activityViewModels()

    companion object {
        private const val REQ_ADD_IMAGE = "req_add_image"
    }

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
        listenForPackResult()
        listenForImageResult()
    }

    private fun setupButtons() {
        binding.btnAddText.setOnClickListener { showAddTextDialog() }
        binding.btnAddDate.setOnClickListener { viewModel.addDateElement() }
        binding.btnAddImage.setOnClickListener {
            ImageSourceBottomSheet.show(parentFragmentManager, REQ_ADD_IMAGE)
        }
        binding.btnAddUsername.setOnClickListener {
            if (!viewModel.hasNormalUsernameElement()) viewModel.addUsernameElement(isShort = false)
        }
        binding.btnAddPassword.setOnClickListener {
            if (!viewModel.hasNormalPasswordElement()) viewModel.addPasswordElement(isShort = false)
        }
        binding.btnAddShortUsername.setOnClickListener {
            if (!viewModel.hasShortUsernameElement()) viewModel.addUsernameElement(isShort = true)
        }
        binding.btnAddShortPassword.setOnClickListener {
            if (!viewModel.hasShortPasswordElement()) viewModel.addPasswordElement(isShort = true)
        }
        binding.btnAddQr.setOnClickListener {
            if (!viewModel.hasQrElement()) viewModel.addQrElement()
        }
        binding.btnAddFrame.setOnClickListener {
            if (!viewModel.hasFrameElement()) viewModel.addFrameElement()
        }
        binding.btnAddShape.setOnClickListener {
            viewModel.addShapeElement()
        }
        binding.btnAddLine.setOnClickListener {
            viewModel.addLineElement()
        }

        binding.btnAddPack.setOnClickListener {
            PackBrowserBottomSheet().show(parentFragmentManager, "pack_browser")
        }
    }

    private fun listenForPackResult() {
        parentFragmentManager.setFragmentResultListener(
            PackBrowserBottomSheet.Companion.RESULT_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val packPath = bundle.getString(PackBrowserBottomSheet.Companion.KEY_PACK_PATH) ?: return@setFragmentResultListener
            val category = bundle.getString(PackBrowserBottomSheet.Companion.KEY_PACK_CATEGORY) ?: ""
            // Read asset dimensions so the element gets correct aspect ratio
            val assetPath = packPath.removePrefix("pack:")
            val (w, h) = readAssetDimensions(assetPath)
            viewModel.addPackElement(packPath, category, w, h)
        }
    }

    private fun listenForImageResult() {
        parentFragmentManager.setFragmentResultListener(
            REQ_ADD_IMAGE, viewLifecycleOwner
        ) { _, bundle ->
            @Suppress("DEPRECATION")
            val uri = bundle.getParcelable<Uri>(ImageSourceBottomSheet.KEY_URI)
                ?: return@setFragmentResultListener
            addImage(uri)
        }
    }

    /** Parses an SVG or image from assets and returns (width, height) in px. Returns (0,0) on failure. */
    private fun readAssetDimensions(assetPath: String): Pair<Int, Int> {
        return runCatching {
            if (assetPath.endsWith(".svg", ignoreCase = true)) {
                val svg = requireContext().assets.open(assetPath).use {
                    com.caverock.androidsvg.SVG.getFromInputStream(it)
                }
                val w = if (svg.documentWidth > 0f) svg.documentWidth.toInt() else 24
                val h = if (svg.documentHeight > 0f) svg.documentHeight.toInt() else 24
                w to h
            } else {
                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                requireContext().assets.open(assetPath).use {
                    android.graphics.BitmapFactory.decodeStream(it, null, options)
                }
                val w = if (options.outWidth > 0) options.outWidth else 24
                val h = if (options.outHeight > 0) options.outHeight else 24
                w to h
            }
        }.getOrDefault(0 to 0)
    }

    /** Inflates [R.layout.dialog_add_text] and shows a Material dialog for collecting new text. */
    private fun showAddTextDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_text, null, false)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.et_text_input)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_add_text_title)
            .setView(dialogView)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                val text = editText.text?.toString()?.trim() ?: ""
                viewModel.addTextElement(text)
            }
            .create()

        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        dialog.show()
        editText.requestFocus()
    }

    fun onUiStateChanged() {
        if (_binding == null) return
        updateButtonStates()
    }

    private fun updateButtonStates() {
        if (_binding == null) return
        val mode = viewModel.currentTemplate.credentialMode

        val isShortMode = mode == dev.anonymous.cardsdesignerpro.app.data.model.CredentialMode.SHORT
        val isUsernameOnly = mode == dev.anonymous.cardsdesignerpro.app.data.model.CredentialMode.USERNAME_ONLY

        // Short credentials row: visible only in SHORT mode
        binding.layoutShortCredentials.visibility = if (isShortMode) View.VISIBLE else View.GONE

        val hasAnyNormal = viewModel.hasNormalUsernameElement() || viewModel.hasNormalPasswordElement()
        val hasAnyShort = viewModel.hasShortUsernameElement() || viewModel.hasShortPasswordElement()

        when (mode) {
            dev.anonymous.cardsdesignerpro.app.data.model.CredentialMode.NORMAL -> {
                binding.btnAddUsername.isEnabled = !viewModel.hasNormalUsernameElement()
                binding.btnAddPassword.isEnabled = !viewModel.hasNormalPasswordElement()
            }
            dev.anonymous.cardsdesignerpro.app.data.model.CredentialMode.SHORT -> {
                // Normal buttons disabled when short elements exist and vice versa
                binding.btnAddUsername.isEnabled = !viewModel.hasNormalUsernameElement() && !hasAnyShort
                binding.btnAddPassword.isEnabled = !viewModel.hasNormalPasswordElement() && !hasAnyShort
                binding.btnAddShortUsername.isEnabled = !viewModel.hasShortUsernameElement() && !hasAnyNormal
                binding.btnAddShortPassword.isEnabled = !viewModel.hasShortPasswordElement() && !hasAnyNormal
            }
            dev.anonymous.cardsdesignerpro.app.data.model.CredentialMode.USERNAME_ONLY -> {
                binding.btnAddUsername.isEnabled = !viewModel.hasNormalUsernameElement()
                // Password buttons disabled in USERNAME_ONLY mode
                binding.btnAddPassword.isEnabled = false
            }
        }

        binding.btnAddQr.isEnabled         = !viewModel.hasQrElement()
        binding.btnAddFrame.isEnabled      = !viewModel.hasFrameElement()
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

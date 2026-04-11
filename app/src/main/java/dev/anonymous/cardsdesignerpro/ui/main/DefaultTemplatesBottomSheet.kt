package dev.anonymous.cardsdesignerpro.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.anonymous.cardsdesignerpro.databinding.BottomSheetDefaultTemplatesBinding
import kotlinx.coroutines.launch

class DefaultTemplatesBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDefaultTemplatesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDefaultTemplatesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = DefaultTemplateAdapter { dirName, template ->
            val dialogBinding = dev.anonymous.cardsdesignerpro.databinding.DialogTemplateNameBinding.inflate(layoutInflater)
            dialogBinding.etName.setText("${template.name} - نسخة")
            dialogBinding.etName.selectAll()
            
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(dev.anonymous.cardsdesignerpro.R.string.btn_edit_default_template)
                .setView(dialogBinding.root)
                .setNegativeButton(dev.anonymous.cardsdesignerpro.R.string.btn_cancel, null)
                .setPositiveButton(dev.anonymous.cardsdesignerpro.R.string.btn_save) { _, _ ->
                    val enteredName = dialogBinding.etName.text?.toString()?.trim()
                    if (!enteredName.isNullOrEmpty()) {
                        viewModel.extractDefaultTemplate(dirName, enteredName) { newId ->
                            if (newId != null) {
                                (activity as? MainActivity)?.openEditor(newId)
                                dismiss()
                            } else {
                                (activity as? MainActivity)?.snack("فشل استخراج القالب الافتراضي")
                            }
                        }
                    }
                }
                .show()
        }
        binding.rvDefaultTemplates.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            val templates = viewModel.getDefaultTemplates()
            adapter.submitList(templates)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DefaultTemplatesBottomSheet"
    }
}

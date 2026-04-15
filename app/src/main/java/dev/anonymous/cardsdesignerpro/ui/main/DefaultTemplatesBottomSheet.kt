package dev.anonymous.cardsdesignerpro.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
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
            val reqKey = "extract_${dirName}_${template.id}"
            childFragmentManager.setFragmentResultListener(reqKey, viewLifecycleOwner) { _, bundle ->
                val enteredName = bundle.getString("name")
                if (!enteredName.isNullOrEmpty()) {
                    viewModel.extractDefaultTemplate(dirName, enteredName) { newId ->
                        if (newId != null) {
                            (activity as? MainActivity)?.openEditor(newId)
                            dismiss()
                        } else {
                            (activity as? MainActivity)?.snack(getString(dev.anonymous.cardsdesignerpro.R.string.error_extracting_template))
                        }
                    }
                }
            }
            if (childFragmentManager.findFragmentByTag(dev.anonymous.cardsdesignerpro.ui.common.TemplateNameDialogFragment.TAG) == null) {
                dev.anonymous.cardsdesignerpro.ui.common.TemplateNameDialogFragment.newInstance(
                    titleRes = dev.anonymous.cardsdesignerpro.R.string.btn_edit_default_template,
                    positiveBtnRes = dev.anonymous.cardsdesignerpro.R.string.btn_save,
                    initialName = getString(dev.anonymous.cardsdesignerpro.R.string.template_name_copy, template.name),
                    requestKey = reqKey
                ).show(childFragmentManager, dev.anonymous.cardsdesignerpro.ui.common.TemplateNameDialogFragment.TAG)
            }
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

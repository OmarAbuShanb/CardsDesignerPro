package dev.anonymous.cardsdesignerpro.app.ui.common

import android.content.Context
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.anonymous.cardsdesignerpro.app.R
import dev.anonymous.cardsdesignerpro.app.databinding.DialogTemplateNameBinding

class TemplateNameDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "TemplateNameDialogFragment"
        private const val ARG_TITLE = "title"
        private const val ARG_POSITIVE_BTN = "positive_btn"
        private const val ARG_INITIAL_NAME = "initial_name"
        private const val ARG_REQUEST_KEY = "request_key"

        fun newInstance(titleRes: Int, positiveBtnRes: Int, initialName: String?, requestKey: String): TemplateNameDialogFragment {
            return TemplateNameDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TITLE, titleRes)
                    putInt(ARG_POSITIVE_BTN, positiveBtnRes)
                    putString(ARG_INITIAL_NAME, initialName)
                    putString(ARG_REQUEST_KEY, requestKey)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val binding = DialogTemplateNameBinding.inflate(layoutInflater)
        val args = requireArguments()
        val titleRes = args.getInt(ARG_TITLE)
        val positiveBtnRes = args.getInt(ARG_POSITIVE_BTN)
        val initialName = args.getString(ARG_INITIAL_NAME)
        val requestKey = args.getString(ARG_REQUEST_KEY)!!

        if (!initialName.isNullOrEmpty()) {
            binding.etName.setText(initialName)
            binding.etName.post { binding.etName.selectAll() }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setView(binding.root)
            .setCancelable(false)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(positiveBtnRes) { _, _ ->
                val name = binding.etName.text?.toString()?.trim()
                if (!name.isNullOrEmpty()) {
                    parentFragmentManager.setFragmentResult(requestKey, Bundle().apply { putString("name", name) })
                }
            }
            .create()

        dialog.setOnShowListener {
            val btnPositive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btnPositive.isEnabled = !binding.etName.text?.toString()?.trim().isNullOrEmpty()
            focusAndShowKeyboard(binding.etName)

            binding.etName.doAfterTextChanged { editable ->
                btnPositive.isEnabled = !editable?.toString()?.trim().isNullOrEmpty()
            }

            binding.etName.setOnEditorActionListener { _, _, _ ->
                val name = binding.etName.text?.toString()?.trim()
                if (!name.isNullOrEmpty()) {
                    parentFragmentManager.setFragmentResult(requestKey, Bundle().apply { putString("name", name) })
                    dialog.dismiss()
                }
                true
            }
        }

        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )

        return dialog
    }

    private fun focusAndShowKeyboard(editText: EditText) {
        editText.requestFocus()
        editText.post {
            val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}

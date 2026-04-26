package dev.anonymous.cardsdesignerpro.ui.main

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.data.serializer.AppJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class ImportTemplatesDialogFragment : DialogFragment() {

    private lateinit var items: MutableList<SelectionItem>
    private lateinit var inputUri: String

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        items = restoreItems(savedInstanceState)
        inputUri = restoreInputUri(savedInstanceState)

        var updatePositiveState: (() -> Unit)? = null
        val view = layoutInflater.inflate(R.layout.dialog_select_templates, null)
        val recyclerView = view.findViewById<RecyclerView>(R.id.rv_select_templates)
        val selectionAdapter = TemplateSelectionAdapter(items) {
            updatePositiveState?.invoke()
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = selectionAdapter

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_import_templates_title)
            .setView(view)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_import_selected_templates, null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            updatePositiveState = {
                positiveButton.isEnabled = selectionAdapter.getSelectedIds().isNotEmpty()
            }
            updatePositiveState?.invoke()

            positiveButton.setOnClickListener {
                val selectedIds = selectionAdapter.getSelectedIds()
                if (selectedIds.isEmpty()) return@setOnClickListener

                if (items.any { it.isSelected && it.statusText != null }) {
                    showOverrideWarning(selectedIds)
                } else {
                    sendResult(selectedIds)
                    dismiss()
                }
            }
        }

        return dialog
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ITEMS_JSON, serializeItems(items))
        outState.putString(STATE_INPUT_URI, inputUri)
    }

    private fun showOverrideWarning(selectedIds: Set<String>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_warning_title)
            .setMessage(R.string.import_warning_message)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                sendResult(selectedIds)
                dismiss()
            }
            .show()
    }

    private fun sendResult(selectedIds: Set<String>) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putString(RESULT_URI, inputUri)
                putStringArrayList(RESULT_SELECTED_IDS, ArrayList(selectedIds))
            }
        )
    }

    private fun restoreItems(savedInstanceState: Bundle?): MutableList<SelectionItem> {
        val json = savedInstanceState?.getString(STATE_ITEMS_JSON)
            ?: requireArguments().getString(ARG_ITEMS_JSON)
            ?: error("Missing import dialog items")
        return deserializeItems(json).toMutableList()
    }

    private fun restoreInputUri(savedInstanceState: Bundle?): String {
        return savedInstanceState?.getString(STATE_INPUT_URI)
            ?: requireArguments().getString(ARG_INPUT_URI)
            ?: error("Missing import uri")
    }

    companion object {
        const val TAG = "ImportTemplatesDialog"
        const val REQUEST_KEY = "import_templates_dialog_request"
        const val RESULT_URI = "result_uri"
        const val RESULT_SELECTED_IDS = "result_selected_ids"

        private const val ARG_ITEMS_JSON = "arg_items_json"
        private const val ARG_INPUT_URI = "arg_input_uri"
        private const val STATE_ITEMS_JSON = "state_items_json"
        private const val STATE_INPUT_URI = "state_input_uri"

        fun newInstance(uri: String, items: List<SelectionItem>): ImportTemplatesDialogFragment {
            return ImportTemplatesDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_INPUT_URI, uri)
                    putString(ARG_ITEMS_JSON, serializeItems(items))
                }
            }
        }

        private fun serializeItems(items: List<SelectionItem>): String {
            return AppJson.instance.encodeToString(ListSerializer(SelectionItem.serializer()), items)
        }

        private fun deserializeItems(json: String): List<SelectionItem> {
            return AppJson.instance.decodeFromString(ListSerializer(SelectionItem.serializer()), json)
        }
    }
}

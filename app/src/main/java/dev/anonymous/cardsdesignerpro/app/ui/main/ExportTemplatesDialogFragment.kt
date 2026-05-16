package dev.anonymous.cardsdesignerpro.app.ui.main

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.anonymous.cardsdesignerpro.app.R
import dev.anonymous.cardsdesignerpro.app.data.serializer.AppJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class ExportTemplatesDialogFragment : DialogFragment() {

    private lateinit var items: MutableList<SelectionItem>

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        items = restoreItems(savedInstanceState)

        var updatePositiveState: (() -> Unit)? = null
        val view = layoutInflater.inflate(R.layout.dialog_select_templates, null)
        val recyclerView = view.findViewById<RecyclerView>(R.id.rv_select_templates)
        val selectionAdapter = TemplateSelectionAdapter(items) {
            updatePositiveState?.invoke()
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = selectionAdapter

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_export_templates_title)
            .setView(view)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_export_selected_templates, null)
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
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    Bundle().apply {
                        putStringArrayList(RESULT_SELECTED_IDS, ArrayList(selectedIds))
                    }
                )
                dismiss()
            }
        }

        return dialog
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ITEMS_JSON, serializeItems(items))
    }

    private fun restoreItems(savedInstanceState: Bundle?): MutableList<SelectionItem> {
        val json = savedInstanceState?.getString(STATE_ITEMS_JSON)
            ?: requireArguments().getString(ARG_ITEMS_JSON)
            ?: error("Missing export dialog items")
        return deserializeItems(json).toMutableList()
    }

    companion object {
        const val TAG = "ExportTemplatesDialog"
        const val REQUEST_KEY = "export_templates_dialog_request"
        const val RESULT_SELECTED_IDS = "result_selected_ids"

        private const val ARG_ITEMS_JSON = "arg_items_json"
        private const val STATE_ITEMS_JSON = "state_items_json"

        fun newInstance(items: List<SelectionItem>): ExportTemplatesDialogFragment {
            return ExportTemplatesDialogFragment().apply {
                arguments = Bundle().apply {
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

package dev.anonymous.cardsdesignerpro.app.ui.export

import android.app.Dialog
import android.os.Bundle
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.anonymous.cardsdesignerpro.app.R
import dev.anonymous.cardsdesignerpro.app.common.widget.StepperView

/**
 * A configuration-change safe dialog that lets the user pick a custom card layout
 * by adjusting columns (2–10) and rows (5–20) via [StepperView] steppers.
 *
 * Communicates the result back via [FragmentResult] using [REQUEST_KEY].
 */
class CustomLayoutDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val initCols = requireArguments().getInt(ARG_COLUMNS)
        val initRows = requireArguments().getInt(ARG_ROWS)

        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_layout, null)
        val stepperColumns = dialogView.findViewById<StepperView>(R.id.stepper_columns)
        val stepperRows = dialogView.findViewById<StepperView>(R.id.stepper_rows)
        val tvCardsCount = dialogView.findViewById<TextView>(R.id.tv_cards_count)

        fun updateCount() {
            val total = stepperColumns.value * stepperRows.value
            tvCardsCount.text = getString(R.string.label_cards_per_page_count, total)
        }

        // Restore from savedInstanceState if available (config change), otherwise use args
        val cols = savedInstanceState?.getInt(STATE_COLUMNS) ?: initCols
        val rows = savedInstanceState?.getInt(STATE_ROWS) ?: initRows

        stepperColumns.minValue = 2
        stepperColumns.maxValue = 10
        stepperColumns.onValueChanged = null
        stepperColumns.value = cols.coerceIn(2, 10)
        stepperColumns.onValueChanged = { updateCount() }

        stepperRows.minValue = 5
        stepperRows.maxValue = 20
        stepperRows.onValueChanged = null
        stepperRows.value = rows.coerceIn(5, 20)
        stepperRows.onValueChanged = { updateCount() }

        updateCount()

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_custom_layout_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    Bundle().apply {
                        putString(RESULT_ACTION, ACTION_CONFIRM)
                        putInt(RESULT_COLUMNS, stepperColumns.value)
                        putInt(RESULT_ROWS, stepperRows.value)
                    }
                )
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                sendDismissResult()
            }
            .create()
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        sendDismissResult()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Persist current stepper values across config changes
        val d = dialog ?: return
        val stepperColumns = d.findViewById<StepperView>(R.id.stepper_columns)
        val stepperRows = d.findViewById<StepperView>(R.id.stepper_rows)
        if (stepperColumns != null) outState.putInt(STATE_COLUMNS, stepperColumns.value)
        if (stepperRows != null) outState.putInt(STATE_ROWS, stepperRows.value)
    }

    private fun sendDismissResult() {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putString(RESULT_ACTION, ACTION_DISMISS)
            }
        )
    }

    companion object {
        const val TAG = "CustomLayoutDialog"
        const val REQUEST_KEY = "custom_layout_request"
        const val RESULT_ACTION = "result_action"
        const val RESULT_COLUMNS = "result_columns"
        const val RESULT_ROWS = "result_rows"
        const val ACTION_CONFIRM = "action_confirm"
        const val ACTION_DISMISS = "action_dismiss"

        private const val ARG_COLUMNS = "arg_columns"
        private const val ARG_ROWS = "arg_rows"
        private const val STATE_COLUMNS = "state_columns"
        private const val STATE_ROWS = "state_rows"

        fun newInstance(columns: Int, rows: Int): CustomLayoutDialogFragment {
            return CustomLayoutDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_COLUMNS, columns)
                    putInt(ARG_ROWS, rows)
                }
            }
        }
    }
}

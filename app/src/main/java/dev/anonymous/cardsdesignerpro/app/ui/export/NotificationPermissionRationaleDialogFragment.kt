package dev.anonymous.cardsdesignerpro.app.ui.export

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.anonymous.cardsdesignerpro.app.R

class NotificationPermissionRationaleDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val isSeparate = requireArguments().getBoolean(ARG_IS_SEPARATE)

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.notification_permission_dialog_title)
            .setMessage(R.string.notification_permission_dialog_message)
            .setNegativeButton(R.string.btn_cancel, null)
            .setNeutralButton(R.string.notification_permission_dialog_export_anyway) { _, _ ->
                sendResult(ACTION_EXPORT_ANYWAY, isSeparate)
            }
            .setPositiveButton(R.string.notification_permission_dialog_allow) { _, _ ->
                sendResult(ACTION_REQUEST_PERMISSION, isSeparate)
            }
            .create()
    }

    private fun sendResult(action: String, isSeparate: Boolean) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putString(RESULT_ACTION, action)
                putBoolean(RESULT_IS_SEPARATE, isSeparate)
            }
        )
    }

    companion object {
        const val TAG = "NotificationPermissionRationaleDialog"
        const val REQUEST_KEY = "notification_permission_request"
        const val RESULT_ACTION = "result_action"
        const val RESULT_IS_SEPARATE = "result_is_separate"
        const val ACTION_REQUEST_PERMISSION = "action_request_permission"
        const val ACTION_EXPORT_ANYWAY = "action_export_anyway"

        private const val ARG_IS_SEPARATE = "arg_is_separate"

        fun newInstance(isSeparate: Boolean): NotificationPermissionRationaleDialogFragment {
            return NotificationPermissionRationaleDialogFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_IS_SEPARATE, isSeparate)
                }
            }
        }
    }
}

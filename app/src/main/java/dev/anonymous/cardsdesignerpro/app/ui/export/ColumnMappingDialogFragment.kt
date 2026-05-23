package dev.anonymous.cardsdesignerpro.app.ui.export

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.os.Bundle as AndroidBundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.anonymous.cardsdesignerpro.app.R
import dev.anonymous.cardsdesignerpro.app.databinding.DialogColumnMappingBinding

/**
 * Material dialog that lets the user manually select which columns
 * in their data file correspond to username and password.
 *
 * When the user selects a header in one spinner, that header is excluded
 * from the other spinner to prevent duplicate mapping.
 *
 * Validation prevents the user from confirming without selecting the
 * required columns for the current credential mode.
 *
 * Returns the selection via [setFragmentResult] with key [RESULT_KEY].
 */
class ColumnMappingDialogFragment : DialogFragment() {

    private var _binding: DialogColumnMappingBinding? = null
    private val binding get() = _binding!!

    /** Full header list from the file. */
    private lateinit var headers: List<String>
    private lateinit var noneLabel: String

    /** Whether the template only needs username (no password). */
    private var isUsernameOnly = false

    /** Suppress listener feedback during programmatic updates. */
    private var suppressListeners = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogColumnMappingBinding.inflate(layoutInflater)

        headers = requireArguments().getStringArrayList(ARG_HEADERS) ?: emptyList()
        val uri = requireArguments().getString(ARG_URI) ?: ""
        val isShortFile = requireArguments().getBoolean(ARG_IS_SHORT, false)
        val currentUsername = requireArguments().getString(ARG_CURRENT_USERNAME)
        val currentPassword = requireArguments().getString(ARG_CURRENT_PASSWORD)
        val fileName = requireArguments().getString(ARG_FILE_NAME) ?: ""
        isUsernameOnly = requireArguments().getBoolean(ARG_USERNAME_ONLY, false)

        noneLabel = getString(R.string.label_column_none)

        // Show filename so user knows which file this dialog is for
        if (fileName.isNotEmpty()) {
            binding.tvMappingFilename.text = fileName
            binding.tvMappingFilename.visibility = View.VISIBLE
        } else {
            binding.tvMappingFilename.visibility = View.GONE
        }

        // Hide password spinner in USERNAME_ONLY mode
        binding.layoutPasswordColumn.visibility =
            if (isUsernameOnly) View.GONE else View.VISIBLE

        // Initial population — no exclusions yet
        rebuildSpinners(excludeFromUser = null, excludeFromPass = null)

        // Pre-select saved values if available
        if (currentUsername != null || currentPassword != null) {
            suppressListeners = true
            if (currentUsername != null) {
                val userOptions = buildOptionsList(exclude = null)
                val idx = userOptions.indexOf(currentUsername)
                if (idx >= 0) binding.spinnerUsernameColumn.setSelection(idx)
                // Now exclude username from password spinner
                rebuildPasswordSpinner(exclude = currentUsername)
            }
            if (currentPassword != null && !isUsernameOnly) {
                val passOptions = buildOptionsList(exclude = currentUsername)
                val idx = passOptions.indexOf(currentPassword)
                if (idx >= 0) binding.spinnerPasswordColumn.setSelection(idx)
                // Now exclude password from username spinner
                rebuildUsernameSpinner(exclude = currentPassword)
            }
            suppressListeners = false
        }

        // Username selection → exclude from password
        binding.spinnerUsernameColumn.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (suppressListeners) return
                    val selectedHeader = selectedHeader(binding.spinnerUsernameColumn)
                    rebuildPasswordSpinner(exclude = selectedHeader)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

        // Password selection → exclude from username
        if (!isUsernameOnly) {
            binding.spinnerPasswordColumn.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        if (suppressListeners) return
                        val selectedHeader = selectedHeader(binding.spinnerPasswordColumn)
                        rebuildUsernameSpinner(exclude = selectedHeader)
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
        }

        // Build dialog but DON'T set positive button listener here —
        // we override it in onResume to prevent auto-dismiss.
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_column_mapping_title)
            .setView(binding.root)
            .setPositiveButton(R.string.btn_confirm, null) // null — we handle click manually
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        // Override the positive button after show() so it doesn't auto-dismiss
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val usernameCol = selectedHeader(binding.spinnerUsernameColumn)
                val passwordCol = if (isUsernameOnly) null
                    else selectedHeader(binding.spinnerPasswordColumn)

                // Validate: username is always required
                if (usernameCol == null) {
                    Toast.makeText(
                        requireContext(),
                        R.string.error_select_username_column,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                // Validate: password required when not username-only
                if (!isUsernameOnly && passwordCol == null) {
                    Toast.makeText(
                        requireContext(),
                        R.string.error_select_password_column,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                // All valid — send result and dismiss
                setFragmentResult(RESULT_KEY, AndroidBundle().apply {
                    putString(KEY_URI, uri)
                    putString(KEY_USERNAME_COL, usernameCol)
                    putString(KEY_PASSWORD_COL, passwordCol)
                    putBoolean(KEY_IS_SHORT, isShortFile)
                })
                dismiss()
            }
        }

        return dialog
    }

    /** Returns the actual header name selected in [spinner], or null if "(None)" is selected. */
    private fun selectedHeader(spinner: android.widget.Spinner): String? {
        val pos = spinner.selectedItemPosition
        val text = spinner.adapter?.getItem(pos) as? String ?: return null
        return if (text == noneLabel) null else text
    }

    /** Rebuild the password spinner excluding [exclude] from the options. */
    private fun rebuildPasswordSpinner(exclude: String?) {
        if (isUsernameOnly) return // no password spinner in this mode
        val currentSelection = selectedHeader(binding.spinnerPasswordColumn)
        val options = buildOptionsList(exclude)
        suppressListeners = true
        binding.spinnerPasswordColumn.adapter = makeAdapter(options)
        val restoreIdx = if (currentSelection != null) options.indexOf(currentSelection) else 0
        binding.spinnerPasswordColumn.setSelection(if (restoreIdx >= 0) restoreIdx else 0)
        suppressListeners = false
    }

    /** Rebuild the username spinner excluding [exclude] from the options. */
    private fun rebuildUsernameSpinner(exclude: String?) {
        val currentSelection = selectedHeader(binding.spinnerUsernameColumn)
        val options = buildOptionsList(exclude)
        suppressListeners = true
        binding.spinnerUsernameColumn.adapter = makeAdapter(options)
        val restoreIdx = if (currentSelection != null) options.indexOf(currentSelection) else 0
        binding.spinnerUsernameColumn.setSelection(if (restoreIdx >= 0) restoreIdx else 0)
        suppressListeners = false
    }

    /** Rebuild both spinners with no exclusions (initial state). */
    private fun rebuildSpinners(excludeFromUser: String?, excludeFromPass: String?) {
        suppressListeners = true
        binding.spinnerUsernameColumn.adapter = makeAdapter(buildOptionsList(excludeFromUser))
        binding.spinnerPasswordColumn.adapter = makeAdapter(buildOptionsList(excludeFromPass))
        suppressListeners = false
    }

    private fun buildOptionsList(exclude: String?): List<String> {
        val filtered = if (exclude != null) headers.filter { it != exclude } else headers
        return listOf(noneLabel) + filtered
    }

    private fun makeAdapter(options: List<String>): ArrayAdapter<String> {
        return ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, options).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ColumnMappingDialog"
        const val RESULT_KEY = "column_mapping_result"
        const val KEY_URI = "uri"
        const val KEY_USERNAME_COL = "username_col"
        const val KEY_PASSWORD_COL = "password_col"
        const val KEY_IS_SHORT = "is_short"

        private const val ARG_HEADERS = "headers"
        private const val ARG_URI = "uri"
        private const val ARG_IS_SHORT = "is_short"
        private const val ARG_CURRENT_USERNAME = "current_username"
        private const val ARG_CURRENT_PASSWORD = "current_password"
        private const val ARG_FILE_NAME = "file_name"
        private const val ARG_USERNAME_ONLY = "username_only"

        fun newInstance(
            uri: Uri,
            headers: List<String>,
            isShortFile: Boolean = false,
            currentUsernameCol: String? = null,
            currentPasswordCol: String? = null,
            fileName: String? = null,
            isUsernameOnly: Boolean = false
        ) = ColumnMappingDialogFragment().apply {
            arguments = AndroidBundle().apply {
                putStringArrayList(ARG_HEADERS, ArrayList(headers))
                putString(ARG_URI, uri.toString())
                putBoolean(ARG_IS_SHORT, isShortFile)
                putString(ARG_CURRENT_USERNAME, currentUsernameCol)
                putString(ARG_CURRENT_PASSWORD, currentPasswordCol)
                putString(ARG_FILE_NAME, fileName)
                putBoolean(ARG_USERNAME_ONLY, isUsernameOnly)
            }
        }
    }
}

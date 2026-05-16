package dev.anonymous.cardsdesignerpro.app.ui.license

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.anonymous.cardsdesignerpro.app.R
import dev.anonymous.cardsdesignerpro.app.data.license.AppConstants
import dev.anonymous.cardsdesignerpro.app.data.license.DeviceIdProvider
import dev.anonymous.cardsdesignerpro.app.data.license.LicenseManager
import dev.anonymous.cardsdesignerpro.app.databinding.DialogActivationBinding

/**
 * Centralized license-related dialogs.
 * All dialogs use Arabic text from string resources and follow the project's
 * [MaterialAlertDialogBuilder] pattern.
 */
object LicenseDialogs {

    /**
     * Shows the full activation dialog with benefits, WhatsApp link, code input.
     * [onActivated] is called if activation succeeds.
     */
    fun showActivationDialog(activity: Activity, onActivated: () -> Unit) {
        val binding = DialogActivationBinding.inflate(LayoutInflater.from(activity))

        // Paste click — reads from clipboard
        binding.btnPasteCode.setOnClickListener {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (!clipText.isNullOrEmpty()) {
                binding.etActivationCode.setText(clipText)
                binding.etActivationCode.setSelection(clipText.length)
            } else {
                Snackbar.make(
                    binding.root,
                    R.string.license_clipboard_empty,
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }

        // WhatsApp click — opens wa.me
        binding.btnContactWhatsapp.setOnClickListener {
            openWhatsAppContact(activity)
        }

        // Info click
        binding.btnInfoAndroidId.setOnClickListener {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.license_android_id_info_title)
                .setMessage(R.string.license_android_id_info_message)
                .setPositiveButton(R.string.btn_close, null)
                .show()
        }

        // Copy click
        binding.btnCopyAndroidId.setOnClickListener {
            val androidId = DeviceIdProvider.getHashedId(activity)
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Android ID", androidId)
            clipboard.setPrimaryClip(clip)
            Snackbar.make(
                binding.root,
                R.string.license_android_id_copied,
                Snackbar.LENGTH_SHORT
            ).show()
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.license_activate_title)
            .setView(binding.root)
            .setPositiveButton(R.string.license_btn_activate, null) // set below to prevent auto-dismiss
            .setNegativeButton(R.string.license_btn_later, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val code = binding.etActivationCode.text?.toString()?.trim() ?: ""
                if (code.isEmpty()) {
                    binding.etActivationCode.error = activity.getString(R.string.license_error_empty_code)
                    return@setOnClickListener
                }

                val lm = LicenseManager.getInstance(activity)
                if (lm.activate(code)) {
                    dialog.dismiss()
                    Snackbar.make(
                        activity.findViewById(android.R.id.content),
                        R.string.license_activate_success,
                        Snackbar.LENGTH_LONG
                    ).show()
                    onActivated()
                } else {
                    binding.etActivationCode.error = activity.getString(R.string.license_error_invalid_code)
                }
            }
        }

        dialog.show()
    }

    /**
     * Shows the generic "premium feature" dialog for blocked features.
     * [messageResId] allows passing a feature-specific message.
     * [onActivateNow] is called if user chooses "تفعيل الآن".
     */
    fun showPremiumFeatureDialog(
        activity: Activity,
        messageResId: Int = R.string.license_premium_feature_message,
        onActivateNow: () -> Unit
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.license_premium_feature_title)
            .setMessage(messageResId)
            .setPositiveButton(R.string.license_btn_activate_now) { _, _ -> onActivateNow() }
            .setNegativeButton(R.string.license_btn_later, null)
            .show()
    }

    /**
     * Shows the "export limit reached" dialog with formatted export count.
     */
    fun showExportLimitDialog(activity: Activity, onActivateNow: () -> Unit) {
        val maxExports = AppConstants.MAX_TRIAL_EXPORTS
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.license_export_limit_title)
            .setMessage(activity.getString(R.string.license_export_limit_message, maxExports))
            .setPositiveButton(R.string.license_btn_activate_now) { _, _ -> onActivateNow() }
            .setNegativeButton(R.string.license_btn_later, null)
            .show()
    }

    /**
     * Shows the "trial expired" dialog with formatted trial days.
     */
    fun showTrialExpiredDialog(activity: Activity, onActivateNow: () -> Unit) {
        val trialDays = AppConstants.TRIAL_DURATION_DAYS
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.license_trial_expired_title)
            .setMessage(activity.getString(R.string.license_trial_expired_message, trialDays))
            .setPositiveButton(R.string.license_btn_activate_now) { _, _ -> onActivateNow() }
            .setNegativeButton(R.string.license_btn_later, null)
            .show()
    }

    /**
     * Shows the premium template dialog (replaces the old SnackBar).
     * [onActivateNow] is called if user chooses "تفعيل الآن".
     */
    fun showPremiumTemplateDialog(activity: Activity, onActivateNow: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.license_premium_feature_title)
            .setMessage(R.string.license_premium_template_message)
            .setPositiveButton(R.string.license_btn_activate_now) { _, _ -> onActivateNow() }
            .setNegativeButton(R.string.license_btn_later, null)
            .show()
    }

    /**
     * Shows the premium ready-made element dialog.
     * [onActivateNow] is called if user chooses "تفعيل الآن".
     */
    fun showPremiumReadyMadeDialog(activity: Activity, onActivateNow: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.license_premium_feature_title)
            .setMessage(R.string.license_premium_readymade_message)
            .setPositiveButton(R.string.license_btn_activate_now) { _, _ -> onActivateNow() }
            .setNegativeButton(R.string.license_btn_later, null)
            .show()
    }

    // ── WhatsApp ─────────────────────────────────────────────────────────────

    private fun openWhatsAppContact(activity: Activity) {
        val phone = AppConstants.DEVELOPER_WHATSAPP_NUMBER
        val url = "https://wa.me/${phone.replace("+", "")}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            activity.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            Snackbar.make(
                activity.findViewById(android.R.id.content),
                R.string.license_whatsapp_not_installed,
                Snackbar.LENGTH_LONG
            ).show()
        }
    }
}

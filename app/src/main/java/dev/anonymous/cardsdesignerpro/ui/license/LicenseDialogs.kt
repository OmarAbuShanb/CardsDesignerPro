package dev.anonymous.cardsdesignerpro.ui.license

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.data.license.AppConstants
import dev.anonymous.cardsdesignerpro.data.license.DeviceIdProvider
import dev.anonymous.cardsdesignerpro.data.license.LicenseManager

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
        val dialogView = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_activation, null)

        val etCode = dialogView.findViewById<EditText>(R.id.et_activation_code)
        
        val btnWhatsApp = dialogView.findViewById<View>(R.id.btn_contact_whatsapp)
        val btnInfoAndroidId = dialogView.findViewById<View>(R.id.btn_info_android_id)
        val btnCopyAndroidId = dialogView.findViewById<View>(R.id.btn_copy_android_id)

        // WhatsApp click — opens wa.me
        btnWhatsApp.setOnClickListener {
            openWhatsAppContact(activity)
        }

        // Info click
        btnInfoAndroidId.setOnClickListener {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.license_android_id_info_title)
                .setMessage(R.string.license_android_id_info_message)
                .setPositiveButton(R.string.btn_close, null)
                .show()
        }

        // Copy click
        btnCopyAndroidId.setOnClickListener {
            val androidId = DeviceIdProvider.getHashedId(activity)
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Android ID", androidId)
            clipboard.setPrimaryClip(clip)
            Snackbar.make(
                dialogView,
                R.string.license_android_id_copied,
                Snackbar.LENGTH_SHORT
            ).show()
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.license_activate_title)
            .setView(dialogView)
            .setPositiveButton(R.string.license_btn_activate, null) // set below to prevent auto-dismiss
            .setNegativeButton(R.string.license_btn_later, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val code = etCode.text?.toString()?.trim() ?: ""
                if (code.isEmpty()) {
                    etCode.error = activity.getString(R.string.license_error_empty_code)
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
                    etCode.error = activity.getString(R.string.license_error_invalid_code)
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
        val message = activity.getString(R.string.license_whatsapp_message)
        val phone = AppConstants.DEVELOPER_WHATSAPP_NUMBER
        val url = "https://wa.me/${phone.replace("+", "")}?text=${Uri.encode(message)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { activity.startActivity(intent) }
    }
}

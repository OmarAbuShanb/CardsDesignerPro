package dev.anonymous.cardsdesignerpro.keygen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Base64
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.anonymous.cardsdesignerpro.keygen.databinding.ActivityKeygenBinding
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Developer-only utility app for generating ECDSA activation codes.
 *
 * Security model (Asymmetric Cryptography):
 *   - This app holds the PRIVATE KEY → can SIGN (generate codes)
 *   - The main app holds only the PUBLIC KEY → can VERIFY but NOT generate
 *   - Even if someone decompiles the main app, they CANNOT create valid codes
 *
 * First-time setup:
 *   1. Run this app and tap "توليد زوج مفاتيح جديد"
 *   2. Copy the PUBLIC KEY that appears
 *   3. Paste it into ActivationCodeVerifier.kt in the main app (PUBLIC_KEY_BASE64)
 *   4. Rebuild the main app
 *
 * Daily usage:
 *   1. Customer sends device hash via WhatsApp
 *   2. Paste the hash here
 *   3. Tap "توليد كود التفعيل" → code appears
 *   4. Copy and send back to customer
 */
class KeygenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeygenBinding
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val PREFS_NAME = "keygen_prefs"
        private const val KEY_PRIVATE_KEY = "ecdsa_private_key_base64"
        private const val KEY_PUBLIC_KEY = "ecdsa_public_key_base64"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeygenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setupKeySection()
        setupBackupRestore()
        setupCodeGeneration()
    }

    // ── Key Pair Setup ───────────────────────────────────────────────────────

    private fun setupKeySection() {
        updateKeyStatus()

        binding.btnGenerateKeys.setOnClickListener {
            if (hasKeys()) {
                // Confirm before overwriting existing keys
                MaterialAlertDialogBuilder(this)
                    .setTitle("⚠️ تحذير")
                    .setMessage("سيتم استبدال المفاتيح الحالية.\n\nالأكواد المولّدة بالمفاتيح القديمة لن تعمل بعد الآن.\n\nمتأكد؟")
                    .setPositiveButton("نعم، استبدل") { _, _ -> generateAndSaveKeyPair() }
                    .setNegativeButton("إلغاء", null)
                    .show()
            } else {
                generateAndSaveKeyPair()
            }
        }

        binding.btnCopyPublicKey.setOnClickListener {
            val publicKey = prefs.getString(KEY_PUBLIC_KEY, null) ?: return@setOnClickListener
            copyToClipboard("public_key", publicKey)
            Snackbar.make(binding.root, R.string.msg_public_key_copied, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun generateAndSaveKeyPair() {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = keyPairGenerator.generateKeyPair()

        val privateKeyBase64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)
        val publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)

        prefs.edit()
            .putString(KEY_PRIVATE_KEY, privateKeyBase64)
            .putString(KEY_PUBLIC_KEY, publicKeyBase64)
            .apply()

        updateKeyStatus()

        MaterialAlertDialogBuilder(this)
            .setTitle("✅ تم توليد المفاتيح")
            .setMessage(getString(R.string.msg_keys_generated))
            .setPositiveButton("حسناً", null)
            .show()
    }

    private fun updateKeyStatus() {
        if (hasKeys()) {
            binding.tvKeyStatus.text = getString(R.string.key_status_ready)
            val publicKey = prefs.getString(KEY_PUBLIC_KEY, "") ?: ""
            binding.labelPublicKey.visibility = View.VISIBLE
            binding.tvPublicKey.visibility = View.VISIBLE
            binding.tvPublicKey.text = publicKey
            binding.btnCopyPublicKey.visibility = View.VISIBLE
            // Show backup section
            binding.dividerBackup.visibility = View.VISIBLE
            binding.labelBackup.visibility = View.VISIBLE
            binding.layoutBackupButtons.visibility = View.VISIBLE
        } else {
            binding.tvKeyStatus.text = getString(R.string.key_status_none)
            binding.labelPublicKey.visibility = View.GONE
            binding.tvPublicKey.visibility = View.GONE
            binding.btnCopyPublicKey.visibility = View.GONE
            binding.dividerBackup.visibility = View.GONE
            binding.labelBackup.visibility = View.GONE
            binding.layoutBackupButtons.visibility = View.GONE
        }
    }

    private fun hasKeys(): Boolean = prefs.getString(KEY_PRIVATE_KEY, null) != null

    // ── Backup / Restore ─────────────────────────────────────────────────────

    private fun setupBackupRestore() {
        binding.btnExportPrivateKey.setOnClickListener { exportPrivateKey() }
        binding.btnImportPrivateKey.setOnClickListener { showImportDialog() }
    }

    private fun exportPrivateKey() {
        val privateKey = prefs.getString(KEY_PRIVATE_KEY, null)
        if (privateKey == null) {
            Snackbar.make(binding.root, R.string.error_no_keys, Snackbar.LENGTH_SHORT).show()
            return
        }

        // Show warning dialog first, then copy
        MaterialAlertDialogBuilder(this)
            .setTitle("⚠️ تحذير مهم")
            .setMessage(getString(R.string.dialog_export_warning))
            .setPositiveButton("نسخ المفتاح") { _, _ ->
                copyToClipboard("private_key", privateKey)
                Snackbar.make(binding.root, R.string.msg_private_key_copied, Snackbar.LENGTH_LONG).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showImportDialog() {
        val editText = android.widget.EditText(this).apply {
            hint = getString(R.string.dialog_import_hint)
            textDirection = View.TEXT_DIRECTION_LTR
            isSingleLine = false
            minLines = 3
            setPadding(48, 32, 48, 32)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_import_title)
            .setMessage(R.string.dialog_import_message)
            .setView(editText)
            .setPositiveButton("استيراد") { _, _ ->
                val key = editText.text?.toString()?.trim() ?: ""
                if (key.isEmpty()) {
                    Snackbar.make(binding.root, R.string.error_clipboard_empty, Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                restorePrivateKey(key)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun restorePrivateKey(privateKeyBase64: String) {
        // Validate the key by trying to load it
        try {
            val keyBytes = Base64.decode(privateKeyBase64, Base64.NO_WRAP)
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("EC")
            keyFactory.generatePrivate(keySpec) // throws if invalid

            // Regenerate the public key from the private key
            val privateKey = keyFactory.generatePrivate(keySpec) as java.security.interfaces.ECPrivateKey
            val keyPairGen = KeyPairGenerator.getInstance("EC")
            keyPairGen.initialize(ECGenParameterSpec("secp256r1"))

            // We can't derive public from private directly, so we just save the private key
            // The user should also save the public key, but the important thing is the private key
            prefs.edit()
                .putString(KEY_PRIVATE_KEY, privateKeyBase64)
                .apply()

            updateKeyStatus()
            Snackbar.make(binding.root, R.string.msg_private_key_restored, Snackbar.LENGTH_LONG).show()
        } catch (e: Exception) {
            Snackbar.make(binding.root, R.string.error_invalid_private_key, Snackbar.LENGTH_LONG).show()
        }
    }

    // ── Code Generation ──────────────────────────────────────────────────────

    private fun setupCodeGeneration() {
        binding.btnGenerate.setOnClickListener {
            if (!hasKeys()) {
                Snackbar.make(binding.root, R.string.error_no_keys, Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val hash = binding.etDeviceHash.text?.toString()?.trim() ?: ""
            if (hash.isEmpty()) {
                Snackbar.make(binding.root, R.string.error_empty_hash, Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val code = signDeviceHash(hash)
            if (code != null) {
                binding.tvGeneratedCode.text = code
                binding.cardResult.visibility = View.VISIBLE
            } else {
                Snackbar.make(binding.root, "خطأ في التوقيع", Snackbar.LENGTH_SHORT).show()
            }
        }

        binding.btnCopy.setOnClickListener {
            val code = binding.tvGeneratedCode.text?.toString() ?: return@setOnClickListener
            copyToClipboard("activation_code", code)
            Snackbar.make(binding.root, R.string.msg_copied, Snackbar.LENGTH_SHORT).show()
        }
    }

    /**
     * Signs the device hash with the stored ECDSA private key.
     * Returns Base64-encoded signature (the activation code).
     */
    private fun signDeviceHash(deviceHash: String): String? {
        return try {
            val privateKeyBase64 = prefs.getString(KEY_PRIVATE_KEY, null) ?: return null
            val privateKeyBytes = Base64.decode(privateKeyBase64, Base64.NO_WRAP)
            val keySpec = PKCS8EncodedKeySpec(privateKeyBytes)
            val keyFactory = KeyFactory.getInstance("EC")
            val privateKey = keyFactory.generatePrivate(keySpec)

            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(privateKey)
            sig.update(deviceHash.toByteArray(Charsets.UTF_8))
            val signatureBytes = sig.sign()

            Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

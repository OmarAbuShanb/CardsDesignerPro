package dev.anonymous.cardsdesignerpro.app.data.license

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * Centralized provider for the device identity used in licensing.
 *
 * Uses [Settings.Secure.ANDROID_ID] as the practical device identifier.
 * Provides both the raw ID and a SHA-256 hash suitable for display,
 * WhatsApp messages, and activation code generation.
 */
object DeviceIdProvider {

    /** Returns the raw ANDROID_ID for this device. */
    fun getRawId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    /** Returns a SHA-256 hex-encoded hash of the ANDROID_ID. */
    fun getHashedId(context: Context): String {
        val raw = getRawId(context)
        return sha256Hex(raw)
    }

    /** Computes SHA-256 hex string from the given input. */
    internal fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

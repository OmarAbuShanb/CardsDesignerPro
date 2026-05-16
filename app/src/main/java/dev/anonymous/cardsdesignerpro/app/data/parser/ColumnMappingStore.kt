package dev.anonymous.cardsdesignerpro.app.data.parser

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * Persists [ColumnMapping] choices in [SharedPreferences], keyed by a
 * hash of the sorted, lowered header list.
 *
 * This allows auto-resolution of previously mapped file structures
 * without prompting the user again.
 */
object ColumnMappingStore {

    private const val PREFS_NAME = "column_mapping_prefs"
    private const val PREFIX_USER = "user_"
    private const val PREFIX_PASS = "pass_"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Saves the user-selected column mapping for a set of headers.
     */
    fun save(context: Context, headers: List<String>, mapping: ColumnMapping) {
        val key = headerKey(headers)
        prefs(context).edit()
            .putString(PREFIX_USER + key, mapping.usernameColumn)
            .putString(PREFIX_PASS + key, mapping.passwordColumn)
            .apply()
    }

    /**
     * Loads a previously saved mapping for the given header set, or null if none was saved.
     */
    fun load(context: Context, headers: List<String>): ColumnMapping? {
        val key = headerKey(headers)
        val p = prefs(context)
        if (!p.contains(PREFIX_USER + key)) return null
        return ColumnMapping(
            usernameColumn = p.getString(PREFIX_USER + key, null),
            passwordColumn = p.getString(PREFIX_PASS + key, null)
        )
    }

    /**
     * Generates a stable hash key from the header list so that files with
     * the same column structure (regardless of row order) resolve to the same mapping.
     */
    private fun headerKey(headers: List<String>): String {
        val normalized = headers.map { it.trim().lowercase() }.sorted().joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}

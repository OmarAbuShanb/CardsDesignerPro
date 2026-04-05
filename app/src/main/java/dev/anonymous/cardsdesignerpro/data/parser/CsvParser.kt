package dev.anonymous.cardsdesignerpro.data.parser

import android.content.Context
import android.net.Uri
import com.opencsv.CSVReaderBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Parses CSV files selected via SAF.
 * Returns a list of records; each record is a map of column-header -> value.
 * Auto-detects common username and password column names.
 */
object CsvParser {

    /** Common column names considered "username". */
    private val USERNAME_KEYS = setOf("username", "user", "اسم المستخدم", "المستخدم", "يوزر")
    /** Common column names considered "password". */
    private val PASSWORD_KEYS = setOf("password", "pass", "كلمة المرور", "الباسورد", "باس")

    suspend fun parse(context: Context, uri: Uri): ParseResult = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val reader = CSVReaderBuilder(stream.bufferedReader(Charsets.UTF_8))
                    .build()
                val allRows = reader.readAll()
                if (allRows.isEmpty()) return@use ParseResult.empty()

                val headers = allRows.first().map { it.trim() }
                val records = allRows.drop(1).mapNotNull { row ->
                    if (row.all { it.isBlank() }) null
                    else headers.zip(row.toList()).toMap()
                }

                val usernameCol = headers.firstOrNull { it.lowercase() in USERNAME_KEYS }
                val passwordCol = headers.firstOrNull { it.lowercase() in PASSWORD_KEYS }

                ParseResult(records, headers, usernameCol, passwordCol)
            } ?: ParseResult.empty()
        }.getOrElse { e ->
            e.printStackTrace()
            ParseResult.error(e.message ?: "خطأ غير معروف")
        }
    }
}

data class ParseResult(
    val records: List<Map<String, String>>,
    val headers: List<String>,
    val usernameColumn: String?,
    val passwordColumn: String?,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null
    val count: Int get() = records.size

    companion object {
        fun empty() = ParseResult(emptyList(), emptyList(), null, null)
        fun error(msg: String) = ParseResult(emptyList(), emptyList(), null, null, msg)
    }
}

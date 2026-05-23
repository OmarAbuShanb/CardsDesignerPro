package dev.anonymous.cardsdesignerpro.app.data.parser

import android.content.Context
import android.net.Uri
import com.opencsv.CSVParserBuilder
import com.opencsv.CSVReaderBuilder
import dev.anonymous.cardsdesignerpro.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Parses CSV files selected via SAF.
 * Returns a list of records; each record is a map of column-header -> value.
 * Auto-detects common username and password column names.
 */
object CsvParser {

    /** Common column names considered "username". */
    private val USERNAME_KEYS = setOf("username", "user", "اسم المستخدم", "المستخدم", "يوزر", "الرقم", "رقم")
    /** Common column names considered "password". */
    private val PASSWORD_KEYS = setOf("password", "pass", "كلمة المرور", "الباسورد", "باس", "كلمة السر", "السر")

    suspend fun parse(
        context: Context,
        uri: Uri,
        columnMapping: ColumnMapping? = null
    ): ParseResult = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val text = stream.bufferedReader(Charsets.UTF_8).readText()
                if (text.isBlank()) return@use ParseResult.empty()

                // Auto-detect delimiter: peek first line for semicolons
                val firstLine = text.lineSequence().firstOrNull() ?: ""
                val separator = if (firstLine.contains(';')) ';' else ','

                val csvParser = CSVParserBuilder().withSeparator(separator).build()
                val reader = CSVReaderBuilder(text.reader())
                    .withCSVParser(csvParser)
                    .build()
                val allRows = reader.readAll()
                if (allRows.isEmpty()) return@use ParseResult.empty()

                val headers = allRows.first().map { it.trim() }
                val records = allRows.drop(1).mapNotNull { row ->
                    if (row.all { it.isBlank() }) null
                    else headers.zip(row.map { 
                        it.trim().replace(Regex("^[\"'‘“]+|[\"'’”]+$"), "")
                    }).toMap()
                }

                // Use manual mapping if provided, otherwise auto-detect
                val usernameCol = columnMapping?.usernameColumn
                    ?: headers.firstOrNull { it.lowercase() in USERNAME_KEYS }
                val passwordCol = columnMapping?.passwordColumn
                    ?: headers.firstOrNull { it.lowercase() in PASSWORD_KEYS }

                // Username is always required; flag if it's still unresolved
                val needsMapping = headers.isNotEmpty()
                        && usernameCol == null

                ParseResult(records, headers, usernameCol, passwordCol, needsColumnMapping = needsMapping)
            } ?: ParseResult.empty()
        }.getOrElse { e ->
            e.printStackTrace()
            ParseResult.error(e.message ?: context.getString(R.string.error_unknown_parse))
        }
    }
}

data class ParseResult(
    val records: List<Map<String, String>>,
    val headers: List<String>,
    val usernameColumn: String?,
    val passwordColumn: String?,
    val error: String? = null,
    /** True when headers exist but username/password columns weren't auto-detected. */
    val needsColumnMapping: Boolean = false
) {
    val isSuccess: Boolean get() = error == null
    val count: Int get() = records.size

    companion object {
        fun empty() = ParseResult(emptyList(), emptyList(), null, null)
        fun error(msg: String) = ParseResult(emptyList(), emptyList(), null, null, msg)
    }
}

package dev.anonymous.cardsdesignerpro.app.data.parser

import android.content.Context
import android.net.Uri
import dev.anonymous.cardsdesignerpro.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.dhatim.fastexcel.reader.Row
import java.util.Optional
import kotlin.math.floor

/**
 * Parses .xlsx files selected via SAF using FastExcel Reader (lightweight ~200 KB).
 * Returns the same [ParseResult] format as [CsvParser] for unified downstream handling.
 *
 * Supports two Excel layouts, detected automatically:
 *
 * **Format 1 – Standard header row**
 * ```
 * username | password | ...
 * 100001   | 200001   | ...
 * 100002   | 200002   | ...
 * ```
 *
 * **Format 2 – Paired rows (repeated header+data)**
 * ```
 * username | password | package
 * 100001   | 200001   | تنتهي البطاقة بعد 8 ساعات
 * username | password | package
 * 100002   | 200002   | تنتهي البطاقة بعد 8 ساعات
 * ```
 * In Format 2 every odd row (0-indexed) is a header row and every even row is the data.
 * The `package` column is ignored.
 * Column name matching is case-insensitive (e.g. "Password" == "password").
 */
object ExcelParser {

    private val USERNAME_KEYS = setOf("username", "user", "اسم المستخدم", "المستخدم", "يوزر", "الرقم", "رقم")
    private val PASSWORD_KEYS = setOf("password", "pass", "كلمة المرور", "الباسورد", "باس", "كلمة السر", "السر")

    // ── Public API ─────────────────────────────────────────────────────────────

    suspend fun parse(
        context: Context,
        uri: Uri,
        columnMapping: ColumnMapping? = null
    ): ParseResult =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ReadableWorkbook(stream).use { workbook ->
                        val sheet = workbook.firstSheet
                        val rows: List<Row> = sheet.openStream().use { s ->
                            s.collect(java.util.stream.Collectors.toList())
                        }

                        if (rows.isEmpty()) return@use ParseResult.empty()

                        if (isPairedRowFormat(rows)) parsePairedRows(rows)
                        else parseStandardFormat(rows, columnMapping)
                    }
                } ?: ParseResult.empty()
            }.getOrElse { e ->
                e.printStackTrace()
                ParseResult.error(e.message ?: context.getString(R.string.error_unknown_parse))
            }
        }

    // ── Format detection ───────────────────────────────────────────────────────

    /**
     * Returns true when the file uses the paired-row layout.
     * Heuristic: at least two "header-like" rows exist at indices 0 and 2 (both contain
     * username and password column labels, case-insensitive).
     */
    private fun isPairedRowFormat(rows: List<Row>): Boolean {
        if (rows.size < 4) return false
        return looksLikeHeaderRow(rows[0]) && looksLikeHeaderRow(rows[2])
    }

    private fun looksLikeHeaderRow(row: Row): Boolean {
        val cells = (0 until row.cellCount).map { i ->
            row.safeCellText(i).trim().lowercase()
        }
        return cells.any { it in USERNAME_KEYS } && cells.any { it in PASSWORD_KEYS }
    }

    // ── Format 1: standard single-header ──────────────────────────────────────

    private fun parseStandardFormat(
        rows: List<Row>,
        columnMapping: ColumnMapping? = null
    ): ParseResult {
        val headerRow = rows.first()
        val headers = headerRow.cellStrings()

        val records = rows.drop(1).mapNotNull { row ->
            val map = headers.mapIndexed { i, header ->
                header to row.cellValueAt(i)
            }.toMap()
            if (map.values.all { it.isBlank() }) null else map
        }

        // Use manual mapping if provided, otherwise auto-detect
        val usernameCol = columnMapping?.usernameColumn
            ?: headers.firstOrNull { it.lowercase() in USERNAME_KEYS }
        val passwordCol = columnMapping?.passwordColumn
            ?: headers.firstOrNull { it.lowercase() in PASSWORD_KEYS }

        val needsMapping = headers.isNotEmpty()
                && usernameCol == null
                && passwordCol == null

        return ParseResult(records, headers, usernameCol, passwordCol, needsColumnMapping = needsMapping)
    }

    // ── Format 2: paired rows (header + data, repeated per card) ──────────────

    private fun parsePairedRows(rows: List<Row>): ParseResult {
        val normalizedUsernameKey = "username"
        val normalizedPasswordKey = "password"

        val records = mutableListOf<Map<String, String>>()

        var i = 0
        while (i + 1 < rows.size) {
            val headerRow = rows[i]
            val dataRow = rows[i + 1]

            // Map cell index → normalized key (username | password only; package ignored)
            val colMap = mutableMapOf<Int, String>()
            for (col in 0 until headerRow.cellCount) {
                val label = headerRow.safeCellText(col).trim().lowercase()
                if (label.isEmpty()) continue
                when {
                    label in USERNAME_KEYS -> colMap[col] = normalizedUsernameKey
                    label in PASSWORD_KEYS -> colMap[col] = normalizedPasswordKey
                    // package and any other columns → ignored
                }
            }

            val record = colMap.entries.associate { (col, key) ->
                key to dataRow.cellValueAt(col)
            }

            if (record.values.any { it.isNotBlank() }) records.add(record)
            i += 2
        }

        val headers = listOf(normalizedUsernameKey, normalizedPasswordKey)
        return ParseResult(
            records = records,
            headers = headers,
            usernameColumn = normalizedUsernameKey,
            passwordColumn = normalizedPasswordKey
        )
    }

    // ── Cell helpers ───────────────────────────────────────────────────────────

    /**
     * Safely reads a cell's raw text value regardless of cell type (STRING, NUMBER, etc.).
     * Uses [rawValue] instead of the library's [getCellAsString] which throws on non-STRING cells.
     */
    private fun Row.safeCellText(index: Int): String {
        if (index >= cellCount) return ""
        return try {
            getOptionalCell(index).map { cell -> cell.rawValue ?: "" }.orElse("")
        } catch (_: Exception) {
            ""
        }
    }

    /** Returns the string representation of a cell, stripping surrounding quotes. */
    private fun Row.cellValueAt(index: Int): String {
        val raw = safeCellText(index)
        // Handle numeric values that come as decimals (e.g. "100001.0" → "100001")
        val cleaned = raw.toDoubleOrNull()?.let { n ->
            if (n == floor(n) && !n.isInfinite()) n.toLong().toString() else n.toString()
        } ?: raw
        return cleaned.trim().replace(Regex("""^["''""]+|["''""]+$"""), "")
    }

    /** Returns all header labels from the first row, stripping quotes. */
    private fun Row.cellStrings(): List<String> =
        (0 until cellCount).map { i ->
            safeCellText(i).trim()
                .replace(Regex("""^["''""]+|["''""]+$"""), "")
        }
}

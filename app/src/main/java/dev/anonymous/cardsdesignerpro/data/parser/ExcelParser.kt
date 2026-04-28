package dev.anonymous.cardsdesignerpro.data.parser

import android.content.Context
import android.net.Uri
import dev.anonymous.cardsdesignerpro.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook

/**
 * Parses .xlsx and .xls files selected via SAF.
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

    private val USERNAME_KEYS = setOf("username", "user", "اسم المستخدم", "المستخدم", "يوزر")
    private val PASSWORD_KEYS = setOf("password", "pass", "كلمة المرور", "الباسورد", "باس")

    // ── Public API ─────────────────────────────────────────────────────────────

    suspend fun parse(context: Context, uri: Uri, isXlsx: Boolean): ParseResult =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val workbook: Workbook =
                        if (isXlsx) XSSFWorkbook(stream) else HSSFWorkbook(stream)
                    val sheet = workbook.getSheetAt(0)
                    val rows  = sheet.rowIterator().asSequence().toList()
                    workbook.close()

                    if (rows.isEmpty()) return@use ParseResult.empty()

                    if (isPairedRowFormat(rows)) parsePairedRows(rows)
                    else                         parseStandardFormat(rows)
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
        val cells = (0 until row.lastCellNum.toInt()).map { i ->
            row.getCell(i)?.stringCellValue?.trim()?.lowercase() ?: ""
        }
        return cells.any { it in USERNAME_KEYS } && cells.any { it in PASSWORD_KEYS }
    }

    // ── Format 1: standard single-header ──────────────────────────────────────

    private fun parseStandardFormat(rows: List<Row>): ParseResult {
        val headerRow = rows.first()
        val headers   = headerRow.cellStrings()

        val records = rows.drop(1).mapNotNull { row ->
            val map = headers.mapIndexed { i, header ->
                header to row.cellValueAt(i)
            }.toMap()
            if (map.values.all { it.isBlank() }) null else map
        }

        val usernameCol = headers.firstOrNull { it.lowercase() in USERNAME_KEYS }
        val passwordCol = headers.firstOrNull { it.lowercase() in PASSWORD_KEYS }
        return ParseResult(records, headers, usernameCol, passwordCol)
    }

    // ── Format 2: paired rows (header + data, repeated per card) ──────────────

    private fun parsePairedRows(rows: List<Row>): ParseResult {
        val normalizedUsernameKey = "username"
        val normalizedPasswordKey = "password"

        val records = mutableListOf<Map<String, String>>()

        var i = 0
        while (i + 1 < rows.size) {
            val headerRow = rows[i]
            val dataRow   = rows[i + 1]

            // Map cell index → normalized key (username | password only; package ignored)
            val colMap = mutableMapOf<Int, String>()
            for (col in 0 until headerRow.lastCellNum.toInt()) {
                val label = headerRow.getCell(col)?.stringCellValue?.trim()?.lowercase() ?: continue
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
            records       = records,
            headers       = headers,
            usernameColumn = normalizedUsernameKey,
            passwordColumn = normalizedPasswordKey
        )
    }

    // ── Cell helpers ───────────────────────────────────────────────────────────

    /** Returns the string representation of a cell, stripping surrounding quotes. */
    private fun Row.cellValueAt(index: Int): String {
        val cell = getCell(index) ?: return ""
        return cell.asString().replace(Regex("""^["''""]+|["''""]+$"""), "")
    }

    private fun Cell.asString(): String = when (cellType) {
        CellType.STRING  -> stringCellValue.trim()
        CellType.NUMERIC -> {
            val n = numericCellValue
            if (n == kotlin.math.floor(n)) n.toLong().toString() else n.toString()
        }
        CellType.BOOLEAN -> booleanCellValue.toString()
        else             -> ""
    }

    /** Returns all header labels from the first row, stripping quotes. */
    private fun Row.cellStrings(): List<String> =
        (0 until lastCellNum.toInt()).map { i ->
            getCell(i)?.stringCellValue?.trim()
                ?.replace(Regex("""^["''""]+|["''""]+$"""), "") ?: ""
        }
}

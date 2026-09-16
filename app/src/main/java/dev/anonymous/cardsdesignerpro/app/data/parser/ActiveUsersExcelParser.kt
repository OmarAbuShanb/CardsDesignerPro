package dev.anonymous.cardsdesignerpro.app.data.parser

import android.content.Context
import android.net.Uri
import dev.anonymous.cardsdesignerpro.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.dhatim.fastexcel.reader.Row
import java.util.stream.Collectors
import kotlin.math.floor

/**
 * Parser for Active Users / Active Cards Excel reports.
 *
 * Required columns:
 * - تاريخ إنشاء المستخدم
 * - إسم المستخدم
 * - حالة البيع
 * - اسم الحزمة
 * - تاريخ أول اتصال
 * - الحالة
 *
 * Any other columns are ignored.
 *
 * File must match this structure; no manual mapping supported.
 */
object ActiveUsersExcelParser {

    // Expected headers (Arabic variants supported)
    private val CREATED_AT_KEYS = setOf(
        "تاريخ إنشاء المستخدم",
        "تاريخ انشاء المستخدم"
    )

    private val USERNAME_KEYS = setOf(
        "إسم المستخدم",
        "اسم المستخدم"
    )

    private val SALE_STATUS_KEYS = setOf(
        "حالة البيع"
    )

    private val PACKAGE_KEYS = setOf(
        "اسم الحزمة"
    )

    private val FIRST_LOGIN_KEYS = setOf(
        "تاريخ أول اتصال",
        "تاريخ اول اتصال"
    )

    private val STATUS_KEYS = setOf(
        "الحالة"
    )

    suspend fun parse(
        context: Context,
        uri: Uri
    ): ParseResult =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ReadableWorkbook(stream).use { workbook ->

                        val sheet = workbook.firstSheet
                        val rows = sheet.openStream().use {
                            it.collect(Collectors.toList())
                        }

                        if (rows.isEmpty()) {
                            return@use ParseResult.empty()
                        }

                        parseRows(context, rows)

                    }
                } ?: ParseResult.empty()
            }.getOrElse { e ->
                e.printStackTrace()
                ParseResult.error(
                    e.message ?: context.getString(R.string.error_file_invalid_data)
                )
            }
        }

    private fun parseRows(
        context: Context,
        rows: List<Row>
    ): ParseResult {

        val headerRow = rows.first()
        val headers = headerRow.cellStrings()

        fun findColumn(keys: Set<String>): String? {
            return headers.firstOrNull { header ->
                header.normalizeHeader() in keys.map { it.normalizeHeader() }
            }
        }

        val createdAtCol = findColumn(CREATED_AT_KEYS)
        val usernameCol = findColumn(USERNAME_KEYS)
        val saleStatusCol = findColumn(SALE_STATUS_KEYS)
        val packageCol = findColumn(PACKAGE_KEYS)
        val firstLoginCol = findColumn(FIRST_LOGIN_KEYS)
        val statusCol = findColumn(STATUS_KEYS)

        // Strict validation
        if (
            createdAtCol == null ||
            usernameCol == null ||
            saleStatusCol == null ||
            packageCol == null ||
            firstLoginCol == null ||
            statusCol == null
        ) {
            return ParseResult.error(
                context.getString(R.string.error_file_invalid_data)
            )
        }

        val allowedHeaders = listOf(
            createdAtCol,
            usernameCol,
            saleStatusCol,
            packageCol,
            firstLoginCol,
            statusCol
        )

        val records = rows.drop(1).mapNotNull { row ->

            val map = allowedHeaders.mapIndexedNotNull { _, header ->

                val index = headers.indexOf(header)
                if (index == -1) return@mapIndexedNotNull null

                header to row.cellValueAt(index)

            }.toMap()

            if (map.values.all { it.isBlank() }) {
                null
            } else {
                map
            }
        }

        return ParseResult(
            records = records,
            headers = allowedHeaders,
            usernameColumn = usernameCol,
            passwordColumn = null,
            needsColumnMapping = false
        )
    }

    // ───────────────── helpers ─────────────────

    private fun String.normalizeHeader(): String {
        return trim()
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("آ", "ا")
            .lowercase()
    }

    private fun Row.safeCellText(index: Int): String {
        if (index >= cellCount) return ""
        return try {
            getOptionalCell(index)
                .map { it.rawValue ?: "" }
                .orElse("")
        } catch (_: Exception) {
            ""
        }
    }

    private fun Row.cellValueAt(index: Int): String {
        val raw = safeCellText(index)

        val cleaned = raw.toDoubleOrNull()?.let { n ->
            if (n == floor(n) && !n.isInfinite()) {
                n.toLong().toString()
            } else {
                n.toString()
            }
        } ?: raw

        return cleaned.trim()
            .replace(Regex("""^["']+|["']+$"""), "")
    }

    private fun Row.cellStrings(): List<String> =
        (0 until cellCount).map { i ->
            safeCellText(i)
                .trim()
                .replace(Regex("""^["']+|["']+$"""), "")
        }
}

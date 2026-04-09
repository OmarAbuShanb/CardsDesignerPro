package dev.anonymous.cardsdesignerpro.data.parser

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook

/**
 * Parses .xlsx and .xls files selected via SAF.
 * Returns the same [ParseResult] format as [CsvParser] for unified downstream handling.
 */
object ExcelParser {

    private val USERNAME_KEYS = setOf("username", "user", "اسم المستخدم", "المستخدم", "يوزر")
    private val PASSWORD_KEYS = setOf("password", "pass", "كلمة المرور", "الباسورد", "باس")

    suspend fun parse(context: Context, uri: Uri, isXlsx: Boolean): ParseResult =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val workbook: Workbook =
                        if (isXlsx) XSSFWorkbook(stream) else HSSFWorkbook(stream)
                    val sheet = workbook.getSheetAt(0)
                    val rows = sheet.rowIterator().asSequence().toList()
                    if (rows.isEmpty()) return@use ParseResult.empty()

                    val headerRow = rows.first()
                    val headers = (0 until headerRow.lastCellNum).map { i ->
                        headerRow.getCell(i)?.stringCellValue?.trim()
                            ?.replace(Regex("^[\"'‘“]+|[\"'’”]+$"), "") ?: ""
                    }

                    val records = rows.drop(1).mapNotNull { row ->
                        val map = headers.mapIndexed { i, header ->
                            val cell = row.getCell(i)
                            val value = when (cell?.cellType) {
                                CellType.STRING -> cell.stringCellValue.trim()
                                    .replace(Regex("^[\"'‘“]+|[\"'’”]+$"), "")
                                CellType.NUMERIC -> {
                                    val n = cell.numericCellValue
                                    if (n == kotlin.math.floor(n)) n.toLong().toString() else n.toString()
                                }
                                CellType.BOOLEAN -> cell.booleanCellValue.toString()
                                else -> ""
                            }
                            header to value
                        }.toMap()
                        if (map.values.all { it.isBlank() }) null else map
                    }

                    val usernameCol = headers.firstOrNull { it.lowercase() in USERNAME_KEYS }
                    val passwordCol = headers.firstOrNull { it.lowercase() in PASSWORD_KEYS }

                    workbook.close()
                    ParseResult(records, headers, usernameCol, passwordCol)
                } ?: ParseResult.empty()
            }.getOrElse { e ->
                e.printStackTrace()
                ParseResult.error(e.message ?: "خطأ غير معروف")
            }
        }
}

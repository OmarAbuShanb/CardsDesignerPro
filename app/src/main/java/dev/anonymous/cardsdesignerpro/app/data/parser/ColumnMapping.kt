package dev.anonymous.cardsdesignerpro.app.data.parser

/**
 * Manual column mapping for data files whose headers
 * cannot be auto-detected by [CsvParser] or [ExcelParser].
 *
 * @param usernameColumn Header name of the column containing usernames, or null if not mapped.
 * @param passwordColumn Header name of the column containing passwords, or null if not mapped.
 */
data class ColumnMapping(
    val usernameColumn: String?,
    val passwordColumn: String?
)

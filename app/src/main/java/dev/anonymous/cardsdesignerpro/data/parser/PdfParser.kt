package dev.anonymous.cardsdesignerpro.data.parser

import android.content.Context
import android.net.Uri
import dev.anonymous.cardsdesignerpro.R
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Parses PDF files that contain card credential data in a grid/table layout.
 * Returns the same [ParseResult] format as [CsvParser] and [ExcelParser].
 *
 * ## Supported extraction visual_shapes
 *
 * The parser handles **two visual_shapes** that [PDFTextStripper] may produce,
 * depending on the internal structure of the PDF:
 *
 * **Pattern 1 – Vertical / sequential** (most common output):
 * Each card is extracted one after another, with its own username, password,
 * and package lines:
 * ```
 * Username  9725060121
 * Password  511118
 * Package   اشتراك يومي 8 ساعات 2 ميجا
 * Username  1725076121
 * Password  138383
 * Package   اشتراك يومي 8 ساعات 2 ميجا
 * ```
 *
 * **Pattern 2 – Horizontal / multi-value** (less common):
 * Multiple cards appear on the same line, labels repeated:
 * ```
 * Username 100001  Username 100002  Username 100003
 * Password 200001  Password 200002  Password 200003
 * Package  pkg1    Package  pkg2    Package  pkg3
 * ```
 *
 * Both visual_shapes are supported transparently.
 *
 * - The **package** row is **ignored** — only username and password are extracted.
 * - Label matching is **case-insensitive**.
 */
object PdfParser {

    private val USERNAME_KEYS = setOf("username", "user", "اسم المستخدم", "المستخدم", "يوزر")
    private val PASSWORD_KEYS = setOf("password", "pass", "كلمة المرور", "الباسورد", "باس")
    private val PACKAGE_KEYS  = setOf("package", "الباقة", "باقة", "الباكج", "باكج")

    /** All known label keys (used to distinguish labels from values). */
    private val ALL_KEYS = USERNAME_KEYS + PASSWORD_KEYS + PACKAGE_KEYS

    // ── Public API ─────────────────────────────────────────────────────────────

    suspend fun parse(context: Context, uri: Uri): ParseResult = withContext(Dispatchers.IO) {
        runCatching {
            // Initialize PDFBox resources (safe to call multiple times)
            PDFBoxResourceLoader.init(context.applicationContext)

            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream).use { document ->
                    val stripper = PDFTextStripper().apply {
                        sortByPosition = true      // critical for table reading order
                    }
                    val fullText = stripper.getText(document)
                    parseExtractedText(fullText)
                }
            } ?: ParseResult.empty()
        }.getOrElse { e ->
            e.printStackTrace()
            ParseResult.error(e.message ?: context.getString(R.string.error_unknown_parse))
        }
    }

    // ── Core parsing logic ─────────────────────────────────────────────────────

    /**
     * Takes the raw text extracted from the PDF and converts it into a [ParseResult].
     *
     * Strategy:
     * 1. Split into non-blank lines.
     * 2. Classify each line as **username-row**, **password-row**, or **package-row**
     *    based on whether its first recognized token (case-insensitive) is a known label.
     * 3. Group consecutive (username-row, password-row, [package-row]) triplets.
     * 4. Within each group, extract label→value pairs side-by-side.
     */
    private fun parseExtractedText(text: String): ParseResult {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (lines.isEmpty()) return ParseResult.empty()

        // ── Classify every line ────────────────────────────────────────────────
        data class ClassifiedLine(val type: LineType, val raw: String)
        val classified = lines.mapNotNull { line ->
            val type = classifyLine(line) ?: return@mapNotNull null
            ClassifiedLine(type, line)
        }

        if (classified.isEmpty()) return ParseResult.empty()

        // ── Group into row-triplets (username, password, [package]) ────────────
        val records = mutableListOf<Map<String, String>>()
        var i = 0
        while (i < classified.size) {
            val current = classified[i]

            if (current.type == LineType.USERNAME) {
                val usernameLine = current.raw
                val passwordLine = if (i + 1 < classified.size && classified[i + 1].type == LineType.PASSWORD)
                    classified[i + 1].raw else null

                if (passwordLine != null) {
                    val userValues = extractValues(usernameLine, USERNAME_KEYS)
                    val passValues = extractValues(passwordLine, PASSWORD_KEYS)

                    // Pair up username/password values from the same row-group
                    val count = maxOf(userValues.size, passValues.size)
                    for (j in 0 until count) {
                        val u = userValues.getOrElse(j) { "" }
                        val p = passValues.getOrElse(j) { "" }
                        if (u.isNotBlank() || p.isNotBlank()) {
                            records.add(mapOf("username" to u, "password" to p))
                        }
                    }

                    // Skip past the password line (and optional package line)
                    i += 2
                    if (i < classified.size && classified[i].type == LineType.PACKAGE) i++
                } else {
                    i++
                }
            } else {
                i++
            }
        }

        if (records.isEmpty()) return ParseResult.empty()

        val headers = listOf("username", "password")
        return ParseResult(
            records        = records,
            headers        = headers,
            usernameColumn = "username",
            passwordColumn = "password"
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private enum class LineType { USERNAME, PASSWORD, PACKAGE }

    /**
     * Determines the type of a line based on its **first recognized label**.
     * Returns `null` if the line contains no recognized labels at all.
     */
    private fun classifyLine(line: String): LineType? {
        val tokens = tokenize(line)
        for (token in tokens) {
            val lower = token.lowercase()
            when {
                lower in USERNAME_KEYS -> return LineType.USERNAME
                lower in PASSWORD_KEYS -> return LineType.PASSWORD
                lower in PACKAGE_KEYS  -> return LineType.PACKAGE
            }
        }
        return null
    }

    /**
     * Extracts the **values** from a line, given a set of expected label keys.
     *
     * For a line like `"username  100001  username  100002"`, with label keys
     * being [USERNAME_KEYS], this returns `["100001", "100002"]`.
     *
     * Logic: walk through tokens; whenever a label token is found, the **next**
     * token(s) that aren't labels are collected as the value. This handles
     * multi-word values correctly.
     */
    private fun extractValues(line: String, labelKeys: Set<String>): List<String> {
        val tokens = tokenize(line)
        val values = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val lower = tokens[i].lowercase()
            if (lower in labelKeys) {
                // Collect all subsequent non-label tokens as the value
                i++
                val valueParts = mutableListOf<String>()
                while (i < tokens.size && tokens[i].lowercase() !in ALL_KEYS) {
                    valueParts.add(tokens[i])
                    i++
                }
                if (valueParts.isNotEmpty()) {
                    values.add(valueParts.joinToString(" "))
                }
            } else {
                i++
            }
        }
        return values
    }

    /**
     * Splits a line into tokens by whitespace.
     * Also handles tab-separated and multi-space-separated values.
     */
    private fun tokenize(line: String): List<String> =
        line.split(Regex("\\s+")).filter { it.isNotBlank() }
}

package dev.anonymous.cardsdesignerpro.app.util

import android.graphics.Color
import androidx.core.graphics.toColorInt
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.util.Matrix
import dev.anonymous.cardsdesignerpro.app.data.model.TextAlign

/**
 * Draws text directly into a [PDPageContentStream] using native PDF text operators.
 *
 * Handles:
 *  - Arabic text via built-in reshaping (contextual form substitution) + BiDi reordering
 *  - Vertical centering that matches Android's [android.text.StaticLayout]
 *  - Text alignment (START / CENTER / END)
 *  - Rotation around element center
 *  - Background color, text stroke
 *
 * Coordinate notes:
 *  - PDF origin is bottom-left; Android origin is top-left.
 *  - The caller must convert Android y-coordinates to PDF y-coordinates
 *    before calling these methods.
 */
object PdfTextDrawer {

    /** Padding around text background in template dp — must match TemplateRenderer.TEXT_PAD_DP. */
    private const val TEXT_PAD_DP = 6f

    /**
     * Prepares text for PDF rendering by applying Arabic shaping and BiDi reordering.
     *
     * 1. **Arabic Reshaping**: Converts standard Unicode Arabic codepoints into their
     *    correct joined presentation forms (initial, medial, final, isolated) using
     *    Unicode Arabic Presentation Forms-B.
     * 2. **Bidi reordering**: Converts logical character order to visual display order
     *    using [java.text.Bidi].
     *
     * Returns the shaped+reordered string, or the original string if text contains
     * no complex script characters.
     */
    fun shapeForPdf(text: String): String {
        if (text.isEmpty()) return text
        // Fast path: skip shaping for ASCII-only text
        if (text.all { it.code < 0x0590 }) return text

        return runCatching {
            // Step 1: Shape Arabic letters into their contextual presentation forms
            val shaped = ArabicReshaper.reshape(text)

            // Step 2: BiDi reorder to visual order using java.text.Bidi
            val bidi = java.text.Bidi(shaped, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
            if (bidi.isLeftToRight) {
                shaped
            } else {
                bidiReorder(shaped, bidi)
            }
        }.getOrElse { text }
    }

    /** Reorders a string from logical to visual order using Bidi run information. */
    private fun bidiReorder(text: String, bidi: java.text.Bidi): String {
        val runCount = bidi.runCount
        val sb = StringBuilder(text.length)
        for (i in 0 until runCount) {
            val start = bidi.getRunStart(i)
            val end = bidi.getRunLimit(i)
            val level = bidi.getRunLevel(i)
            val run = text.substring(start, end)
            if (level % 2 == 1) {
                // RTL run: reverse the characters
                sb.append(run.reversed())
            } else {
                sb.append(run)
            }
        }
        return sb.toString()
    }

    /**
     * Draws a single text element onto the content stream.
     *
     * @param cs           Active page content stream
     * @param text         The string to render (will be auto-shaped for Arabic)
     * @param font         Resolved PDFont from PdfResourceCache
     * @param fontSizePt   Font size in PDF points (already scaled: textSizeSp * scaleX)
     * @param pdfX         Left edge of the element in PDF page coordinates
     * @param pdfY         Bottom edge of the element in PDF page coordinates
     * @param elWidthPt    Element width in PDF points
     * @param elHeightPt   Element height in PDF points
     * @param textColor    Text fill color as ARGB hex string (e.g. "#FF0000")
     * @param bgColor      Optional background color as ARGB hex string, null = no background
     * @param textAlign    START / CENTER / END alignment
     * @param rotation     Rotation in degrees around element center (matches Android convention)
     * @param textStrokeWidth Stroke width in pt (0 = no stroke). Already scaled by caller.
     * @param textStrokeColor Stroke color hex
     * @param wrap         True for wrapping text (TextElement), false for single-line credentials
     * @param scaleX       Scale factor for dp→pt conversion (used for padding)
     * @return true if text was drawn successfully, false if encoding failed (caller should fallback)
     */
    fun drawText(
        cs: PDPageContentStream,
        text: String,
        font: PDFont,
        fontSizePt: Float,
        pdfX: Float,
        pdfY: Float,
        elWidthPt: Float,
        elHeightPt: Float,
        textColor: String,
        bgColor: String?,
        textAlign: TextAlign,
        rotation: Float,
        textStrokeWidth: Float,
        textStrokeColor: String,
        wrap: Boolean,
        scaleX: Float
    ): Boolean {
        if (text.isEmpty()) return true

        // Apply Arabic shaping + BiDi reordering
        val shapedText = shapeForPdf(text)

        // Check if font can encode the shaped text
        val canEncode = try {
            font.encode(shapedText)
            true
        } catch (_: Exception) {
            false
        }
        if (!canEncode) return false

        val padPt = TEXT_PAD_DP * scaleX
        val centerX = pdfX + elWidthPt / 2f
        val centerY = pdfY + elHeightPt / 2f

        // Measure text width
        val textWidth = try {
            font.getStringWidth(shapedText) / 1000f * fontSizePt
        } catch (_: Exception) {
            return false
        }

        // ── Vertical positioning ────────────────────────────────────────────
        // Match Android's StaticLayout (includePad=false) vertical centering.
        //
        // Android's Paint.FontMetrics.ascent is typically LARGER than the PDF
        // font descriptor's ascent, because Android measures from the top of
        // tall glyphs (like Arabic with diacritics) while PDF uses the font's
        // design ascent. This causes PDF text to appear higher than Android.
        //
        // Correction: use the font's capHeight (height of uppercase letters)
        // for visual centering of single-line text, then position the baseline.
        // For multi-line text, we keep the full ascent/descent approach.

        val fd = font.fontDescriptor
        val rawAscent = fd?.ascent ?: 800f
        val rawDescent = fd?.descent ?: -200f
        val rawCapHeight = fd?.capHeight ?: (rawAscent * 0.7f)

        val ascent = rawAscent / 1000f * fontSizePt
        val descent = rawDescent / 1000f * fontSizePt  // negative value
        val capHeight = rawCapHeight / 1000f * fontSizePt
        val layoutHeight = ascent - descent

        // For single-line text (credentials, dates), use capHeight-based centering
        // which matches the visual center of digits and uppercase letters.
        // For multi-line wrapped text, use full ascent/descent.
        val ty = if (!wrap) {
            // Center the cap-height range within the element, then compute baseline
            // baseline = centerY - capHeight/2 (bottom of caps is at baseline)
            centerY - capHeight / 2f
        } else {
            // Standard full-metrics centering for wrapped text
            centerY - layoutHeight / 2f - descent
        }

        // ── Horizontal positioning ──────────────────────────────────────────
        val isRtl = text.any { isComplexScript(it) }

        val tx: Float = if (!wrap) {
            when (textAlign) {
                TextAlign.START -> if (isRtl) pdfX + elWidthPt - textWidth else pdfX
                TextAlign.END -> if (isRtl) pdfX else pdfX + elWidthPt - textWidth
                TextAlign.CENTER -> centerX - textWidth / 2f
            }
        } else {
            when (textAlign) {
                TextAlign.START -> if (isRtl) pdfX + elWidthPt - padPt - textWidth else pdfX + padPt
                TextAlign.END -> if (isRtl) pdfX + padPt else pdfX + elWidthPt - padPt - textWidth
                TextAlign.CENTER -> centerX - textWidth / 2f
            }
        }

        cs.saveGraphicsState()

        // Apply rotation if needed
        if (rotation != 0f) {
            val radians = Math.toRadians((-rotation).toDouble())
            val cos = Math.cos(radians).toFloat()
            val sin = Math.sin(radians).toFloat()
            val m = Matrix()
            m.setValue(0, 0, cos)
            m.setValue(0, 1, sin)
            m.setValue(1, 0, -sin)
            m.setValue(1, 1, cos)
            m.setValue(2, 0, centerX - cos * centerX + sin * centerY)
            m.setValue(2, 1, centerY - sin * centerX - cos * centerY)
            cs.transform(m)
        }

        // Draw background rect if specified
        if (bgColor != null) {
            val alpha = colorAlpha(bgColor)
            if (alpha > 0) {
                val (bgR, bgG, bgB) = colorComponents(bgColor)
                cs.setNonStrokingColor(bgR, bgG, bgB)
                if (!wrap) {
                    cs.addRect(
                        tx - padPt,
                        ty + descent - padPt,
                        textWidth + padPt * 2,
                        layoutHeight + padPt * 2
                    )
                } else {
                    cs.addRect(pdfX, pdfY, elWidthPt, elHeightPt)
                }
                cs.fill()
            }
        }

        // Draw text stroke (if any)
        if (textStrokeWidth > 0f) {
            cs.beginText()
            cs.setFont(font, fontSizePt)
            val (sr, sg, sb) = colorComponents(textStrokeColor)
            cs.setStrokingColor(sr, sg, sb)
            cs.setLineWidth(textStrokeWidth)
            setRenderingMode(cs, 1) // Mode 1 = Stroke
            cs.newLineAtOffset(tx, ty)
            cs.showText(shapedText)
            cs.endText()
        }

        // Draw text fill
        cs.beginText()
        cs.setFont(font, fontSizePt)
        val (tr, tg, tb) = colorComponents(textColor)
        cs.setNonStrokingColor(tr, tg, tb)
        setRenderingMode(cs, 0) // Mode 0 = Fill
        cs.newLineAtOffset(tx, ty)
        cs.showText(shapedText)
        cs.endText()

        cs.restoreGraphicsState()
        return true
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns true if the character belongs to a complex script Unicode block. */
    private fun isComplexScript(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.ARABIC ||
                block == Character.UnicodeBlock.ARABIC_SUPPLEMENT ||
                block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
                block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B ||
                block == Character.UnicodeBlock.HEBREW
    }

    private fun colorComponents(hex: String): Triple<Int, Int, Int> {
        val color = runCatching { hex.toColorInt() }.getOrElse { Color.BLACK }
        return Triple(
            (color shr 16) and 0xFF,
            (color shr 8) and 0xFF,
            color and 0xFF
        )
    }

    private fun colorAlpha(hex: String): Int {
        val color = runCatching { hex.toColorInt() }.getOrElse { Color.BLACK }
        return (color ushr 24) and 0xFF
    }

    private fun setRenderingMode(cs: PDPageContentStream, mode: Int) {
        try {
            val method = cs.javaClass.getDeclaredMethod("writeOperand", Int::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(cs, mode)
            val writeOp = cs.javaClass.getDeclaredMethod("writeOperator", String::class.java)
            writeOp.isAccessible = true
            writeOp.invoke(cs, "Tr")
        } catch (_: Exception) { }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Arabic Reshaper — Converts standard Arabic Unicode to contextual presentation forms.
// Uses Unicode Arabic Presentation Forms-B (U+FE70–U+FEFF).
// No external dependencies required.
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Stateless Arabic text reshaper that converts standard Arabic Unicode characters
 * into their correct contextual presentation forms.
 *
 * Arabic letters change shape based on their position in a word:
 *  - **Isolated**: ب (standalone)
 *  - **Initial**: بـ (start of word)
 *  - **Medial**: ـبـ (middle of word)
 *  - **Final**: ـب (end of word)
 *
 * This reshaper maps each letter to its Unicode Presentation Form-B glyph
 * based on context (adjacent joining characters).
 */
object ArabicReshaper {

    /**
     * Reshapes Arabic text by replacing standard Arabic Unicode characters
     * with their contextual presentation forms.
     *
     * Non-Arabic characters pass through unchanged.
     */
    fun reshape(text: String): String {
        if (text.isEmpty()) return text

        val chars = text.toCharArray()
        val result = StringBuilder(chars.size)

        for (i in chars.indices) {
            val c = chars[i]
            val entry = CHAR_TABLE[c]

            if (entry == null) {
                // Not an Arabic letter — pass through unchanged
                result.append(c)
                continue
            }

            val prevJoins = i > 0 && canJoinAfter(chars[i - 1])
            val nextJoins = i < chars.size - 1 && canJoinBefore(chars[i + 1])

            val form = when {
                prevJoins && nextJoins && entry.medial != '\u0000' -> entry.medial
                prevJoins && entry.final_ != '\u0000' -> entry.final_
                nextJoins && entry.initial != '\u0000' -> entry.initial
                else -> entry.isolated
            }
            result.append(form)
        }

        return result.toString()
    }

    /** Can this character join to the letter BEFORE it (i.e. does it have a right-joining link)? */
    private fun canJoinBefore(c: Char): Boolean = CHAR_TABLE[c] != null

    /** Can this character join to the letter AFTER it (i.e. does it have a left-joining link)? */
    private fun canJoinAfter(c: Char): Boolean {
        val entry = CHAR_TABLE[c] ?: return false
        // A character can join to the next only if it has initial/medial forms
        // (i.e. it's a dual-joining or right-joining letter)
        return entry.initial != '\u0000'
    }

    /**
     * Contextual forms for a single Arabic character.
     * '\u0000' means the form doesn't exist (e.g. Alef has no medial form).
     */
    private data class CharEntry(
        val isolated: Char,
        val initial: Char,
        val medial: Char,
        val final_: Char
    )

    /**
     * Mapping from standard Arabic Unicode (U+0621–U+064A) to
     * Arabic Presentation Forms-B (U+FE70–U+FEFF).
     *
     * Right-joining letters (Alef, Dal, Thal, Ra, Zain, Waw) have no initial/medial forms.
     */
    private val CHAR_TABLE: Map<Char, CharEntry> = mapOf(
        // Hamza
        '\u0621' to CharEntry('\uFE80', '\u0000', '\u0000', '\u0000'),
        // Alef with Madda
        '\u0622' to CharEntry('\uFE81', '\u0000', '\u0000', '\uFE82'),
        // Alef with Hamza Above
        '\u0623' to CharEntry('\uFE83', '\u0000', '\u0000', '\uFE84'),
        // Waw with Hamza
        '\u0624' to CharEntry('\uFE85', '\u0000', '\u0000', '\uFE86'),
        // Alef with Hamza Below
        '\u0625' to CharEntry('\uFE87', '\u0000', '\u0000', '\uFE88'),
        // Yeh with Hamza
        '\u0626' to CharEntry('\uFE89', '\uFE8B', '\uFE8C', '\uFE8A'),
        // Alef
        '\u0627' to CharEntry('\uFE8D', '\u0000', '\u0000', '\uFE8E'),
        // Beh
        '\u0628' to CharEntry('\uFE8F', '\uFE91', '\uFE92', '\uFE90'),
        // Teh Marbuta
        '\u0629' to CharEntry('\uFE93', '\u0000', '\u0000', '\uFE94'),
        // Teh
        '\u062A' to CharEntry('\uFE95', '\uFE97', '\uFE98', '\uFE96'),
        // Theh
        '\u062B' to CharEntry('\uFE99', '\uFE9B', '\uFE9C', '\uFE9A'),
        // Jeem
        '\u062C' to CharEntry('\uFE9D', '\uFE9F', '\uFEA0', '\uFE9E'),
        // Hah
        '\u062D' to CharEntry('\uFEA1', '\uFEA3', '\uFEA4', '\uFEA2'),
        // Khah
        '\u062E' to CharEntry('\uFEA5', '\uFEA7', '\uFEA8', '\uFEA6'),
        // Dal
        '\u062F' to CharEntry('\uFEA9', '\u0000', '\u0000', '\uFEAA'),
        // Thal
        '\u0630' to CharEntry('\uFEAB', '\u0000', '\u0000', '\uFEAC'),
        // Ra
        '\u0631' to CharEntry('\uFEAD', '\u0000', '\u0000', '\uFEAE'),
        // Zain
        '\u0632' to CharEntry('\uFEAF', '\u0000', '\u0000', '\uFEB0'),
        // Seen
        '\u0633' to CharEntry('\uFEB1', '\uFEB3', '\uFEB4', '\uFEB2'),
        // Sheen
        '\u0634' to CharEntry('\uFEB5', '\uFEB7', '\uFEB8', '\uFEB6'),
        // Sad
        '\u0635' to CharEntry('\uFEB9', '\uFEBB', '\uFEBC', '\uFEBA'),
        // Dad
        '\u0636' to CharEntry('\uFEBD', '\uFEBF', '\uFEC0', '\uFEBE'),
        // Tah
        '\u0637' to CharEntry('\uFEC1', '\uFEC3', '\uFEC4', '\uFEC2'),
        // Zah
        '\u0638' to CharEntry('\uFEC5', '\uFEC7', '\uFEC8', '\uFEC6'),
        // Ain
        '\u0639' to CharEntry('\uFEC9', '\uFECB', '\uFECC', '\uFECA'),
        // Ghain
        '\u063A' to CharEntry('\uFECD', '\uFECF', '\uFED0', '\uFECE'),
        // Tatweel (kashida) — joins on both sides
        '\u0640' to CharEntry('\u0640', '\u0640', '\u0640', '\u0640'),
        // Feh
        '\u0641' to CharEntry('\uFED1', '\uFED3', '\uFED4', '\uFED2'),
        // Qaf
        '\u0642' to CharEntry('\uFED5', '\uFED7', '\uFED8', '\uFED6'),
        // Kaf
        '\u0643' to CharEntry('\uFED9', '\uFEDB', '\uFEDC', '\uFEDA'),
        // Lam
        '\u0644' to CharEntry('\uFEDD', '\uFEDF', '\uFEE0', '\uFEDE'),
        // Meem
        '\u0645' to CharEntry('\uFEE1', '\uFEE3', '\uFEE4', '\uFEE2'),
        // Noon
        '\u0646' to CharEntry('\uFEE5', '\uFEE7', '\uFEE8', '\uFEE6'),
        // Heh
        '\u0647' to CharEntry('\uFEE9', '\uFEEB', '\uFEEC', '\uFEEA'),
        // Waw
        '\u0648' to CharEntry('\uFEED', '\u0000', '\u0000', '\uFEEE'),
        // Alef Maksura
        '\u0649' to CharEntry('\uFEEF', '\u0000', '\u0000', '\uFEF0'),
        // Yeh
        '\u064A' to CharEntry('\uFEF1', '\uFEF3', '\uFEF4', '\uFEF2'),
        // ── Lam-Alef ligatures are handled by standard fonts automatically ──
        // Peh (used in Urdu/Farsi — پ)
        '\u067E' to CharEntry('\uFB56', '\uFB58', '\uFB59', '\uFB57'),
        // Tcheh (چ)
        '\u0686' to CharEntry('\uFB7A', '\uFB7C', '\uFB7D', '\uFB7B'),
        // Jeh (ژ)
        '\u0698' to CharEntry('\uFB8A', '\u0000', '\u0000', '\uFB8B'),
        // Gaf (گ)
        '\u06AF' to CharEntry('\uFB92', '\uFB94', '\uFB95', '\uFB93'),
        // Veh (ڤ — used in some Arabic dialects for "V")
        '\u06A4' to CharEntry('\uFB6A', '\uFB6C', '\uFB6D', '\uFB6B'),
    )
}

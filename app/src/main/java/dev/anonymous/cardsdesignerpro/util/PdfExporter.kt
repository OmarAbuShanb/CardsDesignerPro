package dev.anonymous.cardsdesignerpro.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import dev.anonymous.cardsdesignerpro.data.model.ExportSettings
import dev.anonymous.cardsdesignerpro.data.model.FlipEdge
import dev.anonymous.cardsdesignerpro.data.model.Template
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.data.parser.ParseResult
import dev.anonymous.cardsdesignerpro.ui.editor.canvas.TemplateRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/**
 * Generates multi-page PDFs from a [Template] and a list of credential records.
 *
 * Supports single-side export as well as duplex (dual-sided) export modes:
 *  - [export]         — single-face PDF (front side only, or back-side ignored)
 *  - [exportDual]     — interleaved PDF: odd pages = front, even pages = back (mirrored)
 *  - [exportSeparate] — writes two separate PDFs, one for each face
 *
 * Back-face cards are mirrored via [mirroredBackSlot] so that when the paper is
 * flipped on the chosen edge, each card lands behind its corresponding front card.
 */
object PdfExporter {

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Renders all cards from [records] into a single-face PDF (front side).
     * @param onProgress callback with (completedCards, totalCards)
     */
    suspend fun export(
        context: Context,
        template: Template,
        records: ParseResult,
        shortRecords: ParseResult?,
        settings: ExportSettings,
        outputStream: OutputStream,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val renderer = TemplateRenderer(context)
        try {
            val layout = calculateLayout(template, settings)
            writeFacePages(
                renderer, layout, template, records, shortRecords, settings,
                outputStream, onProgress
            )
        } finally {
            renderer.clearBitmapCache()
        }
    }

    /**
     * Interleaved duplex PDF: odd pages = front, even pages = back (mirrored).
     * Page order: front-p1, back-p1, front-p2, back-p2, …
     */
    suspend fun exportDual(
        context: Context,
        template: Template,
        records: ParseResult,
        shortRecords: ParseResult?,
        settings: ExportSettings,
        outputStream: OutputStream,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val renderer = TemplateRenderer(context)
        val backTemplate = backFaceTemplate(template) ?: run {
            // No back side configured — fall back to single export
            writeFacePages(renderer, calculateLayout(template, settings),
                template, records, shortRecords, settings, outputStream, onProgress)
            renderer.clearBitmapCache()
            return@withContext
        }
        try {
            val layout   = calculateLayout(template, settings)
            val pageW    = settings.pageSize.widthPt.toInt()
            val pageH    = settings.pageSize.heightPt.toInt()
            val total    = records.count.coerceAtLeast(1)
            val perPage  = layout.cardsPerPage
            val pageCount= ceil(total.toDouble() / perPage).toInt()
            val totalProgress = total * 2   // front cards + back cards

            val pdfDoc = PdfDocument()
            var doneCount = 0
            var cardIndex = 0

            val dateStr    = buildDateString(template)
            val usernameCol= records.usernameColumn
            val passwordCol= records.passwordColumn

            for (pageNum in 1..pageCount) {
                if (!isActive) break
                val cardsOnPage = minOf(perPage, total - (pageNum - 1) * perPage)

                // ── Front page ───────────────────────────────────────────────
                val frontPageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum * 2 - 1).create()
                val frontPage = pdfDoc.startPage(frontPageInfo)
                drawCards(
                    canvas = frontPage.canvas,
                    layout = layout,
                    template = template,
                    renderer = renderer,
                    records = records,
                    shortRecords = shortRecords,
                    startCardIndex = cardIndex,
                    cardsOnPage = cardsOnPage,
                    usernameCol = usernameCol,
                    passwordCol = passwordCol,
                    dateStr = dateStr,
                    mirrored = false
                )
                pdfDoc.finishPage(frontPage)
                doneCount += cardsOnPage
                onProgress(doneCount, totalProgress)

                // ── Back page (mirrored) ─────────────────────────────────────
                val backPageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum * 2).create()
                val backPage = pdfDoc.startPage(backPageInfo)
                drawCards(
                    canvas = backPage.canvas,
                    layout = layout,
                    template = backTemplate,
                    renderer = renderer,
                    records = records,
                    shortRecords = shortRecords,
                    startCardIndex = cardIndex,
                    cardsOnPage = cardsOnPage,
                    usernameCol = usernameCol,
                    passwordCol = passwordCol,
                    dateStr = dateStr,
                    mirrored = true,
                    flipEdge = settings.flipEdge
                )
                pdfDoc.finishPage(backPage)
                doneCount += cardsOnPage
                onProgress(doneCount, totalProgress)

                cardIndex += cardsOnPage
            }

            pdfDoc.writeTo(outputStream)
            pdfDoc.close()
        } finally {
            renderer.clearBitmapCache()
        }
    }

    /**
     * Writes two separate PDFs — front face to [frontStream], back face to [backStream].
     */
    suspend fun exportSeparate(
        context: Context,
        template: Template,
        records: ParseResult,
        shortRecords: ParseResult?,
        settings: ExportSettings,
        frontStream: OutputStream,
        backStream: OutputStream,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val renderer = TemplateRenderer(context)
        // No back side configured — fall back to single export
        val backTemplate = backFaceTemplate(template) ?: run {
            writeFacePages(renderer, calculateLayout(template, settings),
                template, records, shortRecords, settings, frontStream, onProgress)
            renderer.clearBitmapCache()
            return@withContext
        }
        try {
            val layout  = calculateLayout(template, settings)
            val total   = records.count.coerceAtLeast(1)
            val halfProgress = total

            // Front PDF
            writeFacePages(renderer, layout, template, records, shortRecords, settings,
                frontStream) { done, _ -> onProgress(done, halfProgress * 2) }

            renderer.clearBitmapCache()

            // Back PDF (mirrored)
            writeFacePagesMirrored(renderer, layout, backTemplate, records, shortRecords, settings,
                backStream, settings.flipEdge) { done, _ ->
                onProgress(halfProgress + done, halfProgress * 2)
            }
        } finally {
            renderer.clearBitmapCache()
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    fun calculateLayout(template: Template, settings: ExportSettings): LayoutInfo {
        val pageW = settings.pageSize.widthPt
        val pageH = settings.pageSize.heightPt
        val preset = settings.selectedPreset
        val cols = preset.columns
        val rows = preset.rows
        val hSpacing = settings.horizontalSpacingDp
        val vSpacing = settings.verticalSpacingDp
        val aspectRatio = template.card.heightRatio  // height / width

        // Available space for cards after subtracting spacing between them
        val availableW = pageW - (cols - 1) * hSpacing
        val availableH = pageH - (rows - 1) * vSpacing

        // Maximum card dimensions that fit the grid
        val maxCardW = availableW / cols
        val maxCardH = availableH / rows

        // Fit card while preserving aspect ratio
        var cardW = maxCardW
        var cardH = cardW * aspectRatio
        if (cardH > maxCardH) {
            cardH = maxCardH
            cardW = cardH / aspectRatio
        }

        val perPage = cols * rows

        // Center the grid on the page
        val gridW = cols * cardW + (cols - 1) * hSpacing
        val gridH = rows * cardH + (rows - 1) * vSpacing
        val marginLeft = (pageW - gridW) / 2f
        val marginTop  = (pageH - gridH) / 2f

        return LayoutInfo(cols, rows, perPage, cardW, cardH, pageW, pageH, marginLeft, marginTop, settings)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns a copy of [template] using backElements as its elements list.
     * Returns null if no back side is configured.
     */
    private fun backFaceTemplate(template: Template): Template? {
        val back = template.backElements ?: return null
        val backStyle = template.backCard ?: template.card
        return template.copy(elements = back, card = backStyle)
    }

    /** Writes a complete single-face PDF to [outputStream]. */
    private suspend fun writeFacePages(
        renderer: TemplateRenderer,
        layout: LayoutInfo,
        template: Template,
        records: ParseResult,
        shortRecords: ParseResult?,
        settings: ExportSettings,
        outputStream: OutputStream,
        onProgress: (Int, Int) -> Unit
    ) {
        val pdfDoc   = PdfDocument()
        val pageW    = settings.pageSize.widthPt.toInt()
        val pageH    = settings.pageSize.heightPt.toInt()
        val total    = records.count.coerceAtLeast(1)
        val pageCount= ceil(total.toDouble() / layout.cardsPerPage).toInt()
        val usernameCol = records.usernameColumn
        val passwordCol = records.passwordColumn
        val dateStr     = buildDateString(template)
        var cardIndex   = 0

        for (pageNum in 1..pageCount) {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
            val cardsOnPage = minOf(layout.cardsPerPage, total - (pageNum - 1) * layout.cardsPerPage)
            val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
            val page     = pdfDoc.startPage(pageInfo)
            drawCards(page.canvas, layout, template, renderer, records, shortRecords,
                cardIndex, cardsOnPage, usernameCol, passwordCol, dateStr, mirrored = false)
            pdfDoc.finishPage(page)
            cardIndex += cardsOnPage
            onProgress(cardIndex, total)
        }
        pdfDoc.writeTo(outputStream)
        pdfDoc.close()
    }

    /** Writes a mirrored single-face PDF to [outputStream]. */
    private suspend fun writeFacePagesMirrored(
        renderer: TemplateRenderer,
        layout: LayoutInfo,
        template: Template,
        records: ParseResult,
        shortRecords: ParseResult?,
        settings: ExportSettings,
        outputStream: OutputStream,
        flipEdge: FlipEdge,
        onProgress: (Int, Int) -> Unit
    ) {
        val pdfDoc    = PdfDocument()
        val pageW     = settings.pageSize.widthPt.toInt()
        val pageH     = settings.pageSize.heightPt.toInt()
        val total     = records.count.coerceAtLeast(1)
        val pageCount = ceil(total.toDouble() / layout.cardsPerPage).toInt()
        val usernameCol = records.usernameColumn
        val passwordCol = records.passwordColumn
        val dateStr     = buildDateString(template)
        var cardIndex   = 0

        for (pageNum in 1..pageCount) {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
            val cardsOnPage = minOf(layout.cardsPerPage, total - (pageNum - 1) * layout.cardsPerPage)
            val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
            val page     = pdfDoc.startPage(pageInfo)
            drawCards(page.canvas, layout, template, renderer, records, shortRecords,
                cardIndex, cardsOnPage, usernameCol, passwordCol, dateStr,
                mirrored = true, flipEdge = flipEdge)
            pdfDoc.finishPage(page)
            cardIndex += cardsOnPage
            onProgress(cardIndex, total)
        }
        pdfDoc.writeTo(outputStream)
        pdfDoc.close()
    }

    /**
     * Draws [cardsOnPage] cards onto [canvas] starting from [startCardIndex].
     *
     * When [mirrored] = true, slot positions are remapped via [mirroredBackSlot]
     * so the back-face cards align with their front-face counterparts after flipping.
     */
    private fun drawCards(
        canvas: Canvas,
        layout: LayoutInfo,
        template: Template,
        renderer: TemplateRenderer,
        records: ParseResult,
        shortRecords: ParseResult?,
        startCardIndex: Int,
        cardsOnPage: Int,
        usernameCol: String?,
        passwordCol: String?,
        dateStr: String,
        mirrored: Boolean,
        flipEdge: FlipEdge = FlipEdge.LONG_EDGE
    ) {
        val s = layout.settings
        renderer.maxImageDim   = s.quality.maxImageDim
        renderer.maxPatternDim = s.quality.maxPatternDim
        renderer.maxQrDim      = s.quality.maxQrDim
        for (slot in 0 until cardsOnPage) {
            val srcCol = slot % layout.columns
            val srcRow = slot / layout.columns

            // Map back-side slot to a mirrored grid position
            val (drawCol, drawRow) = if (mirrored)
                mirroredBackSlot(srcCol, srcRow, layout.columns, layout.rows, flipEdge)
            else
                srcCol to srcRow

            val cardLeft = layout.marginLeftPt + drawCol * (layout.cardWidthPt + s.horizontalSpacingDp)
            val cardTop  = layout.marginTopPt  + drawRow * (layout.cardHeightPt + s.verticalSpacingDp)

            val recordIdx = startCardIndex + slot
            val record    = if (recordIdx < records.records.size) records.records[recordIdx] else emptyMap()
            val shortRecord = if (shortRecords != null && recordIdx < shortRecords.records.size) shortRecords.records[recordIdx] else emptyMap()
            val username  = record[usernameCol ?: ""] ?: ""
            val password  = record[passwordCol ?: ""] ?: ""
            val shortUsername = shortRecord[usernameCol ?: ""] ?: ""
            val shortPassword = shortRecord[passwordCol ?: ""] ?: ""

            canvas.save()
            // Short-edge flip: the back card content is physically upside-down
            if (mirrored && flipEdge == FlipEdge.SHORT_EDGE) {
                canvas.rotate(
                    180f,
                    cardLeft + layout.cardWidthPt / 2f,
                    cardTop  + layout.cardHeightPt / 2f
                )
            }
            renderer.draw(
                canvas      = canvas,
                template    = template,
                cardLeft    = cardLeft,
                cardTop     = cardTop,
                cardWidthPx = layout.cardWidthPt,
                cardHeightPx= layout.cardHeightPt,
                username    = username,
                password    = password,
                shortUsername = shortUsername,
                shortPassword = shortPassword,
                date        = dateStr,
                renderScale = s.quality.renderScale
            )
            canvas.restore()
        }
    }

    /**
     * Maps a front-face grid slot (srcCol, srcRow) to the back-face position
     * that will align with it after the paper is flipped on [flipEdge].
     *
     * LONG_EDGE flip (most printers): reverse columns per row.
     *   Front [r, c] → Back [r, cols-1-c]
     *
     * SHORT_EDGE flip: reverse rows only, columns unchanged.
     *   Front [r, c] → Back [rows-1-r, c]
     */
    private fun mirroredBackSlot(
        col: Int, row: Int, cols: Int, rows: Int, flipEdge: FlipEdge
    ): Pair<Int, Int> = when (flipEdge) {
        FlipEdge.LONG_EDGE  -> (cols - 1 - col) to row
        FlipEdge.SHORT_EDGE -> col to (rows - 1 - row)
    }

    private fun buildDateString(template: Template): String {
        // Search both sides for a DateElement
        val dateEl = (template.elements + (template.backElements ?: emptyList()))
            .filterIsInstance<TemplateElement.DateElement>().firstOrNull() ?: return ""
        val formatter = DateTimeFormatter.ofPattern(dateEl.format.pattern)
        return LocalDate.now().format(formatter)
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    data class LayoutInfo(
        val columns: Int,
        val rows: Int,
        val cardsPerPage: Int,
        val cardWidthPt: Float,
        val cardHeightPt: Float,
        val pageWidthPt: Float,
        val pageHeightPt: Float,
        val marginLeftPt: Float,
        val marginTopPt: Float,
        val settings: ExportSettings
    ) {
        fun pageCount(totalRecords: Int): Int =
            if (totalRecords == 0) 0
            else ceil(totalRecords.toDouble() / cardsPerPage).toInt()
    }
}

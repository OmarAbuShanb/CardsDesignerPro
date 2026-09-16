package dev.anonymous.cardsdesignerpro.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix

import dev.anonymous.cardsdesignerpro.app.data.model.ExportSettings
import dev.anonymous.cardsdesignerpro.app.data.model.FlipEdge
import dev.anonymous.cardsdesignerpro.app.data.model.Template
import dev.anonymous.cardsdesignerpro.app.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.app.data.model.TextAlign
import dev.anonymous.cardsdesignerpro.app.data.parser.ParseResult
import dev.anonymous.cardsdesignerpro.app.ui.editor.canvas.RenderLayer
import dev.anonymous.cardsdesignerpro.app.ui.editor.canvas.TemplateRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import androidx.core.graphics.createBitmap

/**
 * PDFBox-based PDF exporter with optimised resource reuse.
 *
 * Architecture (refactored):
 *  1. **Static layer** — template decorations (bg, shapes, lines, frames, text, images, date).
 *     Built once as a [PDFormXObject] with native PDF vector/text commands,
 *     reused via `drawForm()` for every card slot. Falls back to a single
 *     Lossless PNG XObject if form building fails.
 *  2. **Dynamic layer** — per-card content:
 *     - **Username/Password**: drawn as real PDF text directly on the page content stream.
 *     - **QR code**: rendered as a small bounded bitmap [PDImageXObject] at actual QR
 *       element bounds (not full-card), cached per content+style.
 *     - **Date**: drawn as PDF text (same for all cards in one export).
 *
 * Resource management:
 *  - [PdfResourceCache] caches fonts, images, and QR bitmaps per [PDDocument].
 *  - Fonts are loaded once per font/style via [PdfResourceCache.getFont].
 *  - Identical images share a single [PDImageXObject].
 *  - [PdfExportAnalyzer] logs resource counts in debug builds.
 */
object PdfBoxExporter {



    /** Set to true to log PDF resource analysis after export. */
    private val DEBUG_ANALYZE: Boolean
        get() = android.util.Log.isLoggable("PdfBoxExporter", android.util.Log.DEBUG)

    // ── Static resource wrapper ──────────────────────────────────────────────

    /** Wraps either a Form XObject (preferred) or a bitmap Image XObject (fallback). */
    private sealed class StaticResource {
        data class FormRes(val form: PDFormXObject) : StaticResource()
        data class ImageRes(val image: PDImageXObject) : StaticResource()
    }

    // ── Public API ───────────────────────────────────────────────────────────

    suspend fun export(
        context: Context, template: Template, records: ParseResult,
        shortRecords: ParseResult?, settings: ExportSettings,
        outputStream: OutputStream, onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        val renderer = createRenderer(context, template, settings)
        try {
            val layout = PdfLayoutCalculator.calculateLayout(template, settings)
            writeFace(context, renderer, layout, template, records, shortRecords, settings, outputStream, onProgress)
        } finally { renderer.clearBitmapCache() }
    }

    suspend fun exportDual(
        context: Context, template: Template, records: ParseResult,
        shortRecords: ParseResult?, settings: ExportSettings,
        outputStream: OutputStream, onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        val renderer = createRenderer(context, template, settings)
        val back = backFace(template) ?: run {
            writeFace(context, renderer, PdfLayoutCalculator.calculateLayout(template, settings),
                template, records, shortRecords, settings, outputStream, onProgress)
            renderer.clearBitmapCache(); return@withContext
        }
        try {
            val layout = PdfLayoutCalculator.calculateLayout(template, settings)
            val total = records.count.coerceAtLeast(1)
            val perPage = layout.cardsPerPage
            val pages = ceil(total.toDouble() / perPage).toInt()
            val showPN = settings.showPageNumbers && pages * 2 > 1
            val doc = PDDocument()
            val dateStr = dateString(template)
            val uCol = records.usernameColumn; val pCol = records.passwordColumn
            val fontsDir = java.io.File(context.filesDir, "templates/${template.id}/fonts")
            val cache = PdfResourceCache(doc, context, fontsDir)

            val frontStatic = buildStaticOrFallback(doc, renderer, template, layout, settings, cache, context, dateStr)
            val backStatic = buildStaticOrFallback(doc, renderer, back, layout, settings, cache, context, dateStr)

            var done = 0; var ci = 0
            for (pn in 1..pages) {
                if (!isActive) break
                val n = minOf(perPage, total - (pn - 1) * perPage)
                emitPage(doc, layout, template, renderer, records, shortRecords,
                    ci, n, uCol, pCol, dateStr, frontStatic, cache, settings,
                    false, showPN, pn * 2 - 1)
                done += n; onProgress(done, total * 2)
                emitPage(doc, layout, back, renderer, records, shortRecords,
                    ci, n, uCol, pCol, dateStr, backStatic, cache, settings,
                    true, showPN, pn * 2, settings.flipEdge)
                done += n; onProgress(done, total * 2)
                ci += n
            }
            if (DEBUG_ANALYZE) PdfExportAnalyzer.analyze(doc)
            doc.save(outputStream); doc.close()
            cache.close()
        } finally { renderer.clearBitmapCache() }
    }

    suspend fun exportSeparate(
        context: Context, template: Template, records: ParseResult,
        shortRecords: ParseResult?, settings: ExportSettings,
        frontStream: OutputStream, backStream: OutputStream,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        val renderer = createRenderer(context, template, settings)
        val back = backFace(template) ?: run {
            writeFace(context, renderer, PdfLayoutCalculator.calculateLayout(template, settings),
                template, records, shortRecords, settings, frontStream, onProgress)
            renderer.clearBitmapCache(); return@withContext
        }
        try {
            val layout = PdfLayoutCalculator.calculateLayout(template, settings)
            val total = records.count.coerceAtLeast(1)
            writeFace(context, renderer, layout, template, records, shortRecords, settings,
                frontStream) { d, _ -> onProgress(d, total * 2) }
            renderer.clearBitmapCache()
            writeFaceMirrored(context, renderer, layout, back, records, shortRecords, settings,
                backStream, settings.flipEdge) { d, _ -> onProgress(total + d, total * 2) }
        } finally { renderer.clearBitmapCache() }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun createRenderer(ctx: Context, t: Template, s: ExportSettings) =
        TemplateRenderer(ctx).apply {
            fontsDir = java.io.File(ctx.filesDir, "templates/${t.id}/fonts")
            maxImageDim = s.quality.maxImageDim; maxQrDim = s.quality.maxQrDim
        }

    private fun backFace(t: Template): Template? {
        val b = t.backElements ?: return null
        return t.copy(elements = b, card = t.backCard ?: t.card)
    }

    private fun dateString(t: Template): String {
        val el = (t.elements + (t.backElements ?: emptyList()))
            .filterIsInstance<TemplateElement.DateElement>().firstOrNull() ?: return ""
        return LocalDate.now().format(DateTimeFormatter.ofPattern(el.format.pattern))
    }

    // ── Static resource creation ─────────────────────────────────────────────

    /**
     * Attempts to build a [PDFormXObject] with native PDF drawing.
     * Falls back to the old bitmap XObject approach if form building fails.
     */
    private fun buildStaticOrFallback(
        doc: PDDocument,
        renderer: TemplateRenderer,
        template: Template,
        layout: PdfLayoutCalculator.LayoutInfo,
        settings: ExportSettings,
        cache: PdfResourceCache,
        context: Context,
        dateStr: String
    ): StaticResource {
        // Try native PDF form first
        val form = PdfStaticFormBuilder.buildStaticForm(
            doc, template, layout.cardWidthPt, layout.cardHeightPt,
            cache, context, settings.quality.renderScale, dateStr,
            renderer, settings.quality.maxImageDim
        )
        if (form != null) {
            return StaticResource.FormRes(form)
        }

        // Fallback: render to bitmap like the old approach
        android.util.Log.w("PdfBoxExporter", "Form build failed, using bitmap fallback for static template")
        val scale = settings.quality.renderScale
        return StaticResource.ImageRes(staticXObj(doc, renderer, template, layout, scale))
    }

    /** Old bitmap fallback: renders STATIC layer once → Lossless PNG XObject. */
    private fun staticXObj(
        doc: PDDocument, renderer: TemplateRenderer,
        template: Template, layout: PdfLayoutCalculator.LayoutInfo, scale: Float
    ): PDImageXObject {
        val w = (layout.cardWidthPt * scale).toInt().coerceAtLeast(1)
        val h = (layout.cardHeightPt * scale).toInt().coerceAtLeast(1)
        val bmp = createBitmap(w, h)
        renderer.draw(Canvas(bmp), template, 0f, 0f, w.toFloat(), h.toFloat(),
            renderScale = scale, renderLayer = RenderLayer.STATIC)
        val xObj = LosslessFactory.createFromImage(doc, bmp)
        bmp.recycle()
        return xObj
    }

    // ── Dynamic element helpers ──────────────────────────────────────────────

    /** Collects visible dynamic elements from the template in draw order (reversed). */
    private fun dynamicElements(template: Template): List<TemplateElement> =
        template.elements.asReversed().filter { it.isVisible }.filter {
            it is TemplateElement.UsernameElement ||
            it is TemplateElement.PasswordElement ||
            it is TemplateElement.QrElement
        }

    /**
     * Draws a single dynamic element (username/password/QR) directly onto the page content stream
     * at the given card position.
     *
     * @return true if drawn successfully, false if fallback bitmap rendering is needed for this element.
     */
    private fun drawDynamicElement(
        cs: PDPageContentStream,
        doc: PDDocument,
        el: TemplateElement,
        template: Template,
        username: String, password: String,
        shortUsername: String, shortPassword: String,
        dateStr: String,
        pX: Float, pY: Float,
        cW: Float, cH: Float,
        cache: PdfResourceCache,
        renderer: TemplateRenderer,
        renderScale: Float,
        maxQrDim: Int
    ): Boolean {
        val scaleX = cW / template.card.widthDp
        val scaleY = cH / (template.card.widthDp * template.card.heightRatio)

        when (el) {
            is TemplateElement.UsernameElement -> {
                val text = if (el.isShortVariant) shortUsername.ifEmpty { dummyDigits(el.digitCount.coerceAtLeast(1)) }
                           else username.ifEmpty { dummyDigits(el.digitCount.coerceAtLeast(1)) }
                return drawCredentialText(cs, el.fontName, el.isBold, el.textSizeSp, el.textColor,
                    el.bgColor, TextAlign.CENTER, el.rotation, el.textStrokeWidth, el.textStrokeColor,
                    el.x, el.y, el.width, el.height, text, pX, pY, cW, cH, scaleX, scaleY, cache)
            }
            is TemplateElement.PasswordElement -> {
                val text = if (el.isShortVariant) shortPassword.ifEmpty { dummyDigits(el.digitCount.coerceAtLeast(1)) }
                           else password.ifEmpty { dummyDigits(el.digitCount.coerceAtLeast(1)) }
                return drawCredentialText(cs, el.fontName, el.isBold, el.textSizeSp, el.textColor,
                    el.bgColor, TextAlign.CENTER, el.rotation, el.textStrokeWidth, el.textStrokeColor,
                    el.x, el.y, el.width, el.height, text, pX, pY, cW, cH, scaleX, scaleY, cache)
            }
            is TemplateElement.QrElement -> {
                val qrUser = if (el.linkToShortNumbers) shortUsername else username
                val qrPass = if (el.linkToShortNumbers) shortPassword else password
                val content = "http://${el.host}/login?username=${qrUser.ifEmpty { "username" }}&password=${qrPass.ifEmpty { "password" }}"

                // QR is always square — centered within element bounds
                val elL = el.x * scaleX
                val elT = el.y * scaleY
                val elW = el.width * scaleX
                val elH = el.height * scaleY
                val side = minOf(elW, elH)
                val qrL = pX + elL + (elW - side) / 2f
                val qrT = elT + (elH - side) / 2f
                val qrPdfY = pY + (cH - qrT - side)  // Convert to PDF y

                val bitmapSize = (side * renderScale).toInt().coerceIn(128, maxQrDim)
                val qrXObj = cache.getQrImage(renderer, content, el, bitmapSize) ?: return true // empty QR = skip

                cs.saveGraphicsState()
                if (el.rotation != 0f) {
                    val cx = pX + elL + elW / 2f
                    val cy = pY + (cH - elT - elH / 2f)
                    applyRotation(cs, el.rotation, cx, cy)
                }
                cs.drawImage(qrXObj, qrL, qrPdfY, side, side)
                cs.restoreGraphicsState()
                return true
            }
            else -> return true
        }
    }

    private fun drawCredentialText(
        cs: PDPageContentStream,
        fontName: String, isBold: Boolean, textSizeSp: Float,
        textColor: String, bgColor: String?, textAlign: TextAlign,
        rotation: Float, textStrokeWidth: Float, textStrokeColor: String,
        elX: Float, elY: Float, elW: Float, elH: Float,
        text: String,
        pX: Float, pY: Float, cW: Float, cH: Float,
        scaleX: Float, scaleY: Float,
        cache: PdfResourceCache
    ): Boolean {
        val font = cache.getFont(fontName, isBold)
        val fontSizePt = textSizeSp * scaleX

        val l = pX + elX * scaleX
        val t = elY * scaleY
        val w = elW * scaleX
        val h = elH * scaleY
        // Convert Android top-origin to PDF bottom-origin, offset by card position
        val pdfY = pY + (cH - t - h)

        return PdfTextDrawer.drawText(
            cs = cs, text = text, font = font, fontSizePt = fontSizePt,
            pdfX = l, pdfY = pdfY, elWidthPt = w, elHeightPt = h,
            textColor = textColor, bgColor = bgColor, textAlign = textAlign,
            rotation = rotation, textStrokeWidth = textStrokeWidth * scaleX,
            textStrokeColor = textStrokeColor, wrap = false, scaleX = scaleX
        )
    }

    private fun dummyDigits(count: Int): String =
        (1..count).joinToString("") { (it % 10).toString() }

    // ── Fallback: render a single dynamic element to bitmap ──────────────────

    /**
     * Renders a single dynamic element to a bounded bitmap and draws it as an image.
     * Used when [PdfTextDrawer] cannot encode the text (e.g. complex Arabic shaping).
     */
    private fun fallbackDynamicElement(
        cs: PDPageContentStream,
        doc: PDDocument,
        el: TemplateElement,
        template: Template,
        renderer: TemplateRenderer,
        username: String, password: String,
        shortUsername: String, shortPassword: String,
        dateStr: String,
        pX: Float, pY: Float,
        cW: Float, cH: Float,
        dynScale: Float
    ) {
        val dW = (cW * dynScale).toInt().coerceAtLeast(1)
        val dH = (cH * dynScale).toInt().coerceAtLeast(1)
        val dynBuf = createBitmap(dW, dH)
        dynBuf.eraseColor(Color.TRANSPARENT)
        renderer.draw(Canvas(dynBuf), template, 0f, 0f, dW.toFloat(), dH.toFloat(),
            username = username, password = password,
            shortUsername = shortUsername, shortPassword = shortPassword,
            date = dateStr, renderScale = dynScale, renderLayer = RenderLayer.DYNAMIC)
        val xObj = LosslessFactory.createFromImage(doc, dynBuf)
        cs.drawImage(xObj, pX, pY, cW, cH)
        dynBuf.recycle()
    }

    // ── Page writing ─────────────────────────────────────────────────────────

    private suspend fun writeFace(
        context: Context,
        renderer: TemplateRenderer, layout: PdfLayoutCalculator.LayoutInfo,
        template: Template, records: ParseResult, shortRecords: ParseResult?,
        settings: ExportSettings, out: OutputStream,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) {
        val doc = PDDocument()
        val total = records.count.coerceAtLeast(1)
        val pages = ceil(total.toDouble() / layout.cardsPerPage).toInt()
        val showPN = settings.showPageNumbers && pages > 1
        val fontsDir = java.io.File(context.filesDir, "templates/${template.id}/fonts")
        val cache = PdfResourceCache(doc, context, fontsDir)
        val dateStr = dateString(template)
        val staticRes = buildStaticOrFallback(doc, renderer, template, layout, settings, cache, context, dateStr)
        var ci = 0
        for (pn in 1..pages) {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
            val n = minOf(layout.cardsPerPage, total - (pn - 1) * layout.cardsPerPage)
            emitPage(doc, layout, template, renderer, records, shortRecords,
                ci, n, records.usernameColumn, records.passwordColumn,
                dateStr, staticRes, cache, settings, false, showPN, pn)
            ci += n; onProgress(ci, total)
        }
        if (DEBUG_ANALYZE) PdfExportAnalyzer.analyze(doc)
        doc.save(out); doc.close()
        cache.close()
    }

    private suspend fun writeFaceMirrored(
        context: Context,
        renderer: TemplateRenderer, layout: PdfLayoutCalculator.LayoutInfo,
        template: Template, records: ParseResult, shortRecords: ParseResult?,
        settings: ExportSettings, out: OutputStream, flipEdge: FlipEdge,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) {
        val doc = PDDocument()
        val total = records.count.coerceAtLeast(1)
        val pages = ceil(total.toDouble() / layout.cardsPerPage).toInt()
        val showPN = settings.showPageNumbers && pages > 1
        val fontsDir = java.io.File(context.filesDir, "templates/${template.id}/fonts")
        val cache = PdfResourceCache(doc, context, fontsDir)
        val dateStr = dateString(template)
        val staticRes = buildStaticOrFallback(doc, renderer, template, layout, settings, cache, context, dateStr)
        var ci = 0
        for (pn in 1..pages) {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
            val n = minOf(layout.cardsPerPage, total - (pn - 1) * layout.cardsPerPage)
            emitPage(doc, layout, template, renderer, records, shortRecords,
                ci, n, records.usernameColumn, records.passwordColumn,
                dateStr, staticRes, cache, settings, true, showPN, pn, flipEdge)
            ci += n; onProgress(ci, total)
        }
        if (DEBUG_ANALYZE) PdfExportAnalyzer.analyze(doc)
        doc.save(out); doc.close()
        cache.close()
    }

    private fun emitPage(
        doc: PDDocument, layout: PdfLayoutCalculator.LayoutInfo,
        template: Template, renderer: TemplateRenderer,
        records: ParseResult, shortRecords: ParseResult?,
        startIdx: Int, cardsOnPage: Int,
        uCol: String?, pCol: String?, dateStr: String,
        staticRes: StaticResource, cache: PdfResourceCache,
        settings: ExportSettings,
        mirrored: Boolean, showPN: Boolean, pageNum: Int,
        flipEdge: FlipEdge = FlipEdge.LONG_EDGE
    ) {
        val page = PDPage(PDRectangle(layout.pageWidthPt, layout.pageHeightPt))
        doc.addPage(page)

        val dynElements = dynamicElements(template)
        val renderScale = settings.quality.renderScale
        val dynScale = renderScale * 0.75f

        PDPageContentStream(doc, page).use { cs ->
            for (slot in 0 until cardsOnPage) {
                val sc = slot % layout.columns; val sr = slot / layout.columns
                val (dc, dr) = if (mirrored) mirror(sc, sr, layout.columns, layout.rows, flipEdge) else sc to sr
                val cL = layout.marginLeftPt + dc * (layout.cardWidthPt + settings.horizontalSpacingDp)
                val cT = layout.marginTopPt + dr * (layout.cardHeightPt + settings.verticalSpacingDp)
                val pX = cL; val pY = layout.pageHeightPt - cT - layout.cardHeightPt
                val cW = layout.cardWidthPt; val cH = layout.cardHeightPt

                val rot = mirrored && flipEdge == FlipEdge.SHORT_EDGE
                if (rot) {
                    cs.saveGraphicsState()
                    val m = Matrix()
                    m.setValue(0, 0, -1f); m.setValue(1, 1, -1f)
                    m.setValue(2, 0, 2f * (pX + cW / 2f)); m.setValue(2, 1, 2f * (pY + cH / 2f))
                    cs.transform(m)
                }

                // ── 1. Draw static template (reused Form XObject or fallback bitmap) ──
                when (staticRes) {
                    is StaticResource.FormRes -> {
                        cs.saveGraphicsState()
                        // Translate to card position — form BBox is (0,0,cW,cH)
                        cs.transform(Matrix.getTranslateInstance(pX, pY))
                        cs.drawForm(staticRes.form)
                        cs.restoreGraphicsState()
                    }
                    is StaticResource.ImageRes -> {
                        cs.drawImage(staticRes.image, pX, pY, cW, cH)
                    }
                }

                // ── 2. Draw dynamic content directly ──
                val ri = startIdx + slot
                val rec = if (ri < records.records.size) records.records[ri] else emptyMap()
                val sRec = if (shortRecords != null && ri < shortRecords.records.size)
                    shortRecords.records[ri] else emptyMap()
                val u = rec[uCol ?: ""] ?: ""
                val p = rec[pCol ?: ""] ?: ""
                val su = sRec[uCol ?: ""] ?: ""
                val sp = sRec[pCol ?: ""] ?: ""

                var usedFallback = false
                for (dynEl in dynElements) {
                    val success = drawDynamicElement(
                        cs, doc, dynEl, template, u, p, su, sp, dateStr,
                        pX, pY, cW, cH, cache, renderer, renderScale,
                        settings.quality.maxQrDim
                    )
                    if (!success) {
                        // Native text failed for this element — use full-card dynamic bitmap fallback
                        usedFallback = true
                        break
                    }
                }

                // If any dynamic element failed native drawing, fall back to old full-card bitmap
                if (usedFallback) {
                    fallbackDynamicElement(cs, doc, template.elements.first(), template,
                        renderer, u, p, su, sp, dateStr, pX, pY, cW, cH, dynScale)
                }

                if (rot) cs.restoreGraphicsState()
            }
            if (showPN) drawPageNum(cs, layout, pageNum, mirrored, flipEdge)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun mirror(c: Int, r: Int, cols: Int, rows: Int, f: FlipEdge) = when (f) {
        FlipEdge.LONG_EDGE -> (cols - 1 - c) to r
        FlipEdge.SHORT_EDGE -> c to (rows - 1 - r)
    }

    private fun applyRotation(cs: PDPageContentStream, degrees: Float, cx: Float, cy: Float) {
        val rad = Math.toRadians((-degrees).toDouble())
        val cos = Math.cos(rad).toFloat()
        val sin = Math.sin(rad).toFloat()
        val m = Matrix()
        m.setValue(0, 0, cos)
        m.setValue(0, 1, sin)
        m.setValue(1, 0, -sin)
        m.setValue(1, 1, cos)
        m.setValue(2, 0, cx - cos * cx + sin * cy)
        m.setValue(2, 1, cy - sin * cx - cos * cy)
        cs.transform(m)
    }

    private fun drawPageNum(cs: PDPageContentStream, l: PdfLayoutCalculator.LayoutInfo,
                            n: Int, m: Boolean, f: FlipEdge) {
        val w = l.pageWidthPt; val h = l.pageHeightPt; val mn = minOf(w, h)
        val mg = (mn * 0.012f).coerceAtLeast(4f); val fs = (mn * 0.016f).coerceIn(8f, 12f)
        val t = n.toString(); val tw = fs * t.length * 0.5f
        cs.beginText(); cs.setFont(PDType1Font.HELVETICA, fs); cs.setNonStrokingColor(0f, 0f, 0f)
        if (!m || f == FlipEdge.LONG_EDGE) cs.newLineAtOffset(w - mg - tw, mg)
        else cs.newLineAtOffset(mg, h - mg - fs)
        cs.showText(t); cs.endText()
    }
}

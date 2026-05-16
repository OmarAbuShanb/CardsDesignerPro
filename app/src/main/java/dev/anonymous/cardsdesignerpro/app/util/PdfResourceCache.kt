package dev.anonymous.cardsdesignerpro.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import dev.anonymous.cardsdesignerpro.app.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.app.ui.editor.canvas.TemplateRenderer
import java.io.File
import java.io.FileInputStream

/**
 * Document-scoped cache for PDF resources.
 *
 * Ensures that fonts, images, and QR bitmaps are loaded/created exactly once
 * per [PDDocument] and reused across all pages and cards.
 *
 * Must call [close] when the document is finished to recycle Android bitmaps.
 */
class PdfResourceCache(
    private val doc: PDDocument,
    private val context: Context,
    private val fontsDir: File?
) {
    // ── Font cache ───────────────────────────────────────────────────────────

    /** Key: "fontName|bold" → PDFont for this document. */
    private val fontCache = mutableMapOf<String, PDFont>()

    /**
     * Returns a cached [PDFont] for the given font name and bold flag.
     *
     * Built-in fonts map to PDF standard Type1 fonts.
     * Custom fonts (prefixed "custom:") and resource fonts are loaded via
     * [PDType0Font.load] with subsetting enabled.
     */
    fun getFont(fontName: String, isBold: Boolean): PDFont {
        val key = "$fontName|$isBold"
        return fontCache.getOrPut(key) { loadPdfFont(fontName, isBold) }
    }

    private fun loadPdfFont(fontName: String, isBold: Boolean): PDFont {
        // Built-in standard PDF fonts
        val lower = fontName.lowercase()
        if (lower == "default" || lower.isEmpty()) {
            return if (isBold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
        }
        if (lower == "serif") {
            return if (isBold) PDType1Font.TIMES_BOLD else PDType1Font.TIMES_ROMAN
        }
        if (lower == "monospace") {
            return if (isBold) PDType1Font.COURIER_BOLD else PDType1Font.COURIER
        }
        if (lower == "sans-serif") {
            return if (isBold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
        }

        // Custom font from template's font directory
        if (fontName.startsWith("custom:")) {
            val fileName = fontName.removePrefix("custom:")
            val file = fontsDir?.let { File(it, fileName) }
            if (file != null && file.exists()) {
                return runCatching {
                    FileInputStream(file).use { stream ->
                        PDType0Font.load(doc, stream, true)
                    }
                }.getOrElse {
                    if (isBold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
                }
            }
        }

        // Resource font — try to find TTF in res/font
        // We try specific variants first (e.g. "cairo_bold"), then fall back to "cairo_regular"
        return runCatching {
            // Try the specific bold/regular TTF variant first
            val targetName = if (isBold) "${fontName}_bold" else "${fontName}_regular"
            val targetId = context.resources.getIdentifier(targetName, "font", context.packageName)

            // Fall back to the base font name if specific variant not found
            val baseId = if (targetId == 0) {
                context.resources.getIdentifier(fontName, "font", context.packageName)
            } else 0

            val finalId = if (targetId != 0) targetId else baseId

            if (finalId != 0) {
                // openRawResource works for individual TTF files in res/font/
                context.resources.openRawResource(finalId).use { stream ->
                    PDType0Font.load(doc, stream, true)
                }
            } else {
                if (isBold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
            }
        }.getOrElse {
            android.util.Log.w("PdfResourceCache", "Font load failed for '$fontName': ${it.message}")
            if (isBold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
        }
    }

    // ── Image cache ──────────────────────────────────────────────────────────

    /** Key: "path|maxDim|tintColor" → PDImageXObject. */
    private val imageCache = mutableMapOf<String, PDImageXObject>()

    /** Android bitmaps that need to be recycled on close. */
    private val managedBitmaps = mutableListOf<Bitmap>()

    /**
     * Returns a cached [PDImageXObject] for the given image path.
     *
     * If the same image with the same settings was already loaded, returns the
     * existing XObject (stored once in the PDF, referenced many times).
     */
    fun getImage(
        path: String,
        maxDim: Int,
        tintColor: String? = null
    ): PDImageXObject? {
        val key = "$path|$maxDim|$tintColor"
        imageCache[key]?.let { return it }

        val bmp = loadAndTintBitmap(path, maxDim, tintColor) ?: return null
        val xObj = LosslessFactory.createFromImage(doc, bmp)
        managedBitmaps.add(bmp)
        imageCache[key] = xObj
        return xObj
    }

    /** Loads a bitmap, optionally applying a tint, using the same decode logic as TemplateRenderer. */
    private fun loadAndTintBitmap(path: String, reqMaxDim: Int, tintColor: String?): Bitmap? {
        val isSvg = path.lowercase().endsWith(".svg")

        val rawBmp = if (isSvg) {
            loadSvgAsBitmap(path, reqMaxDim)
        } else if (path.startsWith("pack:")) {
            decodeSampled(reqMaxDim) { context.assets.open(path.removePrefix("pack:")) }
        } else if (path.startsWith("res:")) {
            runCatching {
                val resName = path.removePrefix("res:")
                val resId = context.resources.getIdentifier(resName, "drawable", context.packageName)
                if (resId == 0) null
                else {
                    val d = androidx.core.content.ContextCompat.getDrawable(context, resId) ?: return null
                    val b = createBitmap(reqMaxDim.coerceAtMost(1024), reqMaxDim.coerceAtMost(1024))
                    val c = Canvas(b)
                    d.setBounds(0, 0, b.width, b.height)
                    d.draw(c)
                    b
                }
            }.getOrNull()
        } else {
            decodeSampled(reqMaxDim) { FileInputStream(path) }
        }

        if (rawBmp == null) return null

        // Apply tint if needed
        if (tintColor != null) {
            val tinted = createBitmap(rawBmp.width, rawBmp.height)
            val c = Canvas(tinted)
            val p = Paint().apply {
                colorFilter = PorterDuffColorFilter(
                    runCatching { tintColor.toColorInt() }.getOrElse { android.graphics.Color.BLACK },
                    PorterDuff.Mode.SRC_IN
                )
            }
            c.drawBitmap(rawBmp, 0f, 0f, p)
            rawBmp.recycle()
            return tinted
        }

        return rawBmp
    }

    private fun loadSvgAsBitmap(path: String, reqMaxDim: Int): Bitmap? = runCatching {
        val stream = if (path.startsWith("pack:")) {
            context.assets.open(path.removePrefix("pack:"))
        } else {
            FileInputStream(path)
        }
        stream.use {
            val svg = com.caverock.androidsvg.SVG.getFromInputStream(it)
            val docW = if (svg.documentWidth > 0f) svg.documentWidth else reqMaxDim.toFloat()
            val docH = if (svg.documentHeight > 0f) svg.documentHeight else reqMaxDim.toFloat()
            val aspect = docW / docH
            val finalW = if (docW > docH) reqMaxDim.toFloat() else reqMaxDim * aspect
            val finalH = finalW / aspect
            val b = createBitmap(finalW.toInt().coerceAtLeast(1), finalH.toInt().coerceAtLeast(1))
            val c = Canvas(b)
            svg.documentWidth = finalW
            svg.documentHeight = finalH
            svg.renderToCanvas(c)
            b
        }
    }.getOrNull()

    private fun decodeSampled(reqMaxDim: Int, streamProvider: () -> java.io.InputStream?): Bitmap? = runCatching {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        streamProvider()?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null

        var inSampleSize = 1
        while ((options.outHeight / (inSampleSize * 2)) >= reqMaxDim ||
            (options.outWidth / (inSampleSize * 2)) >= reqMaxDim
        ) {
            inSampleSize *= 2
        }
        options.inJustDecodeBounds = false
        options.inSampleSize = inSampleSize
        streamProvider()?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    // ── QR cache ─────────────────────────────────────────────────────────────

    /** Key: "content|styleHash|size" → PDImageXObject. */
    private val qrCache = mutableMapOf<String, PDImageXObject>()

    /**
     * Returns a cached [PDImageXObject] for a QR code with the given content and style.
     *
     * QR bitmaps are generated at [sizePx] (the actual QR element size, NOT the full card),
     * then converted to a PDF image XObject once and reused if the same content+style appears.
     */
    fun getQrImage(
        renderer: TemplateRenderer,
        content: String,
        el: TemplateElement.QrElement,
        sizePx: Int
    ): PDImageXObject? {
        val key = "qr|$content|$sizePx|${el.qrColor}|${el.backgroundColor}" +
                "|${el.pixelShape}|${el.eyeShape}|${el.qrPadding}" +
                "|${el.logoPath}|${el.logoSizeFraction}|${el.tintLogo}"
        qrCache[key]?.let { return it }

        val bmp = renderer.generateQrBitmap(content, sizePx, el) ?: return null
        val xObj = LosslessFactory.createFromImage(doc, bmp)
        managedBitmaps.add(bmp)
        qrCache[key] = xObj
        return xObj
    }

    // ── SVG rendering for element bounds ─────────────────────────────────────

    /**
     * Renders an SVG at the given element bounds and caches the result.
     * Used for ImageElement with SVG paths — avoids full-card rasterization.
     */
    fun getSvgImage(
        path: String,
        widthPx: Int,
        heightPx: Int,
        tintColor: String? = null
    ): PDImageXObject? {
        val key = "svg|$path|${widthPx}x${heightPx}|$tintColor"
        imageCache[key]?.let { return it }

        val bmp = renderSvgToBitmap(path, widthPx, heightPx, tintColor) ?: return null
        val xObj = LosslessFactory.createFromImage(doc, bmp)
        managedBitmaps.add(bmp)
        imageCache[key] = xObj
        return xObj
    }

    private fun renderSvgToBitmap(
        path: String,
        widthPx: Int,
        heightPx: Int,
        tintColor: String?
    ): Bitmap? = runCatching {
        val stream = if (path.startsWith("pack:")) {
            context.assets.open(path.removePrefix("pack:"))
        } else {
            FileInputStream(path)
        }
        stream.use { ins ->
            val svg = com.caverock.androidsvg.SVG.getFromInputStream(ins)
            val w = widthPx.toFloat().coerceAtLeast(1f)
            val h = heightPx.toFloat().coerceAtLeast(1f)
            svg.documentWidth = w
            svg.documentHeight = h

            val bmp = createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
            val canvas = Canvas(bmp)

            if (tintColor != null) {
                // Render to layer, then tint
                val layerPaint = Paint().apply {
                    colorFilter = PorterDuffColorFilter(
                        runCatching { tintColor.toColorInt() }.getOrElse { android.graphics.Color.BLACK },
                        PorterDuff.Mode.SRC_IN
                    )
                }
                @Suppress("DEPRECATION")
                val sc = canvas.saveLayer(RectF(0f, 0f, w, h), layerPaint)
                svg.renderToCanvas(canvas)
                canvas.restoreToCount(sc)
            } else {
                svg.renderToCanvas(canvas)
            }
            bmp
        }
    }.getOrNull()

    // ── Cleanup ──────────────────────────────────────────────────────────────

    /** Recycles all managed Android bitmaps. Call after doc.save(). */
    fun close() {
        managedBitmaps.forEach { it.recycle() }
        managedBitmaps.clear()
        fontCache.clear()
        imageCache.clear()
        qrCache.clear()
    }
}

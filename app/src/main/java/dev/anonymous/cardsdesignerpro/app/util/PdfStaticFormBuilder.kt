package dev.anonymous.cardsdesignerpro.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.toColorInt
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import dev.anonymous.cardsdesignerpro.app.data.model.ExportQuality
import dev.anonymous.cardsdesignerpro.app.data.model.ImageScaleType
import dev.anonymous.cardsdesignerpro.app.data.model.Template
import dev.anonymous.cardsdesignerpro.app.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.app.data.model.TextAlign
import dev.anonymous.cardsdesignerpro.app.ui.editor.canvas.RenderLayer
import dev.anonymous.cardsdesignerpro.app.ui.editor.canvas.TemplateRenderer

/**
 * Builds a [PDFormXObject] representing the static (non-dynamic) content of a template face.
 *
 * Static elements (background, shapes, lines, frames, text labels, date, images) are drawn
 * using native PDF vector/text commands where possible. Each element has a fallback path:
 * if native drawing fails, the element is rasterized at its own bounds and placed as a
 * small [PDImageXObject] inside the form.
 *
 * The resulting form is stored once in the PDF and reused via `cs.drawForm()` for every card slot,
 * massively reducing file size compared to the old per-bitmap approach.
 */
object PdfStaticFormBuilder {

    /**
     * Builds a reusable PDFormXObject for the given template face.
     *
     * @return the form XObject, or null if building fails entirely (caller should use bitmap fallback)
     */
    fun buildStaticForm(
        doc: PDDocument,
        template: Template,
        cardWidthPt: Float,
        cardHeightPt: Float,
        cache: PdfResourceCache,
        context: Context,
        renderScale: Float,
        dateStr: String,
        renderer: TemplateRenderer,
        maxImageDim: Int = 2048
    ): PDFormXObject? = runCatching {
        // Create form with BBox matching card dimensions
        val form = PDFormXObject(doc)
        form.bBox = PDRectangle(cardWidthPt, cardHeightPt)
        form.resources = com.tom_roush.pdfbox.pdmodel.PDResources()

        val cs = PDPageContentStream(doc, form, form.stream.createOutputStream())

        // Clip to card bounds — ensures rotated elements don't overflow into
        // neighboring card slots (BBox alone isn't enforced by all renderers)
        cs.saveGraphicsState()
        cs.addRect(0f, 0f, cardWidthPt, cardHeightPt)
        cs.clip()
        // 'n' operator = newpath (clears path without drawing). Using reflection because
        // PDFBox-Android doesn't expose endPath() / newPath() directly.
        try {
            val writeOp = cs.javaClass.getDeclaredMethod("writeOperator", String::class.java)
            writeOp.isAccessible = true
            writeOp.invoke(cs, "n")
        } catch (_: Exception) {
            // If reflection fails, fill with white as safe fallback
            cs.setNonStrokingColor(255f, 255f, 255f)
            cs.fill()
        }

        val scaleX = cardWidthPt / template.card.widthDp
        val scaleY = cardHeightPt / (template.card.widthDp * template.card.heightRatio)

        // Iterate in the same order as TemplateRenderer (reversed = back-to-front)
        template.elements.asReversed().filter { it.isVisible }.forEach { el ->
            // Skip dynamic elements — they are drawn per-card on the page
            if (el is TemplateElement.UsernameElement ||
                el is TemplateElement.PasswordElement ||
                el is TemplateElement.QrElement
            ) return@forEach

            // Wrap each element in save/restore to guarantee graphics state isolation.
            // If any element throws after applying a transform (rotation, etc.),
            // the restore ensures the transform never leaks to subsequent elements.
            cs.saveGraphicsState()
            runCatching {
                when (el) {
                    is TemplateElement.CardBackground ->
                        drawCardBackground(cs, doc, template, cardWidthPt, cardHeightPt, cache, renderScale, maxImageDim)
                    is TemplateElement.ShapeElement ->
                        drawShape(cs, el, scaleX, scaleY, cardHeightPt)
                    is TemplateElement.LineElement ->
                        drawLine(cs, el, scaleX, scaleY, cardHeightPt)
                    is TemplateElement.FrameElement ->
                        drawFrame(cs, el, scaleX, scaleY, cardHeightPt)
                    is TemplateElement.TextElement ->
                        drawStaticTextOrFallback(cs, doc, el, el.text, scaleX, scaleY,
                            cardWidthPt, cardHeightPt, cache, true, renderer, template, renderScale)
                    is TemplateElement.DateElement ->
                        drawStaticTextOrFallback(cs, doc, el, dateStr, scaleX, scaleY,
                            cardWidthPt, cardHeightPt, cache, false, renderer, template, renderScale)
                    is TemplateElement.ImageElement ->
                        drawImage(cs, doc, el, scaleX, scaleY, cardHeightPt, cache, renderScale, maxImageDim)
                    else -> { /* UsernameElement, PasswordElement, QrElement already filtered */ }
                }
            }.onFailure {
                // Element-level fallback: if any single element fails to draw natively,
                // rasterize just this element at its bounds.
                android.util.Log.w("PdfStaticFormBuilder", "Element ${el.id} error, using bitmap fallback: ${it.message}")
                runCatching {
                    rasterizeElementFallback(cs, doc, el, template, renderer,
                        scaleX, scaleY, cardWidthPt, cardHeightPt, renderScale)
                }
            }
            cs.restoreGraphicsState()
        }

        cs.restoreGraphicsState() // close the clip rect state
        cs.close()
        form
    }.getOrNull()

    // ── Card Background ──────────────────────────────────────────────────────

    private fun drawCardBackground(
        cs: PDPageContentStream,
        doc: PDDocument,
        template: Template,
        w: Float, h: Float,
        cache: PdfResourceCache,
        renderScale: Float,
        maxImageDim: Int
    ) {
        val card = template.card

        // Fill background color
        val (r, g, b) = colorRGB(card.backgroundColor)
        cs.setNonStrokingColor(r, g, b)
        cs.addRect(0f, 0f, w, h)
        cs.fill()

        // Background image
        card.backgroundImagePath?.let { path ->
            val maxDim = (maxOf(w, h) * renderScale * 2f).toInt().coerceIn(64, maxImageDim)
            val imgXObj = cache.getImage(path, maxDim) ?: return@let

            val isCenterCrop = card.backgroundImageScaleType == ImageScaleType.CENTER_CROP.name
            if (isCenterCrop) {
                val imgW = imgXObj.width.toFloat()
                val imgH = imgXObj.height.toFloat()
                val scale = maxOf(w / imgW, h / imgH)
                val drawW = imgW * scale
                val drawH = imgH * scale
                val dx = (w - drawW) / 2f
                val dy = (h - drawH) / 2f
                // Clip to card bounds
                cs.saveGraphicsState()
                cs.addRect(0f, 0f, w, h)
                cs.clip()
                // Note: Some PDFBox versions need a call after clip
                try { cs.fill() } catch (_: Exception) {}
                cs.drawImage(imgXObj, dx, dy, drawW, drawH)
                cs.restoreGraphicsState()
            } else {
                cs.drawImage(imgXObj, 0f, 0f, w, h)
            }
        }
    }

    // ── Shape ─────────────────────────────────────────────────────────────────

    private fun drawShape(
        cs: PDPageContentStream,
        el: TemplateElement.ShapeElement,
        sX: Float, sY: Float,
        cardH: Float
    ) {
        val l = el.x * sX
        val t = el.y * sY
        val w = el.width * sX
        val h = el.height * sY
        // Convert Android top-left origin to PDF bottom-left origin
        val pdfY = cardH - t - h
        val cx = l + w / 2f
        val cy = pdfY + h / 2f
        val cr = el.cornerRadiusDp * sX

        cs.saveGraphicsState()
        if (el.rotation != 0f) {
            applyRotation(cs, el.rotation, cx, cy)
        }

        // Fill (only if alpha > 0)
        if (el.fillColor.isNotEmpty() && colorAlpha(el.fillColor) > 0) {
            val (r, g, b) = colorRGB(el.fillColor)
            cs.setNonStrokingColor(r, g, b)
            addRoundRect(cs, l, pdfY, w, h, cr)
            cs.fill()
        }

        // Stroke
        if (el.strokeWidthDp > 0f) {
            val (sr, sg, sb) = colorRGB(el.strokeColor)
            cs.setStrokingColor(sr, sg, sb)
            cs.setLineWidth(el.strokeWidthDp * sX)
            if (el.isDashed) {
                cs.setLineDashPattern(
                    floatArrayOf(el.dashLengthDp * sX, el.dashGapDp * sX), 0f
                )
                if (el.isDashRounded) cs.setLineCapStyle(1) // Round cap
            }
            addRoundRect(cs, l, pdfY, w, h, cr)
            cs.stroke()
        }

        cs.restoreGraphicsState()
    }

    // ── Line ──────────────────────────────────────────────────────────────────

    private fun drawLine(
        cs: PDPageContentStream,
        el: TemplateElement.LineElement,
        sX: Float, sY: Float,
        cardH: Float
    ) {
        val l = el.x * sX
        val t = el.y * sY
        val w = el.width * sX
        val h = el.height * sY
        val pdfY = cardH - t - h
        val cx = l + w / 2f
        val cy = pdfY + h / 2f

        cs.saveGraphicsState()
        if (el.rotation != 0f) {
            applyRotation(cs, el.rotation, cx, cy)
        }

        val (r, g, b) = colorRGB(el.color)
        cs.setNonStrokingColor(r, g, b)

        if (el.roundedCaps) {
            val cr = h / 2f
            addRoundRect(cs, l, pdfY, w, h, cr)
        } else {
            cs.addRect(l, pdfY, w, h)
        }
        cs.fill()

        cs.restoreGraphicsState()
    }

    // ── Frame ─────────────────────────────────────────────────────────────────

    private fun drawFrame(
        cs: PDPageContentStream,
        el: TemplateElement.FrameElement,
        sX: Float, sY: Float,
        cardH: Float
    ) {
        val pad = el.paddingDp * sX
        val l = el.x * sX + pad
        val t = el.y * sY + pad
        val w = el.width * sX - pad * 2
        val h = el.height * sY - pad * 2
        val pdfY = cardH - t - h
        val cr = el.cornerRadiusDp * sX

        cs.saveGraphicsState()

        val (r, g, b) = colorRGB(el.color)
        cs.setStrokingColor(r, g, b)
        cs.setLineWidth(el.strokeWidthDp * sX)

        if (el.isDashed) {
            cs.setLineDashPattern(
                floatArrayOf(el.dashLengthDp * sX, el.dashGapDp * sX), 0f
            )
            if (el.isDashRounded) cs.setLineCapStyle(1)
        }

        addRoundRect(cs, l, pdfY, w, h, cr)
        cs.stroke()

        cs.restoreGraphicsState()
    }

    // ── Static Text ──────────────────────────────────────────────────────────

    /**
     * Draws a static text element as native PDF text, or falls back to element-bounds
     * bitmap rendering if font encoding fails after Arabic shaping.
     *
     * Arabic/Hebrew text is now handled natively via [PdfTextDrawer.shapeForPdf]
     * which uses Android ICU [ArabicShaping] + [Bidi] reordering.
     * Bitmap fallback is only used when the font truly cannot encode a character.
     */
    private fun drawStaticTextOrFallback(
        cs: PDPageContentStream,
        doc: PDDocument,
        el: TemplateElement,
        text: String,
        sX: Float, sY: Float,
        cardW: Float, cardH: Float,
        cache: PdfResourceCache,
        wrap: Boolean,
        renderer: TemplateRenderer,
        template: Template,
        renderScale: Float
    ) {

        // Try native PDF text
        val (fontName, isBold, textSizeSp, textColor, bgColor, textAlign,
            rotation, textStrokeWidth, textStrokeColor) = extractTextProps(el)

        val font = cache.getFont(fontName, isBold)
        val fontSizePt = textSizeSp * sX

        val l = el.x * sX
        val t = el.y * sY
        val w = el.width * sX
        val h = el.height * sY
        val pdfY = cardH - t - h

        val success = PdfTextDrawer.drawText(
            cs = cs, text = text, font = font, fontSizePt = fontSizePt,
            pdfX = l, pdfY = pdfY, elWidthPt = w, elHeightPt = h,
            textColor = textColor, bgColor = bgColor, textAlign = textAlign,
            rotation = rotation, textStrokeWidth = textStrokeWidth * sX,
            textStrokeColor = textStrokeColor, wrap = wrap, scaleX = sX
        )

        if (!success) {
            // Font couldn't encode the text — fall back to bitmap for this element
            android.util.Log.d("PdfStaticFormBuilder", "Text encoding failed for '${el.id}', using bitmap fallback")
            rasterizeElementFallback(cs, doc, el, template, renderer,
                sX, sY, cardW, cardH, renderScale)
        }
    }

    /**
     * Rasterizes a single element at its own bounds using [TemplateRenderer] and
     * embeds the result as a small [PDImageXObject] inside the form/page.
     *
     * This is the per-element fallback for text with complex scripts (Arabic, etc.)
     * or any element that can't be drawn natively. Much smaller than rasterizing
     * the entire card.
     */
    private fun rasterizeElementFallback(
        cs: PDPageContentStream,
        doc: PDDocument,
        el: TemplateElement,
        template: Template,
        renderer: TemplateRenderer,
        sX: Float, sY: Float,
        cardW: Float, cardH: Float,
        renderScale: Float
    ) {
        // Render the single element at full card size.
        // This correctly handles rotation, clipping, and any transforms that
        // TemplateRenderer applies internally. The bitmap covers the full card
        // but only contains this one element (rest is transparent).
        // It's stored once in the form and reused for all card slots.
        // Text bitmaps always render at max quality — not affected by quality spinner
        val bmpScale = ExportQuality.FULL.renderScale
        val bmpW = (cardW * bmpScale).toInt().coerceAtLeast(1)
        val bmpH = (cardH * bmpScale).toInt().coerceAtLeast(1)

        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bmp)

        // Create a temporary single-element template for isolated rendering
        val singleElTemplate = template.copy(elements = listOf(el))
        renderer.draw(
            canvas = canvas,
            template = singleElTemplate,
            cardLeft = 0f,
            cardTop = 0f,
            cardWidthPx = bmpW.toFloat(),
            cardHeightPx = bmpH.toFloat(),
            renderScale = bmpScale,
            renderLayer = RenderLayer.STATIC
        )

        val imgXObj = LosslessFactory.createFromImage(doc, bmp)
        bmp.recycle()

        // Draw at full card size — form BBox is already card-sized, so (0,0,cardW,cardH)
        cs.drawImage(imgXObj, 0f, 0f, cardW, cardH)
    }

    // ── Image ─────────────────────────────────────────────────────────────────

    private fun drawImage(
        cs: PDPageContentStream,
        doc: PDDocument,
        el: TemplateElement.ImageElement,
        sX: Float, sY: Float,
        cardH: Float,
        cache: PdfResourceCache,
        renderScale: Float,
        maxImageDim: Int
    ) {
        val l = el.x * sX
        val t = el.y * sY
        val w = el.width * sX
        val h = el.height * sY
        val pdfY = cardH - t - h
        val cx = l + w / 2f
        val cy = pdfY + h / 2f

        cs.saveGraphicsState()
        if (el.rotation != 0f) {
            applyRotation(cs, el.rotation, cx, cy)
        }

        val path = el.imagePath
        val isSvg = path.lowercase().endsWith(".svg")
        val reqDim = (maxOf(w, h) * renderScale * 2f).toInt().coerceIn(64, maxImageDim)

        val imgXObj = if (isSvg) {
            // SVG always rasterized at max quality — not affected by quality spinner
            val svgScale = ExportQuality.FULL.renderScale
            cache.getSvgImage(path, (w * svgScale).toInt().coerceAtLeast(1),
                (h * svgScale).toInt().coerceAtLeast(1), el.tintColor)
        } else {
            cache.getImage(path, reqDim, el.tintColor)
        }

        imgXObj?.let {
            cs.drawImage(it, l, pdfY, w, h)
        }

        cs.restoreGraphicsState()
    }

    // ── Helper: Rotation ─────────────────────────────────────────────────────

    /** Applies rotation around a center point. Negated because Android CW = PDF CCW. */
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

    // ── Helper: Rounded Rect via Bézier ──────────────────────────────────────

    /** Kappa constant for circular arc approximation with cubic Bézier. */
    private const val KAPPA = 0.5522847498f

    /**
     * Adds a rounded rectangle path to the content stream using cubic Bézier curves.
     * Does NOT fill or stroke — caller must do cs.fill() or cs.stroke() after.
     */
    private fun addRoundRect(
        cs: PDPageContentStream,
        x: Float, y: Float,
        w: Float, h: Float,
        cr: Float
    ) {
        val r = cr.coerceAtMost(minOf(w, h) / 2f)
        if (r <= 0f) {
            cs.addRect(x, y, w, h)
            return
        }

        val k = r * KAPPA
        // Start at bottom-left, just above the corner
        cs.moveTo(x, y + r)
        // Bottom-left corner
        cs.curveTo(x, y + r - k, x + r - k, y, x + r, y)
        // Bottom edge
        cs.lineTo(x + w - r, y)
        // Bottom-right corner
        cs.curveTo(x + w - r + k, y, x + w, y + r - k, x + w, y + r)
        // Right edge
        cs.lineTo(x + w, y + h - r)
        // Top-right corner
        cs.curveTo(x + w, y + h - r + k, x + w - r + k, y + h, x + w - r, y + h)
        // Top edge
        cs.lineTo(x + r, y + h)
        // Top-left corner
        cs.curveTo(x + r - k, y + h, x, y + h - r + k, x, y + h - r)
        // Close path
        cs.closePath()
    }

    // ── Helper: Color parsing ────────────────────────────────────────────────

    private fun colorRGB(hex: String): Triple<Float, Float, Float> {
        val color = runCatching { hex.toColorInt() }.getOrElse { Color.BLACK }
        return Triple(
            ((color shr 16) and 0xFF).toFloat(),
            ((color shr 8) and 0xFF).toFloat(),
            (color and 0xFF).toFloat()
        )
    }

    /** Returns the alpha component (0..255) of a hex color string. 0 = fully transparent. */
    private fun colorAlpha(hex: String): Int {
        val color = runCatching { hex.toColorInt() }.getOrElse { Color.BLACK }
        return (color ushr 24) and 0xFF
    }

    // ── Helper: Extract text properties from element ─────────────────────────

    private data class TextProps(
        val fontName: String, val isBold: Boolean, val textSizeSp: Float,
        val textColor: String, val bgColor: String?, val textAlign: TextAlign,
        val rotation: Float, val textStrokeWidth: Float, val textStrokeColor: String
    )

    private fun extractTextProps(el: TemplateElement): TextProps = when (el) {
        is TemplateElement.TextElement -> TextProps(
            el.fontName, el.isBold, el.textSizeSp, el.textColor, el.bgColor,
            el.textAlign, el.rotation, el.textStrokeWidth, el.textStrokeColor
        )
        is TemplateElement.DateElement -> TextProps(
            el.fontName, el.isBold, el.textSizeSp, el.textColor, el.bgColor,
            TextAlign.CENTER, el.rotation, el.textStrokeWidth, el.textStrokeColor
        )
        is TemplateElement.UsernameElement -> TextProps(
            el.fontName, el.isBold, el.textSizeSp, el.textColor, el.bgColor,
            TextAlign.CENTER, el.rotation, el.textStrokeWidth, el.textStrokeColor
        )
        is TemplateElement.PasswordElement -> TextProps(
            el.fontName, el.isBold, el.textSizeSp, el.textColor, el.bgColor,
            TextAlign.CENTER, el.rotation, el.textStrokeWidth, el.textStrokeColor
        )
        else -> TextProps("default", false, 15f, "#000000", null, TextAlign.CENTER, 0f, 0f, "#000000")
    }
}

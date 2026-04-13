package dev.anonymous.cardsdesignerpro.ui.editor.canvas

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.drawable.toBitmap
import com.github.alexzhirkevich.customqrgenerator.QrData
import com.github.alexzhirkevich.customqrgenerator.QrErrorCorrectionLevel
import com.github.alexzhirkevich.customqrgenerator.style.BitmapScale
import com.github.alexzhirkevich.customqrgenerator.vector.QrCodeDrawable
import com.github.alexzhirkevich.customqrgenerator.vector.QrVectorOptions
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorBackground
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorBallShape
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorColor
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorColors
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorFrameShape
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorLogo
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorLogoPadding
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorPixelShape
import com.github.alexzhirkevich.customqrgenerator.vector.style.QrVectorShapes
import dev.anonymous.cardsdesignerpro.data.model.CardStyle
import dev.anonymous.cardsdesignerpro.data.model.DecorationShape
import dev.anonymous.cardsdesignerpro.data.model.Template
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter


/**
 * Pure-stateless renderer shared between [CardCanvasView] (screen) and [PdfExporter] (PDF).
 *
 * @param renderScale  Multiplier for raster-content (QR bitmaps). Use 1f for screen,
 *                     ~4f for 300-dpi PDF output so bitmaps look sharp when printed.
 */
class TemplateRenderer(private val context: Context) {

    private val paint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapCache = mutableMapOf<String, Bitmap?>()
    /** Separate cache for expensive QR bitmaps: key = content+style hash. */
    private val qrCache = mutableMapOf<String, Bitmap?>()
    private val svgCache = mutableMapOf<String, com.caverock.androidsvg.SVG?>()

    /**
     * Maximum bitmap dimensions for each content type.
     * Set before [draw] when exporting PDF to control quality per content type.
     * Screen rendering uses the defaults; PDF export overrides via [ExportQuality].
     */
    var maxImageDim: Int = 2048
    var maxPatternDim: Int = 2048
    var maxQrDim: Int = 1024

    /** Padding around text background (dp in template coordinate space). Must match CardCanvasView.TEXT_PAD_DP. */
    private val TEXT_PAD_DP = 6f

    fun draw(
        canvas: Canvas,
        template: Template,
        cardLeft: Float,
        cardTop: Float,
        cardWidthPx: Float,
        cardHeightPx: Float,
        username: String? = null,
        password: String? = null,
        date: String? = null,
        renderScale: Float = 1f
    ) {
        val scaleX = cardWidthPx / template.card.widthDp
        val scaleY = cardHeightPx / (template.card.widthDp * template.card.heightRatio)

        canvas.save()
        canvas.clipRect(cardLeft, cardTop, cardLeft + cardWidthPx, cardTop + cardHeightPx)

        template.elements.asReversed().filter { it.isVisible }.forEach { el ->
            when (el) {
                is TemplateElement.CardBackground ->
                    drawCardBackground(canvas, template, cardLeft, cardTop, cardWidthPx, cardHeightPx, renderScale)
                is TemplateElement.FrameElement ->
                    drawFrame(canvas, el, cardLeft, cardTop, scaleX, scaleY)
                is TemplateElement.TextElement ->
                    drawTextProps(canvas, textProps(el, el.text), cardLeft, cardTop, scaleX, scaleY)
                is TemplateElement.UsernameElement ->
                    drawTextProps(canvas, textProps(el, username ?: dummyDigits(el.digitCount)), cardLeft, cardTop, scaleX, scaleY)
                is TemplateElement.PasswordElement ->
                    drawTextProps(canvas, textProps(el, password ?: dummyDigits(el.digitCount)), cardLeft, cardTop, scaleX, scaleY)
                is TemplateElement.DateElement ->
                    drawTextProps(canvas, textProps(el, date ?: formatDate(el)), cardLeft, cardTop, scaleX, scaleY)
                is TemplateElement.ImageElement ->
                    drawImage(canvas, el, cardLeft, cardTop, scaleX, scaleY, renderScale)
                is TemplateElement.QrElement ->
                    drawQr(canvas, el, username, password, cardLeft, cardTop, scaleX, scaleY, renderScale)
            }
        }
        canvas.restore()
    }

    // ── Background ────────────────────────────────────────────────────────────

    private fun drawCardBackground(canvas: Canvas, template: Template, left: Float, top: Float, w: Float, h: Float, renderScale: Float) {
        val card = template.card
        paint.reset(); paint.color = parseColor(card.backgroundColor); paint.style = Paint.Style.FILL
        canvas.drawRect(left, top, left + w, top + h, paint)
        card.backgroundImagePath?.let { path ->
            if (path.lowercase().endsWith(".svg")) {
                loadSvg(path)?.let { svg ->
                    canvas.save()
                    canvas.translate(left, top)
                    val aspectW = if (svg.documentWidth > 0f) svg.documentWidth else w
                    val aspectH = if (svg.documentHeight > 0f) svg.documentHeight else h
                    canvas.scale(w / aspectW, h / aspectH)
                    svg.renderToCanvas(canvas)
                    canvas.restore()
                }
            } else {
                val reqDim = (maxOf(w, h) * renderScale * 2f).toInt().coerceIn(64, maxImageDim)
                loadBitmap(path, reqDim)?.let { bmp ->
                    // FIT_XY: stretch to fill the card completely
                    canvas.drawBitmap(bmp, null, RectF(left, top, left + w, top + h), null)
                }
            }
        }
        // Draw pattern from CardStyle (replaces the old BackgroundDecorationElement)
        if (card.patternEnabled) {
            drawPattern(canvas, card, left, top, w, h, template, renderScale)
        }
    }

    // ── Pattern (from CardStyle) ──────────────────────────────────────────────

    private fun drawPattern(canvas: Canvas, card: CardStyle,
                            left: Float, top: Float, w: Float, h: Float, template: Template, renderScale: Float) {
        val frame = template.elements.filterIsInstance<TemplateElement.FrameElement>().firstOrNull()
        val aL = if (frame != null) left + frame.paddingDp else left
        val aT = if (frame != null) top  + frame.paddingDp else top
        val aR = if (frame != null) left + w - frame.paddingDp else left + w
        val aB = if (frame != null) top  + h - frame.paddingDp else top  + h
        val aW = aR - aL; val aH = aB - aT
        val color = parseColor(card.patternColor)
        if (card.patternShape == DecorationShape.CUSTOM_IMAGE && card.patternCustomImagePath != null) {
            val applyTint = card.patternCustomImageTintEnabled
            drawPatternGrid(canvas, card.patternDensity, aL, aT, aW, aH, card.patternCustomImagePath, color, applyTint, renderScale); return
        }
        val cols = (4 + card.patternDensity * 8).toInt().coerceIn(2, 14)
        val rows = (cols * aH / aW).toInt().coerceAtLeast(2)
        val cellW = aW / cols; val cellH = aH / rows
        val rf = when (card.patternShape) {
            DecorationShape.DOTS_SMALL -> 0.08f
            DecorationShape.CIRCLES_HOLLOW -> 0.15f
            DecorationShape.STARS_FOUR_POINT -> 0.14f
            else -> 0.10f
        }
        paint.reset(); paint.color = color; paint.isAntiAlias = true
        paint.style = if (card.patternShape == DecorationShape.CIRCLES_HOLLOW) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = cellW * 0.05f
        for (row in 0 until rows) for (col in 0 until cols) {
            val cx = aL + cellW * (col + 0.5f); val cy = aT + cellH * (row + 0.5f)
            val r = minOf(cellW, cellH) * rf
            if (frame != null && frame.cornerRadiusDp > 0 && isCornerExclusion(cx, cy, aL, aT, aR, aB, frame.cornerRadiusDp)) continue

            if (card.patternShape == DecorationShape.STARS_FOUR_POINT) {
                val path = Path()
                path.moveTo(cx, cy - r)
                path.lineTo(cx + r * 0.3f, cy - r * 0.3f)
                path.lineTo(cx + r, cy)
                path.lineTo(cx + r * 0.3f, cy + r * 0.3f)
                path.lineTo(cx, cy + r)
                path.lineTo(cx - r * 0.3f, cy + r * 0.3f)
                path.lineTo(cx - r, cy)
                path.lineTo(cx - r * 0.3f, cy - r * 0.3f)
                path.close()
                canvas.drawPath(path, paint)
            } else {
                canvas.drawCircle(cx, cy, r, paint)
            }
        }
    }

    private fun drawPatternGrid(canvas: Canvas, density: Float,
                             aL: Float, aT: Float, aW: Float, aH: Float, path: String, tint: Int, applyTint: Boolean, renderScale: Float) {
        val cols = (4 + density * 8).toInt().coerceIn(2, 14)
        val rows = (cols * aH / aW).toInt().coerceAtLeast(2)
        val cellW = aW / cols; val cellH = aH / rows; val boundingSz = minOf(cellW, cellH) * 0.55f

        // Use 4x scale for sharpness
        val reqDim = (boundingSz * renderScale * 4f).toInt().coerceIn(48, maxPatternDim)
        val originalBmp = loadBitmap(path, reqDim) ?: return

        val aspect = originalBmp.width.toFloat() / originalBmp.height.toFloat()
        val w = if (aspect > 1f) boundingSz else boundingSz * aspect
        val h = if (aspect > 1f) boundingSz / aspect else boundingSz

        // Offscreen pre-render for PDF efficiency
        val offScale = renderScale * 3f
        val offW = (aW * offScale).toInt().coerceIn(1, maxPatternDim)
        val offH = (aH * offScale).toInt().coerceIn(1, maxPatternDim)
        val offBmp = Bitmap.createBitmap(offW, offH, Bitmap.Config.ARGB_8888)
        val offCanvas = Canvas(offBmp)
        offCanvas.translate(-aL * offScale, -aT * offScale)
        offCanvas.scale(offScale, offScale)

        paint.reset(); paint.isAntiAlias = true
        if (applyTint) {
            paint.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        } else {
            paint.colorFilter = null
        }
        for (row in 0 until rows) for (col in 0 until cols) {
            val cx = aL + cellW * (col + 0.5f); val cy = aT + cellH * (row + 0.5f)
            offCanvas.drawBitmap(originalBmp, null, RectF(cx - w/2, cy - h/2, cx + w/2, cy + h/2), paint)
        }
        paint.colorFilter = null

        canvas.drawBitmap(offBmp, null, RectF(aL, aT, aL + aW, aT + aH), null)
        offBmp.recycle()
    }

    // ── Frame ─────────────────────────────────────────────────────────────────

    private fun drawFrame(canvas: Canvas, el: TemplateElement.FrameElement, left: Float, top: Float, sX: Float, sY: Float) {
        // Apply paddingDp as an inset so the frame border moves inward from card edges
        val pad = el.paddingDp * sX
        val l = left  + el.x * sX + pad
        val t = top   + el.y * sY + pad
        val r = left  + (el.x + el.width)  * sX - pad
        val b = top   + (el.y + el.height) * sY - pad
        paint.reset(); paint.color = parseColor(el.color); paint.style = Paint.Style.STROKE
        paint.strokeWidth = el.strokeWidthDp * sX; paint.isAntiAlias = true
        if (el.isDashed) {
            paint.pathEffect = DashPathEffect(floatArrayOf(el.dashLengthDp * sX, el.dashGapDp * sX), 0f)
            if (el.isDashRounded) paint.strokeCap = Paint.Cap.ROUND
        }
        val cr = el.cornerRadiusDp * sX
        canvas.drawRoundRect(RectF(l, t, r, b), cr, cr, paint)
    }

    // ── Text (StaticLayout — handles Arabic ligatures & BiDi correctly) ───────

    private fun dummyDigits(count: Int): String {
        return (1..count).joinToString("") { (it % 10).toString() }
    }

    private fun drawTextProps(canvas: Canvas, p: TextProps, left: Float, top: Float, sX: Float, sY: Float) {
        val l = left + p.x * sX; val t = top + p.y * sY; val r = l + p.width * sX; val b = t + p.height * sY
        val cx = (l + r) / 2f; val cy = (t + b) / 2f

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColor(p.textColor)
            textSize = p.textSizeSp * sX
            typeface = resolveTypeface(p.fontName, p.isBold)
            letterSpacing = 0f
            isLinearText = true   // disables size-hinting → consistent metrics on screen vs PDF
        }

        // Measure each explicit line independently (never auto-wrap)
        val singleLineMaxW = p.text.split('\n').maxOf { tp.measureText(it) }
        // boxW must be at least the longest line to prevent StaticLayout from wrapping;
        // if shorter than element width, element width wins (so ALIGN_CENTER fills the box).
        val boxW = maxOf(singleLineMaxW.toInt() + 2, (r - l).toInt()).coerceAtLeast(1)

        val layout = StaticLayout.Builder
            .obtain(p.text, 0, p.text.length, tp, boxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()

        val actualTextH = layout.height.toFloat()
        val padPx = TEXT_PAD_DP * sX     // matches CardCanvasView selection box padding

        canvas.save()
        canvas.rotate(p.rotation, cx, cy)

        // Background rect sized to the actual longest rendered line
        p.bgColor?.let { bg ->
            val maxRenderedW = (0 until layout.lineCount)
                .maxOfOrNull { layout.getLineWidth(it) } ?: singleLineMaxW
            paint.reset(); paint.color = parseColor(bg); paint.style = Paint.Style.FILL
            val bgRect = android.graphics.RectF(
                cx - maxRenderedW / 2 - padPx, cy - actualTextH / 2 - padPx,
                cx + maxRenderedW / 2 + padPx, cy + actualTextH / 2 + padPx)
            canvas.drawRoundRect(bgRect, padPx / 2, padPx / 2, paint)
        }

        // Translate so the layout is CENTERED at element-center cx.
        // boxW ≥ singleLineMaxW, so StaticLayout never auto-wraps; \n creates explicit lines only.
        canvas.translate(cx - boxW / 2f, cy - actualTextH / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    // ── Image ─────────────────────────────────────────────────────────────────

    private fun drawImage(canvas: Canvas, el: TemplateElement.ImageElement, left: Float, top: Float, sX: Float, sY: Float, renderScale: Float) {
        val path = el.imagePath ?: return
        val w = el.width * sX
        val h = el.height * sY
        val l = left + el.x * sX; val t = top + el.y * sY; val r = l + w; val b = t + h
        
        paint.reset(); paint.isAntiAlias = true
        el.tintColor?.let { paint.colorFilter = PorterDuffColorFilter(parseColor(it), PorterDuff.Mode.SRC_IN) }
        
        canvas.save()
        canvas.rotate(el.rotation, (l+r)/2, (t+b)/2)

        if (path.lowercase().endsWith(".svg")) {
            val svg = loadSvg(path)
            if (svg != null) {
                val aspectW = if (svg.documentWidth > 0f) svg.documentWidth else w
                val aspectH = if (svg.documentHeight > 0f) svg.documentHeight else h
                canvas.save()
                canvas.translate(l, t)
                canvas.scale(w / aspectW, h / aspectH)
                if (el.tintColor != null) {
                    canvas.saveLayer(null, paint)
                    svg.renderToCanvas(canvas)
                    canvas.restore()
                } else {
                    svg.renderToCanvas(canvas)
                }
                canvas.restore()
            }
        } else {
            val reqDim = (maxOf(w, h) * renderScale * 2f).toInt().coerceIn(64, maxImageDim)
            val bmp = loadBitmap(path, reqDim)
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, RectF(l, t, r, b), paint)
            }
        }
        
        canvas.restore(); paint.colorFilter = null
    }

    // ── QR ────────────────────────────────────────────────────────────────────

    private fun drawQr(canvas: Canvas, el: TemplateElement.QrElement, username: String?, password: String?,
                       left: Float, top: Float, sX: Float, sY: Float, renderScale: Float) {
        val content = "http://${el.host}/login?username=${username ?: "username"}&password=${password ?: "password"}"
        val l = left + el.x * sX; val t = top + el.y * sY; val r = l + el.width * sX; val b = t + el.height * sY

        // Always square — centered within element bounds
        val side = minOf(r - l, b - t)
        val qrL = l + (r - l - side) / 2f
        val qrT = t + (b - t - side) / 2f

        // Scan clarity doesn't require massive bitmaps; restrict it to 1024 to prevent memory exhaustion
        val bitmapSize = (side * renderScale * 4f).toInt().coerceIn(128, maxQrDim.coerceAtMost(1024))
        val qrBmp = generateQrBitmapForEl(content, bitmapSize, el) ?: return

        canvas.save()
        canvas.rotate(el.rotation, (l + r) / 2, (t + b) / 2)
        canvas.drawBitmap(qrBmp, null, RectF(qrL, qrT, qrL + side, qrT + side), null)
        canvas.restore()
    }

    /** Generates a QR bitmap using the custom-qr-generator library (vector API). */
    fun generateQrBitmap(
        content: String,
        sizePx: Int,
        el: TemplateElement.QrElement
    ): Bitmap? {
        return runCatching {
            val data = QrData.Text(content)
            val fg = parseColor(el.qrColor)
            val bg = parseColor(el.backgroundColor)

            fun pixelShape(code: String): QrVectorPixelShape = when (code) {
                "round"  -> QrVectorPixelShape.RoundCorners(radius = .5f)
                "circle" -> QrVectorPixelShape.Circle(1f)
                else     -> QrVectorPixelShape.Default
            }
            fun ballShape(code: String): QrVectorBallShape = when (code) {
                "round"  -> QrVectorBallShape.RoundCorners(radius = .25f)
                "circle" -> QrVectorBallShape.Circle(1f)
                else     -> QrVectorBallShape.Default
            }
            fun frameShape(code: String): QrVectorFrameShape = when (code) {
                "round"  -> QrVectorFrameShape.RoundCorners(corner = .25f)
                "circle" -> QrVectorFrameShape.Circle(1f, 1f)
                else     -> QrVectorFrameShape.Default
            }

            // Attach logo if provided (Android BitmapDrawable, not library class)
            val reqLogoDim = (sizePx * 0.5f).toInt().coerceIn(256, 1024)
            val logoBitmapRaw = el.logoPath?.let { loadBitmap(it, reqLogoDim) }
            val logoObj: QrVectorLogo? = if (logoBitmapRaw != null) {
                // Pad to a square to prevent stretching or cropping from CenterCrop / FitXY
                val size = maxOf(logoBitmapRaw.width, logoBitmapRaw.height)
                val squareBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val paint = if (el.tintLogo) Paint().apply {
                    colorFilter = PorterDuffColorFilter(fg, PorterDuff.Mode.SRC_IN)
                } else null
                
                android.graphics.Canvas(squareBitmap).drawBitmap(
                    logoBitmapRaw, 
                    (size - logoBitmapRaw.width) / 2f, 
                    (size - logoBitmapRaw.height) / 2f, 
                    paint
                )

                val drawable = BitmapDrawable(context.resources, squareBitmap)
                QrVectorLogo(
                    drawable  = drawable,
                    size      = el.logoSizeFraction.coerceIn(0.05f, 1f / 3f),
                    padding   = QrVectorLogoPadding.Accurate(.15f),
                    scale     = BitmapScale.CenterCrop, 
                )
            } else null

            val options = QrVectorOptions.Builder()
                .setPadding(el.qrPadding.coerceIn(0f, 0.25f))
                .setErrorCorrectionLevel(
                    if (logoObj != null) QrErrorCorrectionLevel.High
                    else QrErrorCorrectionLevel.Auto
                )
                .setBackground(QrVectorBackground(
                    color = QrVectorColor.Solid(bg)
                ))
                .setColors(QrVectorColors(
                    dark  = QrVectorColor.Solid(fg),
                    light = QrVectorColor.Unspecified, // uses solid bg color from QrBackground
                ))
                .setShapes(QrVectorShapes(
                    darkPixel = pixelShape(el.pixelShape),
                    ball      = ballShape(el.eyeShape),
                    frame     = frameShape(el.eyeShape),
                ))
                .apply { if (logoObj != null) setLogo(logoObj) }
                .build()

            QrCodeDrawable(data, options).toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    /** Internal convenience wrapper for drawQr(): caches by content+style key. */
    @Suppress("DEPRECATION")
    private fun generateQrBitmapForEl(
        content: String, sizePx: Int, el: TemplateElement.QrElement
    ): Bitmap? {
        // Cache key includes all style fields so any change triggers regeneration
        val key = "qr|$content|$sizePx|${el.qrColor}|${el.backgroundColor}" +
                  "|${el.pixelShape}|${el.eyeShape}|${el.qrPadding}" +
                  "|${el.logoPath}|${el.logoSizeFraction}|${el.tintLogo}"
        return qrCache.getOrPut(key) { generateQrBitmap(content, sizePx, el) }
    }


    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatDate(el: TemplateElement.DateElement): String =
        LocalDate.now().format(DateTimeFormatter.ofPattern(el.format.pattern))

    fun formatDateNow(el: TemplateElement.DateElement) = formatDate(el)

    private fun loadBitmap(path: String, reqMaxDim: Int = 2048): Bitmap? {
        val cacheKey = "$path|$reqMaxDim"
        if (bitmapCache.containsKey(cacheKey)) return bitmapCache[cacheKey]

        fun decodeSampled(options: BitmapFactory.Options, streamProvider: () -> java.io.InputStream?): Bitmap? {
            options.inJustDecodeBounds = true
            streamProvider()?.use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            
            var inSampleSize = 1
            while ((options.outHeight / (inSampleSize * 2)) >= reqMaxDim || (options.outWidth / (inSampleSize * 2)) >= reqMaxDim) {
                inSampleSize *= 2
            }
            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            
            val decoded = streamProvider()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            return decoded
        }

        val isSvg = path.lowercase().endsWith(".svg")
        val bmp = if (isSvg) {
            runCatching {
                val stream = if (path.startsWith("pack:")) {
                    context.assets.open(path.removePrefix("pack:"))
                } else {
                    java.io.FileInputStream(path)
                }
                stream.use {
                    val svg = com.caverock.androidsvg.SVG.getFromInputStream(it)
                    val docW = if (svg.documentWidth > 0f) svg.documentWidth else reqMaxDim.toFloat()
                    val docH = if (svg.documentHeight > 0f) svg.documentHeight else reqMaxDim.toFloat()
                    val aspect = docW / docH
                    // Force the SVG to render at the highest permitted raster quality
                    val finalW = if (docW > docH) reqMaxDim.toFloat() else reqMaxDim * aspect
                    val finalH = finalW / aspect
                    
                    val b = Bitmap.createBitmap(finalW.toInt().coerceAtLeast(1), finalH.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    val c = Canvas(b)
                    svg.documentWidth = finalW
                    svg.documentHeight = finalH
                    svg.renderToCanvas(c)
                    b
                }
            }.getOrNull()
        } else if (path.startsWith("res:")) runCatching {
            val resName = path.removePrefix("res:")
            val resId = context.resources.getIdentifier(resName, "drawable", context.packageName)
            if (resId == 0) return null else {
                val d = androidx.core.content.ContextCompat.getDrawable(context, resId) ?: return null
                val b = Bitmap.createBitmap(reqMaxDim.coerceAtMost(1024), reqMaxDim.coerceAtMost(1024), Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                d.setBounds(0, 0, b.width, b.height)
                d.draw(c)
                b
            }
        }.getOrNull() else if (path.startsWith("pack:")) runCatching {
            decodeSampled(BitmapFactory.Options()) { context.assets.open(path.removePrefix("pack:")) }
        }.getOrNull() else runCatching {
            decodeSampled(BitmapFactory.Options()) { java.io.FileInputStream(path) }
        }.getOrNull()

        if (bmp != null) bitmapCache[cacheKey] = bmp
        return bmp
    }

    private fun loadSvg(path: String): com.caverock.androidsvg.SVG? {
        if (svgCache.containsKey(path)) return svgCache[path]
        val svg = runCatching {
            val stream = if (path.startsWith("pack:")) context.assets.open(path.removePrefix("pack:")) else java.io.FileInputStream(path)
            stream.use { com.caverock.androidsvg.SVG.getFromInputStream(it) }
        }.getOrNull()
        svgCache[path] = svg
        return svg
    }

    fun clearBitmapCache() {
        bitmapCache.values.forEach { it?.recycle() }
        bitmapCache.clear()
        qrCache.values.forEach { it?.recycle() }
        qrCache.clear()
        svgCache.clear()
    }

    private fun parseColor(hex: String) = runCatching { Color.parseColor(hex) }.getOrElse { Color.BLACK }

    private fun resolveTypeface(fontName: String, isBold: Boolean): Typeface {
        val style = if (isBold) Typeface.BOLD else Typeface.NORMAL
        return when (fontName.lowercase()) {
            "serif"      -> Typeface.create(Typeface.SERIF, style)
            "monospace"  -> Typeface.create(Typeface.MONOSPACE, style)
            "sans-serif" -> Typeface.create(Typeface.SANS_SERIF, style)
            else         -> Typeface.create(Typeface.DEFAULT, style)
        }
    }

    private fun isCornerExclusion(cx: Float, cy: Float, l: Float, t: Float, r: Float, b: Float, cr: Float): Boolean {
        listOf(l+cr to t+cr, r-cr to t+cr, l+cr to b-cr, r-cr to b-cr).forEach { (ox, oy) ->
            if (cx in (ox-cr)..(ox+cr) && cy in (oy-cr)..(oy+cr)) {
                val dx = cx - ox; val dy = cy - oy
                if (dx*dx + dy*dy > cr*cr) return true
            }
        }
        return false
    }

    // ── TextProps helpers ─────────────────────────────────────────────────────

    private data class TextProps(val x: Float, val y: Float, val width: Float, val height: Float,
                                 val rotation: Float, val text: String, val textColor: String,
                                 val bgColor: String?, val isBold: Boolean, val fontName: String, val textSizeSp: Float)

    private fun textProps(el: TemplateElement.TextElement, text: String) =
        TextProps(el.x, el.y, el.width, el.height, el.rotation, text, el.textColor, el.bgColor, el.isBold, el.fontName, el.textSizeSp)
    private fun textProps(el: TemplateElement.UsernameElement, text: String) =
        TextProps(el.x, el.y, el.width, el.height, el.rotation, text, el.textColor, el.bgColor, el.isBold, el.fontName, el.textSizeSp)
    private fun textProps(el: TemplateElement.PasswordElement, text: String) =
        TextProps(el.x, el.y, el.width, el.height, el.rotation, text, el.textColor, el.bgColor, el.isBold, el.fontName, el.textSizeSp)
    private fun textProps(el: TemplateElement.DateElement, text: String) =
        TextProps(el.x, el.y, el.width, el.height, el.rotation, text, el.textColor, el.bgColor, el.isBold, el.fontName, el.textSizeSp)
}

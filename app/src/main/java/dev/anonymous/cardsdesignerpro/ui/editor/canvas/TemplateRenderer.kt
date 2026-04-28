package dev.anonymous.cardsdesignerpro.ui.editor.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
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
import dev.anonymous.cardsdesignerpro.data.model.Template
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.data.model.TextAlign
import java.time.LocalDate
import java.time.format.DateTimeFormatter


/**
 * Pure-stateless renderer shared between [CardCanvasView] (screen) and [dev.anonymous.cardsdesignerpro.util.PdfExporter] (PDF).
 *
 * @param dev.anonymous.cardsdesignerpro.data.model.ExportQuality.renderScale  Multiplier for raster-content (QR bitmaps). Use 1f for screen,
 *                     ~4f for 300-dpi PDF output so bitmaps look sharp when printed.
 */
class TemplateRenderer(private val context: Context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapCache = mutableMapOf<String, Bitmap?>()

    /** Separate cache for expensive QR bitmaps: key = content+style hash. */
    private val qrCache = mutableMapOf<String, Bitmap?>()
    private val svgCache = mutableMapOf<String, com.caverock.androidsvg.SVG?>()

    /** Cache for resolved Typefaces to avoid repeated resource lookups. */
    private val typefaceCache = mutableMapOf<String, Typeface>()

    /**
     * Directory containing custom font files (.ttf/.otf) for the current template.
     * Must be set before [draw] when the template uses custom fonts.
     */
    var fontsDir: java.io.File? = null


    /**
     * Maximum bitmap dimensions for each content type.
     * Set before [draw] when exporting PDF to control quality per content type.
     * Screen rendering uses the defaults; PDF export overrides via [dev.anonymous.cardsdesignerpro.data.model.ExportQuality].
     */
    var maxImageDim: Int = 2048
    var maxQrDim: Int = 1024

    /** Padding around text background (dp in template coordinate space). Must match CardCanvasView.TEXT_PAD_DP. */
    private val TEXT_PAD_DP = 6f

    // ── Reusable objects to reduce GC pressure during draw ────────────────────
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val tempRect = RectF()

    fun draw(
        canvas: Canvas,
        template: Template,
        cardLeft: Float,
        cardTop: Float,
        cardWidthPx: Float,
        cardHeightPx: Float,
        username: String? = null,
        password: String? = null,
        shortUsername: String? = null,
        shortPassword: String? = null,
        date: String? = null,
        renderScale: Float = 1f
    ) {
        val scaleX = cardWidthPx / template.card.widthDp
        val scaleY = cardHeightPx / (template.card.widthDp * template.card.heightRatio)

        canvas.withClip(cardLeft, cardTop, cardLeft + cardWidthPx, cardTop + cardHeightPx) {
            template.elements.asReversed().filter { it.isVisible }.forEach { el ->
                when (el) {
                    is TemplateElement.CardBackground ->
                        drawCardBackground(
                            this,
                            template,
                            cardLeft,
                            cardTop,
                            cardWidthPx,
                            cardHeightPx,
                            renderScale
                        )

                    is TemplateElement.FrameElement ->
                        drawFrame(this, el, cardLeft, cardTop, scaleX, scaleY)

                    is TemplateElement.TextElement ->
                        drawTextProps(
                            this,
                            textProps(el, el.text),
                            cardLeft,
                            cardTop,
                            scaleX,
                            scaleY
                        )

                    is TemplateElement.UsernameElement -> {
                        val u = if (el.isShortVariant) shortUsername
                            ?: dummyDigits(el.digitCount.coerceAtLeast(1)) else username
                            ?: dummyDigits(el.digitCount.coerceAtLeast(1))
                        drawTextProps(this, textProps(el, u), cardLeft, cardTop, scaleX, scaleY)
                    }

                    is TemplateElement.PasswordElement -> {
                        val p = if (el.isShortVariant) shortPassword
                            ?: dummyDigits(el.digitCount.coerceAtLeast(1)) else password
                            ?: dummyDigits(el.digitCount.coerceAtLeast(1))
                        drawTextProps(this, textProps(el, p), cardLeft, cardTop, scaleX, scaleY)
                    }

                    is TemplateElement.DateElement ->
                        drawTextProps(
                            this,
                            textProps(el, date ?: formatDate(el)),
                            cardLeft,
                            cardTop,
                            scaleX,
                            scaleY
                        )

                    is TemplateElement.ImageElement ->
                        drawImage(this, el, cardLeft, cardTop, scaleX, scaleY, renderScale)

                    is TemplateElement.QrElement -> {
                        val qrUser = if (el.linkToShortNumbers) shortUsername else username
                        val qrPass = if (el.linkToShortNumbers) shortPassword else password
                        drawQr(
                            this,
                            el,
                            qrUser,
                            qrPass,
                            cardLeft,
                            cardTop,
                            scaleX,
                            scaleY,
                            renderScale
                        )
                    }

                    is TemplateElement.ShapeElement ->
                        drawShape(this, el, cardLeft, cardTop, scaleX, scaleY)
                }
            }
        }
    }

    // ── Background ────────────────────────────────────────────────────────────

    private fun drawCardBackground(
        canvas: Canvas,
        template: Template,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
        renderScale: Float
    ) {
        val card = template.card
        paint.reset(); paint.color = parseColor(card.backgroundColor); paint.style =
            Paint.Style.FILL
        canvas.drawRect(left, top, left + w, top + h, paint)
        card.backgroundImagePath?.let { path ->
            val isCenterCrop = card.backgroundImageScaleType == "CENTER_CROP"
            if (path.lowercase().endsWith(".svg")) {
                loadSvg(path)?.let { svg ->
                    val aspectW = if (svg.documentWidth > 0f) svg.documentWidth else w
                    val aspectH = if (svg.documentHeight > 0f) svg.documentHeight else h

                    canvas.withSave {
                        if (isCenterCrop) {
                            val scale = maxOf(w / aspectW, h / aspectH)
                            val drawW = aspectW * scale
                            val drawH = aspectH * scale
                            val dx = left + (w - drawW) / 2f
                            val dy = top + (h - drawH) / 2f
                            translate(dx, dy)
                            scale(scale, scale)
                        } else {
                            translate(left, top)
                            scale(w / aspectW, h / aspectH)
                        }
                        svg.renderToCanvas(this)
                    }
                }
            } else {
                val reqDim = (maxOf(w, h) * renderScale * 2f).toInt().coerceIn(64, maxImageDim)
                loadBitmap(path, reqDim)?.let { bmp ->
                    if (isCenterCrop) {
                        val scale = maxOf(w / bmp.width.toFloat(), h / bmp.height.toFloat())
                        val drawW = bmp.width * scale
                        val drawH = bmp.height * scale
                        val dx = left + (w - drawW) / 2f
                        val dy = top + (h - drawH) / 2f

                        val matrix = android.graphics.Matrix().apply {
                            postScale(scale, scale)
                            postTranslate(dx, dy)
                        }
                        canvas.drawBitmap(bmp, matrix, null)
                    } else {
                        // FIT_XY: stretch to fill the card completely
                        canvas.drawBitmap(bmp, null, RectF(left, top, left + w, top + h), null)
                    }
                }
            }
        }
    }

    // ── Frame ─────────────────────────────────────────────────────────────────

    private fun drawFrame(
        canvas: Canvas,
        el: TemplateElement.FrameElement,
        left: Float,
        top: Float,
        sX: Float,
        sY: Float
    ) {
        // Apply paddingDp as an inset so the frame border moves inward from card edges
        val pad = el.paddingDp * sX
        val l = left + el.x * sX + pad
        val t = top + el.y * sY + pad
        val r = left + (el.x + el.width) * sX - pad
        val b = top + (el.y + el.height) * sY - pad
        paint.reset(); paint.color = parseColor(el.color); paint.style = Paint.Style.STROKE
        paint.strokeWidth = el.strokeWidthDp * sX; paint.isAntiAlias = true
        if (el.isDashed) {
            paint.pathEffect =
                DashPathEffect(floatArrayOf(el.dashLengthDp * sX, el.dashGapDp * sX), 0f)
            if (el.isDashRounded) paint.strokeCap = Paint.Cap.ROUND
        }
        val cr = el.cornerRadiusDp * sX
        canvas.drawRoundRect(RectF(l, t, r, b), cr, cr, paint)
    }

    // ── Shape ─────────────────────────────────────────────────────────────────

    private fun drawShape(
        canvas: Canvas,
        el: TemplateElement.ShapeElement,
        left: Float,
        top: Float,
        sX: Float,
        sY: Float
    ) {
        val l = left + el.x * sX
        val t = top + el.y * sY
        val r = l + el.width * sX
        val b = t + el.height * sY
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        val cr = el.cornerRadiusDp * sX
        val rect = RectF(l, t, r, b)

        canvas.withRotation(el.rotation, cx, cy) {
            // Fill
            if (el.fillColor.isNotEmpty()) {
                paint.reset(); paint.isAntiAlias = true
                paint.style = Paint.Style.FILL
                paint.color = parseColor(el.fillColor)
                drawRoundRect(rect, cr, cr, paint)
            }

            // Stroke (solid or dashed)
            if (el.strokeWidthDp > 0f) {
                paint.reset(); paint.isAntiAlias = true
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = el.strokeWidthDp * sX
                paint.color = parseColor(el.strokeColor)
                if (el.isDashed) {
                    paint.pathEffect = DashPathEffect(
                        floatArrayOf(el.dashLengthDp * sX, el.dashGapDp * sX), 0f
                    )
                    if (el.isDashRounded) paint.strokeCap = Paint.Cap.ROUND
                }
                drawRoundRect(rect, cr, cr, paint)
            }

        }
    }

    // ── Text (StaticLayout — handles Arabic ligatures & BiDi correctly) ───────

    private fun dummyDigits(count: Int): String {
        return (1..count).joinToString("") { (it % 10).toString() }
    }

    private fun drawTextProps(
        canvas: Canvas,
        p: TextProps,
        left: Float,
        top: Float,
        sX: Float,
        sY: Float
    ) {
        val l = left + p.x * sX
        val t = top + p.y * sY
        val r = l + p.width * sX
        val b = t + p.height * sY
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        val elW = r - l

        // Reuse the shared TextPaint instead of allocating a new one each draw
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.color = parseColor(p.textColor)
        textPaint.textSize = p.textSizeSp * sX
        textPaint.typeface = resolveTypeface(p.fontName, p.isBold)
        textPaint.letterSpacing = 0f
        textPaint.isLinearText = true   // disables size-hinting
        textPaint.isSubpixelText = true // forces purely mathematical string bounds mapping

        // Map TextAlign → StaticLayout.Alignment
        val layoutAlign = when (p.textAlign) {
            TextAlign.START -> Layout.Alignment.ALIGN_NORMAL
            TextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
            TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
        }

        val padPx = TEXT_PAD_DP * sX     // matches CardCanvasView selection box padding

        // If p.wrap is false (Username, Password, Date), use a huge boxW to prevent wrapping.
        // If p.wrap is true (TextElement), subtract padPx and add a dynamic subpixel slop
        // equal to half a space character. This gives tiny scaled elements enough mathematical
        // breathing room so Android's nonlinear text engine does not erroneously push words down.
        val slopPx = textPaint.measureText(" ") * 0.5f
        val boxW = if (p.wrap) kotlin.math.ceil(elW - padPx * 2 + slopPx).toInt().coerceAtLeast(1) else 8192

        // For non-wrapping text (Credentials), we handle alignment manually via 'tx'.
        // We MUST use ALIGN_NORMAL here so the layout starts at 0; otherwise, ALIGN_CENTER
        // within the huge 8192 box would push the text off-screen.
        val finalAlign = if (p.wrap) layoutAlign else Layout.Alignment.ALIGN_NORMAL

        val layout = StaticLayout.Builder
            .obtain(p.text, 0, p.text.length, textPaint, boxW)
            .setAlignment(finalAlign)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()

        // Measure the actual ink-bounds of all lines
        var minL = Float.MAX_VALUE
        var maxR = Float.MIN_VALUE
        for (i in 0 until layout.lineCount) {
            minL = minOf(minL, layout.getLineLeft(i))
            maxR = maxOf(maxR, layout.getLineRight(i))
        }
        val tightW = if (maxR > minL) maxR - minL else 0f
        val actualTextH = layout.height.toFloat()

        // Translation logic:
        // For wrapping text (TextElement), anchor at 'l + padPx' so text is padded away from the element edge.
        // For non-wrapping (Credentials), anchor such that the measured ink-bounds are centered/aligned relative to element 'cx'.
        val tx = if (p.wrap) {
            l + padPx
        } else {
            val targetL = when (p.textAlign) {
                TextAlign.START -> l
                TextAlign.END -> r - tightW
                TextAlign.CENTER -> cx - tightW / 2f
            }
            targetL - minL // compensate for any internal layout offset (like RTL gap)
        }

        canvas.withRotation(p.rotation, cx, cy) {
            // Background rect
            p.bgColor?.let { bg ->
                paint.reset(); paint.color = parseColor(bg); paint.style = Paint.Style.FILL
                if (p.wrap) {
                    // Wrapping text uses the full element bounds
                    tempRect.set(l, t, r, b)
                } else {
                    // Non-wrapping text shrink-wraps to ink
                    tempRect.set(
                        tx + minL - padPx, cy - actualTextH / 2 - padPx,
                        tx + minL + tightW + padPx, cy + actualTextH / 2 + padPx
                    )
                }
                drawRoundRect(tempRect, padPx / 2, padPx / 2, paint)
            }

            translate(tx, cy - actualTextH / 2f)

            // Draw Stroke (if any)
            if (p.textStrokeWidth > 0f) {
                textPaint.style = Paint.Style.STROKE
                textPaint.strokeWidth = p.textStrokeWidth * sX
                textPaint.color = parseColor(p.textStrokeColor)
                layout.draw(this)
            }

            // Draw Fill
            textPaint.style = Paint.Style.FILL
            textPaint.color = parseColor(p.textColor)
            layout.draw(this)
        }
    }

    // ── Image ─────────────────────────────────────────────────────────────────

    private fun drawImage(
        canvas: Canvas,
        el: TemplateElement.ImageElement,
        left: Float,
        top: Float,
        sX: Float,
        sY: Float,
        renderScale: Float
    ) {
        val path = el.imagePath
        val w = el.width * sX
        val h = el.height * sY
        val l = left + el.x * sX
        val t = top + el.y * sY
        val r = l + w
        val b = t + h

        paint.reset(); paint.isAntiAlias = true
        el.tintColor?.let {
            paint.colorFilter = PorterDuffColorFilter(parseColor(it), PorterDuff.Mode.SRC_IN)
        }

        canvas.withRotation(el.rotation, (l + r) / 2, (t + b) / 2) {
            if (path.lowercase().endsWith(".svg")) {
                val svg = loadSvg(path)
                if (svg != null) {
                    withTranslation(l, t) {
                        if (el.tintColor != null) {
                            // Keep tinted SVG sharp in PDF by rasterizing at export scale, not element px size.
                            val bmpW = (w * renderScale * 4f).toInt().coerceIn(1, maxImageDim)
                            val bmpH = (h * renderScale * 4f).toInt().coerceIn(1, maxImageDim)
                            val tmp = loadSvgBitmap(path, bmpW, bmpH)
                            if (tmp != null) {
                                translate(-l, -t)
                                drawBitmap(tmp, null, RectF(l, t, r, b), paint)
                            }
                        } else {
                            svg.documentWidth = w
                            svg.documentHeight = h
                            svg.renderToCanvas(this)
                        }
                    }
                }
            } else {
                val reqDim = (maxOf(w, h) * renderScale * 2f).toInt().coerceIn(64, maxImageDim)
                val bmp = loadBitmap(path, reqDim)
                if (bmp != null) {
                    drawBitmap(bmp, null, RectF(l, t, r, b), paint)
                }
            }

        }; paint.colorFilter = null
    }

    // ── QR ────────────────────────────────────────────────────────────────────

    private fun drawQr(
        canvas: Canvas, el: TemplateElement.QrElement, username: String?, password: String?,
        left: Float, top: Float, sX: Float, sY: Float, renderScale: Float
    ) {
        val content =
            "http://${el.host}/login?username=${username ?: "username"}&password=${password ?: "password"}"
        val l = left + el.x * sX
        val t = top + el.y * sY
        val r = l + el.width * sX
        val b = t + el.height * sY

        // Always square — centered within element bounds
        val side = minOf(r - l, b - t)
        val qrL = l + (r - l - side) / 2f
        val qrT = t + (b - t - side) / 2f

        // Scan clarity doesn't require massive bitmaps; restrict it to 1024 to prevent memory exhaustion
        val bitmapSize =
            (side * renderScale * 4f).toInt().coerceIn(128, maxQrDim.coerceAtMost(1024))
        val qrBmp = generateQrBitmapForEl(content, bitmapSize, el) ?: return

        canvas.withRotation(el.rotation, (l + r) / 2, (t + b) / 2) {
            drawBitmap(qrBmp, null, RectF(qrL, qrT, qrL + side, qrT + side), null)
        }
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
                "round" -> QrVectorPixelShape.RoundCorners(radius = .5f)
                "circle" -> QrVectorPixelShape.Circle(1f)
                else -> QrVectorPixelShape.Default
            }

            fun ballShape(code: String): QrVectorBallShape = when (code) {
                "round" -> QrVectorBallShape.RoundCorners(radius = .25f)
                "circle" -> QrVectorBallShape.Circle(1f)
                else -> QrVectorBallShape.Default
            }

            fun frameShape(code: String): QrVectorFrameShape = when (code) {
                "round" -> QrVectorFrameShape.RoundCorners(corner = .25f)
                "circle" -> QrVectorFrameShape.Circle(1f, 1f)
                else -> QrVectorFrameShape.Default
            }

            // Attach logo if provided (Android BitmapDrawable, not library class)
            val reqLogoDim = (sizePx * 0.5f).toInt().coerceIn(256, 1024)
            val logoBitmapRaw = el.logoPath?.let { loadBitmap(it, reqLogoDim) }
            val logoObj: QrVectorLogo? = if (logoBitmapRaw != null) {
                // Pad to a square to prevent stretching or cropping from CenterCrop / FitXY
                val size = maxOf(logoBitmapRaw.width, logoBitmapRaw.height)
                val squareBitmap = createBitmap(size, size)
                val paint = if (el.tintLogo) Paint().apply {
                    colorFilter = PorterDuffColorFilter(fg, PorterDuff.Mode.SRC_IN)
                } else null

                Canvas(squareBitmap).drawBitmap(
                    logoBitmapRaw,
                    (size - logoBitmapRaw.width) / 2f,
                    (size - logoBitmapRaw.height) / 2f,
                    paint
                )

                val drawable = squareBitmap.toDrawable(context.resources)
                QrVectorLogo(
                    drawable = drawable,
                    size = el.logoSizeFraction.coerceIn(0.05f, 1f / 3f),
                    padding = QrVectorLogoPadding.Accurate(.15f),
                    scale = BitmapScale.CenterCrop,
                )
            } else null

            val options = QrVectorOptions.Builder()
                .setPadding(el.qrPadding.coerceIn(0f, 0.25f))
                .setErrorCorrectionLevel(
                    if (logoObj != null) QrErrorCorrectionLevel.High
                    else QrErrorCorrectionLevel.Auto
                )
                .setBackground(
                    QrVectorBackground(
                        color = QrVectorColor.Solid(bg)
                    )
                )
                .setColors(
                    QrVectorColors(
                        dark = QrVectorColor.Solid(fg),
                        light = QrVectorColor.Unspecified, // uses solid bg color from QrBackground
                    )
                )
                .setShapes(
                    QrVectorShapes(
                        darkPixel = pixelShape(el.pixelShape),
                        ball = ballShape(el.eyeShape),
                        frame = frameShape(el.eyeShape),
                    )
                )
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

        fun decodeSampled(
            options: BitmapFactory.Options,
            streamProvider: () -> java.io.InputStream?
        ): Bitmap? {
            options.inJustDecodeBounds = true
            streamProvider()?.use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            var inSampleSize = 1
            while ((options.outHeight / (inSampleSize * 2)) >= reqMaxDim || (options.outWidth / (inSampleSize * 2)) >= reqMaxDim) {
                inSampleSize *= 2
            }
            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize

            val decoded = streamProvider()?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return null
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
                    val docW =
                        if (svg.documentWidth > 0f) svg.documentWidth else reqMaxDim.toFloat()
                    val docH =
                        if (svg.documentHeight > 0f) svg.documentHeight else reqMaxDim.toFloat()
                    val aspect = docW / docH
                    // Force the SVG to render at the highest permitted raster quality
                    val finalW = if (docW > docH) reqMaxDim.toFloat() else reqMaxDim * aspect
                    val finalH = finalW / aspect

                    val b = createBitmap(
                        finalW.toInt().coerceAtLeast(1),
                        finalH.toInt().coerceAtLeast(1)
                    )
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
                val d =
                    androidx.core.content.ContextCompat.getDrawable(context, resId) ?: return null
                val b = createBitmap(reqMaxDim.coerceAtMost(1024), reqMaxDim.coerceAtMost(1024))
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
            val stream =
                if (path.startsWith("pack:")) context.assets.open(path.removePrefix("pack:")) else java.io.FileInputStream(
                    path
                )
            stream.use { com.caverock.androidsvg.SVG.getFromInputStream(it) }
        }.getOrNull()
        svgCache[path] = svg
        return svg
    }

    private fun loadSvgBitmap(path: String, reqW: Int, reqH: Int): Bitmap? {
        val safeW = reqW.coerceIn(1, maxImageDim)
        val safeH = reqH.coerceIn(1, maxImageDim)
        val cacheKey = "svg_exact|$path|$safeW|$safeH"
        if (bitmapCache.containsKey(cacheKey)) return bitmapCache[cacheKey]

        val bmp = runCatching {
            val svg = loadSvg(path) ?: return null
            val oldW = svg.documentWidth
            val oldH = svg.documentHeight

            val out = createBitmap(safeW, safeH)
            val c = Canvas(out)
            svg.documentWidth = safeW.toFloat()
            svg.documentHeight = safeH.toFloat()
            svg.renderToCanvas(c)

            // Restore previous size to avoid affecting subsequent renders with different dimensions.
            svg.documentWidth = oldW
            svg.documentHeight = oldH
            out
        }.getOrNull()

        if (bmp != null) bitmapCache[cacheKey] = bmp
        return bmp
    }

    fun clearBitmapCache() {
        bitmapCache.values.forEach { it?.recycle() }
        bitmapCache.clear()
        qrCache.values.forEach { it?.recycle() }
        qrCache.clear()
        svgCache.clear()
        // Note: typefaceCache is NOT cleared — typefaces are lightweight and reusable across renders
    }

    private fun parseColor(hex: String) = runCatching { hex.toColorInt() }.getOrElse { Color.BLACK }

    private fun resolveTypeface(fontName: String, isBold: Boolean): Typeface {
        val key = "$fontName|$isBold"
        return typefaceCache.getOrPut(key) {
            val style = if (isBold) Typeface.BOLD else Typeface.NORMAL
            val baseTypeface = when {
                fontName.startsWith("custom:") -> {
                    val fileName = fontName.removePrefix("custom:")
                    val file = fontsDir?.let { java.io.File(it, fileName) }
                    if (file != null && file.exists()) {
                        try { Typeface.createFromFile(file) } catch (_: Exception) { Typeface.DEFAULT }
                    } else Typeface.DEFAULT
                }
                fontName.lowercase().let { it == "default" || it.isEmpty() } -> Typeface.DEFAULT
                fontName.lowercase() == "serif" -> Typeface.SERIF
                fontName.lowercase() == "monospace" -> Typeface.MONOSPACE
                fontName.lowercase() == "sans-serif" -> Typeface.SANS_SERIF
                else -> {
                    try {
                        val resId =
                            context.resources.getIdentifier(fontName, "font", context.packageName)
                        if (resId != 0) androidx.core.content.res.ResourcesCompat.getFont(
                            context,
                            resId
                        ) ?: Typeface.DEFAULT
                        else Typeface.DEFAULT
                    } catch (_: Exception) {
                        Typeface.DEFAULT
                    }
                }
            }
            Typeface.create(baseTypeface, style)
        }
    }


    private data class TextProps(
        val x: Float, val y: Float, val width: Float, val height: Float,
        val rotation: Float, val text: String, val textColor: String,
        val bgColor: String?, val isBold: Boolean, val fontName: String, val textSizeSp: Float,
        val textStrokeWidth: Float, val textStrokeColor: String,
        val textAlign: TextAlign = TextAlign.CENTER,
        val wrap: Boolean = true
    )

    private fun textProps(el: TemplateElement.TextElement, text: String) =
        TextProps(
            el.x,
            el.y,
            el.width,
            el.height,
            el.rotation,
            text,
            el.textColor,
            el.bgColor,
            el.isBold,
            el.fontName,
            el.textSizeSp,
            el.textStrokeWidth,
            el.textStrokeColor,
            el.textAlign,
            wrap = true
        )

    private fun textProps(el: TemplateElement.UsernameElement, text: String) =
        TextProps(
            el.x,
            el.y,
            el.width,
            el.height,
            el.rotation,
            text,
            el.textColor,
            el.bgColor,
            el.isBold,
            el.fontName,
            el.textSizeSp,
            el.textStrokeWidth,
            el.textStrokeColor,
            wrap = false
        )

    private fun textProps(el: TemplateElement.PasswordElement, text: String) =
        TextProps(
            el.x,
            el.y,
            el.width,
            el.height,
            el.rotation,
            text,
            el.textColor,
            el.bgColor,
            el.isBold,
            el.fontName,
            el.textSizeSp,
            el.textStrokeWidth,
            el.textStrokeColor,
            wrap = false
        )

    private fun textProps(el: TemplateElement.DateElement, text: String) =
        TextProps(
            el.x,
            el.y,
            el.width,
            el.height,
            el.rotation,
            text,
            el.textColor,
            el.bgColor,
            el.isBold,
            el.fontName,
            el.textSizeSp,
            el.textStrokeWidth,
            el.textStrokeColor,
            wrap = false
        )
}

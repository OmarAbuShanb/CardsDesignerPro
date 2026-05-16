package dev.anonymous.cardsdesignerpro.app.ui.export.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import dev.anonymous.cardsdesignerpro.app.data.model.FlipEdge
import dev.anonymous.cardsdesignerpro.app.data.model.Template
import dev.anonymous.cardsdesignerpro.app.ui.editor.canvas.TemplateRenderer
import dev.anonymous.cardsdesignerpro.app.util.PdfLayoutCalculator
import java.util.concurrent.Executors
import java.util.concurrent.Future
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave

/**
 * Renders a scaled-down preview of one PDF page.
 *
 * Performance strategy:
 *  - Renders to an off-screen bitmap on a background thread.
 *  - Debounces rapid changes (e.g. slider drag) — waits 120ms after the last
 *    change before starting a new render.
 *  - Keeps the previous bitmap visible while the new one renders (no white flash).
 *  - Cancels queued-but-not-started render tasks to avoid backlog.
 */
class PagePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var template: Template? = null
    private var layout: PdfLayoutCalculator.LayoutInfo? = null
    private var isMirrored = false
    private var flipEdge = FlipEdge.LONG_EDGE
    private var showPageNumberPreview = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000; style = Paint.Style.FILL
    }
    private val renderer = TemplateRenderer(context)
    private val pageNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.RIGHT
    }

    // ── Bitmap cache ─────────────────────────────────────────────────────────
    private var cachedBitmap: Bitmap? = null
    /** Previous bitmap kept visible while the new one renders, to avoid white flash. */
    private var staleBitmap: Bitmap? = null
    /** Key that identifies the current render state — if it changes, re-render. */
    private var cacheKey: String = ""

    // ── Debounce + cancellation ──────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private var pendingFuture: Future<*>? = null
    private val renderRunnable = Runnable { startBackgroundRender() }
    private companion object {
        /** Single background thread shared by all preview instances. */
        private val RENDER_EXECUTOR = Executors.newSingleThreadExecutor()
        /** Debounce delay — waits this long after the last bind() before rendering. */
        private const val DEBOUNCE_MS = 40L
    }

    /** Update renderer quality to match the user's selected export quality. */
    fun setQuality(q: dev.anonymous.cardsdesignerpro.app.data.model.ExportQuality) {
        renderer.maxImageDim = q.maxImageDim
        renderer.maxQrDim    = q.maxQrDim
    }

    fun bind(
        template: Template?,
        layout: PdfLayoutCalculator.LayoutInfo?,
        isMirrored: Boolean = false,
        flipEdge: FlipEdge = FlipEdge.LONG_EDGE,
        showPageNumberPreview: Boolean = false
    ) {
        this.template   = template
        this.layout     = layout
        this.isMirrored = isMirrored
        this.flipEdge   = flipEdge
        this.showPageNumberPreview = showPageNumberPreview
        // Set fonts dir for custom font rendering
        if (template != null) {
            renderer.fontsDir = java.io.File(context.filesDir, "templates/${template.id}/fonts")
        }

        val newKey = buildCacheKey(template, layout, isMirrored, flipEdge, showPageNumberPreview)
        if (newKey != cacheKey) {
            cacheKey = newKey
            if (cachedBitmap != null) {
                // We have a completed render — it becomes the new stale
                staleBitmap?.recycle()
                staleBitmap = cachedBitmap
                cachedBitmap = null
            }

            // Cancel any pending/queued render and debounce the new one
            handler.removeCallbacks(renderRunnable)
            pendingFuture?.cancel(false)
            pendingFuture = null
            handler.postDelayed(renderRunnable, DEBOUNCE_MS)
        }

        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val l = layout
        // Default to A4 portrait ratio (297/210 ≈ 1.414) if no layout is selected
        val h = if (l != null) (w * l.pageHeightPt / l.pageWidthPt).toInt() else (w * 1.414f).toInt()
        setMeasuredDimension(w, h.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewW = width.toFloat()
        val viewH = height.toFloat()

        // Background + shadow
        canvas.drawRoundRect(RectF(6f, 6f, viewW + 6f, viewH + 6f), 4f, 4f, shadowPaint)
        canvas.drawRect(0f, 0f, viewW, viewH, bgPaint)

        template ?: return
        layout ?: return

        // Draw the best available bitmap: cached (latest) or stale (previous)
        val bmp = cachedBitmap ?: staleBitmap
        if (bmp != null && !bmp.isRecycled) {
            canvas.drawBitmap(bmp, null, RectF(0f, 0f, viewW, viewH), null)
        }
    }

    /** Called after debounce delay — kicks off the actual render on background thread. */
    private fun startBackgroundRender() {
        val t = template ?: return
        val l = layout ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0 || viewH <= 0) return

        val tCopy = t
        val lCopy = l
        val mirroredCopy = isMirrored
        val flipCopy = flipEdge
        val showPageNumberCopy = showPageNumberPreview
        val currentKey = cacheKey

        // Cap preview resolution
        val maxDim = 1200f
        val scale = if (viewW > maxDim) maxDim / viewW else 1f
        val bmpW = (viewW * scale).toInt().coerceAtLeast(1)
        val bmpH = (viewH * scale).toInt().coerceAtLeast(1)

        val previewRenderer = TemplateRenderer(context).apply {
            val q = dev.anonymous.cardsdesignerpro.app.data.model.ExportQuality.LOW
            maxImageDim = q.maxImageDim
            maxQrDim    = q.maxQrDim
            fontsDir = java.io.File(context.filesDir, "templates/${tCopy.id}/fonts")
        }

        pendingFuture = RENDER_EXECUTOR.submit {
            // Early exit if key already changed (a newer request is pending)
            if (currentKey != cacheKey) return@submit
            try {
                val bmp = createBitmap(bmpW, bmpH)
                val offCanvas = Canvas(bmp)
                val renderScale = bmpW.toFloat() / lCopy.pageWidthPt

                val s = lCopy.settings
                for (row in 0 until lCopy.rows) {
                    for (col in 0 until lCopy.columns) {
                        // Early exit mid-render if key changed
                        if (currentKey != cacheKey) { bmp.recycle(); previewRenderer.clearBitmapCache(); return@submit }

                        val (drawCol, drawRow) = if (mirroredCopy) {
                            when (flipCopy) {
                                FlipEdge.LONG_EDGE  -> (lCopy.columns - 1 - col) to row
                                FlipEdge.SHORT_EDGE -> col to (lCopy.rows - 1 - row)
                            }
                        } else col to row

                        val cardLeft = (lCopy.marginLeftPt  + drawCol * (lCopy.cardWidthPt  + s.horizontalSpacingDp)) * renderScale
                        val cardTop  = (lCopy.marginTopPt   + drawRow * (lCopy.cardHeightPt + s.verticalSpacingDp))  * renderScale
                        val cardW    = lCopy.cardWidthPt  * renderScale
                        val cardH    = lCopy.cardHeightPt * renderScale

                        offCanvas.withSave {
                            if (mirroredCopy && flipCopy == FlipEdge.SHORT_EDGE) {
                                rotate(180f, cardLeft + cardW / 2f, cardTop + cardH / 2f)
                            }
                            previewRenderer.draw(this, tCopy, cardLeft, cardTop, cardW, cardH)
                        }
                    }
                }

                if (showPageNumberCopy) {
                    drawPageNumberOverlay(
                        canvas = offCanvas,
                        pageWidth = bmpW.toFloat(),
                        pageHeight = bmpH.toFloat(),
                        pageNumberText = "1",
                        mirrored = mirroredCopy,
                        flipEdge = flipCopy
                    )
                }
                previewRenderer.clearBitmapCache()

                post {
                    if (currentKey == cacheKey) {
                        cachedBitmap?.recycle()
                        cachedBitmap = bmp
                        staleBitmap?.recycle()
                        staleBitmap = null
                        invalidate()
                    } else {
                        bmp.recycle()
                    }
                }
            } catch (_: Exception) { /* ignore */ }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(renderRunnable)
        pendingFuture?.cancel(false)
        cachedBitmap?.recycle()
        cachedBitmap = null
        staleBitmap?.recycle()
        staleBitmap = null
        renderer.clearBitmapCache()
    }

    private fun drawPageNumberOverlay(
        canvas: Canvas,
        pageWidth: Float,
        pageHeight: Float,
        pageNumberText: String,
        mirrored: Boolean,
        flipEdge: FlipEdge
    ) {
        val minSide = pageWidth.coerceAtMost(pageHeight)
        val margin = (minSide * 0.015f).coerceAtLeast(6f)
        pageNumberPaint.textSize = (minSide * 0.020f).coerceIn(9f, 22f)

        val rightX = pageWidth - margin
        val bottomY = pageHeight - (pageNumberPaint.descent() + margin)

        if (!mirrored) {
            // Front page — bottom-right
            canvas.drawText(pageNumberText, rightX, bottomY, pageNumberPaint)
        } else if (flipEdge == FlipEdge.LONG_EDGE) {
            // Back, long-edge flip — bottom-right (same corner as front)
            canvas.drawText(pageNumberText, rightX, bottomY, pageNumberPaint)
        } else {
            // Back, short-edge flip — top-left, rotated 180°
            canvas.withSave {
                rotate(180f, pageWidth / 2f, pageHeight / 2f)
                canvas.drawText(pageNumberText, rightX, bottomY, pageNumberPaint)
            }
        }
    }

    private fun buildCacheKey(
        t: Template?,
        l: PdfLayoutCalculator.LayoutInfo?,
        mirrored: Boolean,
        flip: FlipEdge,
        showPageNumber: Boolean
    ): String {
        if (t == null || l == null) return ""
        return "${t.id}|${t.card.hashCode()}|${t.elements.hashCode()}" +
               "|${l.columns}|${l.rows}|${l.cardWidthPt}|${l.cardHeightPt}" +
               "|${l.marginLeftPt}|${l.marginTopPt}" +
               "|${l.settings.horizontalSpacingDp}|${l.settings.verticalSpacingDp}" +
               "|$mirrored|$flip" +
               "|$showPageNumber" +
               "|${renderer.maxImageDim}|${renderer.maxQrDim}"
    }
}

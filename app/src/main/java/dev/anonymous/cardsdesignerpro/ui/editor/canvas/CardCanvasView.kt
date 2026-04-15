package dev.anonymous.cardsdesignerpro.ui.editor.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.data.model.CardSide
import dev.anonymous.cardsdesignerpro.data.model.Template
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Interactive canvas for the Template Editor.
 *
 * Handle layout (when an element is selected):
 *  - top-left      → delete  (ic_handle_delete) — offset away from corner
 *  - top-right     → rotate  (ic_handle_rotate) — offset away from corner
 *  - bottom-right  → resize  (ic_handle_resize) — offset away from corner
 *
 * Height handle (only when card_background is selected):
 *  - centered at the bottom edge of the card (half inside / half outside — NOT clipped)
 *  - ic_handle_height (↑↓ arrows)
 *
 * Touch:
 *  - requestDisallowInterceptTouchEvent(true) prevents NestedScrollView from stealing events
 *  - activePointerId tracks the exact finger for multi-touch safety
 *  - Tap vs drag detected via TAP_SLOP threshold on ACTION_UP (no GestureDetector in MOVE path)
 */
class CardCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onElementSelected(id: String?)
        fun onElementMoved(id: String, dx: Float, dy: Float)
        fun onElementResized(id: String, newW: Float, newH: Float)
        fun onElementRotated(id: String, angleDelta: Float)
        fun onElementDeleteRequested(id: String)
        fun onCardBackgroundSelected()
        fun onCardHeightDrag(deltaRatio: Float)
    }

    var listener: Listener? = null

    private val renderer = TemplateRenderer(context).apply {
        // WYSIWYG: editor shows FULL quality so the design matches the best possible export
        val q = dev.anonymous.cardsdesignerpro.data.model.ExportQuality.FULL
        maxImageDim   = q.maxImageDim
        maxPatternDim = q.maxPatternDim
        maxQrDim      = q.maxQrDim
    }
    private var template: Template? = null
    private var selectedId: String? = null
    private var activeSide: CardSide = CardSide.FRONT

    /** Cached bitmap for non-interactive preview (e.g. default-templates list). */
    private var previewCache: android.graphics.Bitmap? = null
    private var previewRendering = false

    /** The element list for the currently rendered side. */
    private val activeElements: List<TemplateElement>
        get() {
            val t = template ?: return emptyList()
            return if (activeSide == CardSide.BACK && t.isBackSideEnabled)
                t.backElements ?: emptyList()
            else
                t.elements
        }

    // ── Card geometry (view-pixels) ────────────────────────────────────────────
    private var cardWidthPx  = 0f
    private var cardHeightPx = 0f
    private var scaleX = 1f
    private var scaleY = 1f

    private val dp = resources.displayMetrics.density
    private val HR          = 11f * dp    // handle circle radius (smaller)
    private val HANDLE_OFF  = 8f  * dp    // offset distance from element corner
    private val TAP_SLOP    = 8f  * dp    // px threshold for tap vs drag
    private val TEXT_PAD_DP = 6f          // padding around text in template-dp units (matches renderer)

    // ── Paint ─────────────────────────────────────────────────────────────────
    private val handleFill   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE;  style = Paint.Style.FILL }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = 1.5f }
    // Two-stripe selection border: dark underneath + white on top → visible on any background
    private val selBorderDark  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val selBorderLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f
    }

    // ── Text measurement (for overlay bounds) ────────────────────────────────
    private val overlayTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Returns the actual rendered half-sizes (hw, hh) in VIEW pixels for the element.
     *
     * Text elements: measured with large boxW (4096) so \n is respected without auto-wrapping.
     * Other elements: uses el.width/height scaled to view pixels.
     */
    private fun elementHalfSizes(el: TemplateElement): Pair<Float, Float> {
        val textInfo = textMeasureInfo(el)
        if (textInfo != null) {
            val (textStr, sizeSp, fontName, isBold) = textInfo
            overlayTextPaint.textSize = sizeSp * scaleX
            overlayTextPaint.typeface = resolveTypeface(fontName, isBold)
            // Large boxW — respects \n without wrapping long single lines
            val layout = StaticLayout.Builder
                .obtain(textStr, 0, textStr.length, overlayTextPaint, 4096)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build()
            val textW = (0 until layout.lineCount)
                .maxOfOrNull { layout.getLineWidth(it) } ?: overlayTextPaint.measureText(textStr)
            val textH = layout.height.toFloat()
            val padPx = TEXT_PAD_DP * scaleX
            return (textW / 2f + padPx) to (textH / 2f + padPx)
        }
        return (el.width * scaleX / 2f) to (el.height * scaleY / 2f)
    }

    /** Data class to hold text measurement information. */
    private data class TextMeasureInfo(val text: String, val textSizeSp: Float, val fontName: String, val isBold: Boolean)

    private fun textMeasureInfo(el: TemplateElement): TextMeasureInfo? = when (el) {
        is TemplateElement.TextElement ->
            TextMeasureInfo(el.text.ifBlank { "A" }, el.textSizeSp, el.fontName, el.isBold)
        is TemplateElement.UsernameElement ->
            TextMeasureInfo(dummyDigits(el.digitCount.coerceAtLeast(1)), el.textSizeSp, el.fontName, el.isBold)
        is TemplateElement.PasswordElement ->
            TextMeasureInfo(dummyDigits(el.digitCount.coerceAtLeast(1)), el.textSizeSp, el.fontName, el.isBold)
        is TemplateElement.DateElement ->
            TextMeasureInfo(renderer.formatDateNow(el), el.textSizeSp, el.fontName, el.isBold)
        else -> null
    }

    /** Must match TemplateRenderer.dummyDigits() exactly. */
    private fun dummyDigits(count: Int): String =
        (1..count).joinToString("") { (it % 10).toString() }

    private fun resolveTypeface(fontName: String, isBold: Boolean): Typeface {
        val style = if (isBold) Typeface.BOLD else Typeface.NORMAL
        return when (fontName.lowercase()) {
            "serif"      -> Typeface.create(Typeface.SERIF, style)
            "monospace"  -> Typeface.create(Typeface.MONOSPACE, style)
            "sans-serif" -> Typeface.create(Typeface.SANS_SERIF, style)
            else         -> Typeface.create(Typeface.DEFAULT, style)
        }
    }

    private fun isTextEl(el: TemplateElement) = textMeasureInfo(el) != null

    // ── Handle icons (lazy) ───────────────────────────────────────────────────
    private val iconResize: Drawable? by lazy { loadIcon(R.drawable.ic_handle_resize) }
    private val iconRotate: Drawable? by lazy { loadIcon(R.drawable.ic_handle_rotate) }
    private val iconDelete: Drawable? by lazy { loadIcon(R.drawable.ic_handle_delete) }
    private val iconMove:   Drawable? by lazy { loadIcon(R.drawable.ic_handle_move)   }
    private val iconHeight: Drawable? by lazy { loadIcon(R.drawable.ic_handle_height) }
    private fun loadIcon(res: Int) = AppCompatResources.getDrawable(context, res)
        ?.mutate()?.apply { setTint(Color.DKGRAY) }

    // ── Touch state ───────────────────────────────────────────────────────────
    private enum class Mode { NONE, DRAG, RESIZE, ROTATE, HEIGHT_DRAG, CONSUMED }
    private var mode = Mode.NONE
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var lastAngle = 0f
    private var resizeStartW = 0f; private var resizeStartH = 0f
    private var resizeStartX = 0f; private var resizeStartY = 0f
    private var resizeStartVisualW = 0f
    private var resizeStartAspect = 0f   // height/width ratio at drag start (0 = free resize)

    // ── Public API ────────────────────────────────────────────────────────────

    fun bind(template: Template, selectedId: String?, activeSide: CardSide = CardSide.FRONT) {
        val needsLayout = this.template == null
            || this.template!!.card.heightRatio != template.card.heightRatio
            || this.template!!.card.widthDp != template.card.widthDp
        this.template = template
        this.selectedId = selectedId
        this.activeSide = activeSide
        previewCache?.recycle()
        previewCache = null
        previewRendering = false
        if (needsLayout) requestLayout()
        invalidate()
    }

    // ── Measure ───────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val t = template
        // Add HR below the card so the height-handle icon is not clipped by the view bounds
        val h = if (t != null) (w * t.card.heightRatio + HR).toInt() else w
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); recalc()
    }

    private fun recalc() {
        val t = template ?: return
        cardWidthPx  = width.toFloat()
        cardHeightPx = cardWidthPx * t.card.heightRatio
        scaleX = cardWidthPx / t.card.widthDp
        scaleY = cardHeightPx / (t.card.widthDp * t.card.heightRatio)
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = template ?: return
        recalc()

        // Non-interactive preview: render asynchronously and cache for smooth scrolling
        if (!isInteractive) {
            val cached = previewCache
            if (cached != null) {
                val dstRect = android.graphics.RectF(0f, 0f, cardWidthPx, cardHeightPx)
                canvas.drawBitmap(cached, null, dstRect, null)
            } else {
                // Draw placeholder (card background color) while rendering in background
                val bgPaint = Paint().apply { color = parseColorSafe(t.card.backgroundColor) }
                canvas.drawRect(0f, 0f, cardWidthPx, cardHeightPx, bgPaint)

                if (!previewRendering && cardWidthPx > 0 && cardHeightPx > 0) {
                    previewRendering = true
                    val tCopy = t
                    
                    // Cap the preview dimension to balance performance and sharpness
                    val maxDim = 1600f
                    val scale = if (cardWidthPx > maxDim) maxDim / cardWidthPx else 1f
                    val reqW = (cardWidthPx * scale).toInt().coerceAtLeast(1)
                    val reqH = (cardHeightPx * scale).toInt().coerceAtLeast(1)

                    PREVIEW_EXECUTOR.execute {
                        try {
                            val q = dev.anonymous.cardsdesignerpro.data.model.ExportQuality.MEDIUM
                            renderer.maxImageDim = q.maxImageDim
                            renderer.maxPatternDim = q.maxPatternDim
                            renderer.maxQrDim = q.maxQrDim

                            val bmp = android.graphics.Bitmap.createBitmap(reqW, reqH, android.graphics.Bitmap.Config.ARGB_8888)
                            renderer.draw(Canvas(bmp), tCopy, 0f, 0f, reqW.toFloat(), reqH.toFloat())
                            post {
                                previewCache = bmp
                                previewRendering = false
                                invalidate()
                            }
                        } catch (_: Exception) {
                            post { previewRendering = false }
                        }
                    }
                }
            }
            return
        }

        // Build a render-proxy: same card style, but active side's elements
        val activeCard = if (activeSide == CardSide.BACK && t.isBackSideEnabled)
            t.backCard ?: t.card else t.card
        val renderTemplate = if (activeSide == CardSide.BACK && t.isBackSideEnabled)
            t.copy(elements = t.backElements ?: emptyList(), card = activeCard)
        else t
        renderer.draw(canvas, renderTemplate, 0f, 0f, cardWidthPx, cardHeightPx)
        drawElementOverlay(canvas, renderTemplate)
        drawHeightHandleIcon(canvas)
    }

    private fun drawElementOverlay(canvas: Canvas, t: Template) {
        val id = selectedId ?: return
        if (id == "card_background") return
        val el = t.elements.firstOrNull { it.id == id } ?: return
        // Frame fills the entire card — no handles needed
        if (el is TemplateElement.FrameElement) return

        val cx = (el.x + el.width  / 2) * scaleX
        val cy = (el.y + el.height / 2) * scaleY
        // For text elements: use measured text bounds; for others: use model bounds
        val (hw, hh) = elementHalfSizes(el)

        canvas.save()
        canvas.rotate(el.rotation, cx, cy)
        // Two-stripe dashed border: dark first (offset), then white — readable on any background
        val dashOn = 10f; val dashOff = 6f
        selBorderDark.pathEffect  = DashPathEffect(floatArrayOf(dashOn, dashOff), 0f)
        selBorderLight.pathEffect = DashPathEffect(floatArrayOf(dashOn, dashOff), (dashOn + dashOff) / 2)
        val rect = RectF(cx - hw, cy - hh, cx + hw, cy + hh)
        canvas.drawRect(rect, selBorderDark)
        canvas.drawRect(rect, selBorderLight)
        selBorderDark.pathEffect = null; selBorderLight.pathEffect = null
        // Handles — offset away from the element corners (4 handles)
        drawHandle(canvas, cx + hw + HANDLE_OFF, cy + hh + HANDLE_OFF, iconResize)  // bottom-right → resize
        drawHandle(canvas, cx + hw + HANDLE_OFF, cy - hh - HANDLE_OFF, iconRotate)  // top-right    → rotate
        drawHandle(canvas, cx - hw - HANDLE_OFF, cy - hh - HANDLE_OFF, iconDelete)  // top-left     → delete
        drawHandle(canvas, cx - hw - HANDLE_OFF, cy + hh + HANDLE_OFF, iconMove)    // bottom-left  → move
        canvas.restore()
    }

    /**
     * Height handle: small circle at the exact bottom-center of the card.
     * Centered ON the edge → half inside / half outside → never fully clipped.
     * Visible only when card_background is selected.
     */
    private fun drawHeightHandleIcon(canvas: Canvas) {
        if (selectedId != "card_background") return
        val cx = cardWidthPx / 2f
        val cy = cardHeightPx  // center on the card's bottom edge
        drawHandle(canvas, cx, cy, iconHeight)
    }

    /** Draws a circular handle with an icon centered inside it. */
    private fun drawHandle(canvas: Canvas, cx: Float, cy: Float, icon: Drawable?) {
        canvas.drawCircle(cx, cy, HR, handleFill)
        canvas.drawCircle(cx, cy, HR, handleStroke)
        icon?.let {
            val half = (HR * 0.65f).toInt()
            it.setBounds(
                (cx - half).toInt(), (cy - half).toInt(),
                (cx + half).toInt(), (cy + half).toInt()
            )
            it.draw(canvas)
        }
    }

    var isInteractive: Boolean = true

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInteractive) return super.onTouchEvent(event)
        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                val x = event.x; val y = event.y
                downX = x; downY = y; lastX = x; lastY = y
                determineMode(x, y)
                // KEY: prevent NestedScrollView / ViewPager from stealing events
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(activePointerId)
                if (idx < 0) return true
                val x = event.getX(idx); val y = event.getY(idx)
                processMove(x, y)
                lastX = x; lastY = y
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                val idx = event.findPointerIndex(activePointerId)
                val x = if (idx >= 0) event.getX(idx) else lastX
                val y = if (idx >= 0) event.getY(idx) else lastY
                if (mode != Mode.CONSUMED && hypot((x - downX).toDouble(), (y - downY).toDouble()) < TAP_SLOP)
                    handleTap(downX, downY)
                endGesture()
            }

            MotionEvent.ACTION_CANCEL -> endGesture()

            MotionEvent.ACTION_POINTER_UP -> {
                val pIdx = (event.action and MotionEvent.ACTION_POINTER_INDEX_MASK) shr
                           MotionEvent.ACTION_POINTER_INDEX_SHIFT
                if (event.getPointerId(pIdx) == activePointerId) {
                    val newIdx = if (pIdx == 0) 1 else 0
                    activePointerId = event.getPointerId(newIdx)
                    lastX = event.getX(newIdx); lastY = event.getY(newIdx)
                }
            }
        }
        return true
    }

    private fun endGesture() {
        mode = Mode.NONE
        activePointerId = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    // ── Mode determination on ACTION_DOWN ─────────────────────────────────────

    private fun determineMode(x: Float, y: Float) {
        mode = Mode.NONE

        if (selectedId == "card_background") {
            val hcx = cardWidthPx / 2f
            val hcy = cardHeightPx
            if (dist(x, y, hcx, hcy) < HR * 2f) {
                mode = Mode.HEIGHT_DRAG; return
            }
        }

        val id = selectedId ?: return
        val el = activeElements.firstOrNull { it.id == id } ?: return
        if (el is TemplateElement.CardBackground
            || el is TemplateElement.FrameElement) return

        val cx = (el.x + el.width  / 2) * scaleX
        val cy = (el.y + el.height / 2) * scaleY
        // MUST match the positions used in drawElementOverlay()
        val (hw, hh) = elementHalfSizes(el)

        // ── Inverse-rotate touch into element's unrotated local space ───────
        val rad = Math.toRadians(el.rotation.toDouble())
        val cosR = kotlin.math.cos(rad).toFloat()
        val sinR = kotlin.math.sin(rad).toFloat()
        val dx = x - cx; val dy = y - cy
        val lx = cx + dx * cosR + dy * sinR
        val ly = cy - dx * sinR + dy * cosR

        // Delete handle (top-left, offset)
        if (dist(lx, ly, cx - hw - HANDLE_OFF, cy - hh - HANDLE_OFF) < HR * 2f) {
            // Fire delete immediately, don't enter a drag mode
            listener?.onElementDeleteRequested(id)
            mode = Mode.CONSUMED
            return
        }
        // Move handle (bottom-left, offset)
        if (dist(lx, ly, cx - hw - HANDLE_OFF, cy + hh + HANDLE_OFF) < HR * 2f) {
            mode = Mode.DRAG; return
        }
        if (dist(lx, ly, cx + hw + HANDLE_OFF, cy - hh - HANDLE_OFF) < HR * 2f) {   // rotate (top-right)
            mode = Mode.ROTATE
            lastAngle = atan2((y - cy).toDouble(), (x - cx).toDouble()).toFloat(); return
        }
        if (dist(lx, ly, cx + hw + HANDLE_OFF, cy + hh + HANDLE_OFF) < HR * 2f) {   // resize (bottom-right)
            mode = Mode.RESIZE
            resizeStartW = el.width; resizeStartH = el.height
            resizeStartX = x; resizeStartY = y
            resizeStartVisualW = hw * 2f / scaleX
            // Proportional resize for Image, QR, and all text elements
            resizeStartAspect = when {
                el is TemplateElement.ImageElement || el is TemplateElement.QrElement ||
                isTextEl(el) -> if (el.width > 0f) el.height / el.width else 1f
                else -> 0f
            }
            return
        }
        val elRect = RectF(cx - hw, cy - hh, cx + hw, cy + hh)
        if (elRect.contains(lx, ly)) {
            mode = Mode.DRAG                                     // move
        }
    }

    // ── Gesture processing on ACTION_MOVE ─────────────────────────────────────

    private fun processMove(x: Float, y: Float) {
        val id = selectedId ?: return
        when (mode) {
            Mode.DRAG -> {
                listener?.onElementMoved(id, (x - lastX) / scaleX, (y - lastY) / scaleY)
            }
            Mode.RESIZE -> {
                val el = activeElements.firstOrNull { it.id == id } ?: return
                if (isTextEl(el)) {
                    val distDelta = (x - resizeStartX) / scaleX
                    val visualScale = ((resizeStartVisualW + distDelta) / resizeStartVisualW).coerceAtLeast(0.1f)
                    val newW = (resizeStartW * visualScale).coerceAtLeast(10f)
                    val newH = (resizeStartH * visualScale).coerceAtLeast(10f)
                    listener?.onElementResized(id, newW, newH)
                } else if (resizeStartAspect > 0f) {
                    val rawW = resizeStartW + (x - resizeStartX) / scaleX
                    val minW = if (resizeStartAspect > 1f) 20f / resizeStartAspect else 20f
                    val newW = rawW.coerceAtLeast(minW)
                    listener?.onElementResized(id, newW, newW * resizeStartAspect)
                } else {
                    val newW = (resizeStartW + (x - resizeStartX) / scaleX).coerceAtLeast(20f)
                    val newH = (resizeStartH + (y - resizeStartY) / scaleY).coerceAtLeast(20f)
                    listener?.onElementResized(id, newW, newH)
                }
            }
            Mode.ROTATE -> {
                val el = activeElements.firstOrNull { it.id == id } ?: return
                val cx = (el.x + el.width  / 2) * scaleX
                val cy = (el.y + el.height / 2) * scaleY
                val angle = atan2((y - cy).toDouble(), (x - cx).toDouble()).toFloat()
                val delta = Math.toDegrees((angle - lastAngle).toDouble()).toFloat()
                listener?.onElementRotated(id, delta)
                lastAngle = angle
            }
            Mode.HEIGHT_DRAG -> {
                listener?.onCardHeightDrag((y - lastY) / cardWidthPx)
            }
            Mode.NONE, Mode.CONSUMED -> {}
        }
    }

    // ── Tap ───────────────────────────────────────────────────────────────────

    private fun handleTap(x: Float, y: Float) {
        val t = template ?: return
        if (selectedId == "card_background" && dist(x, y, cardWidthPx / 2, cardHeightPx) < HR * 2f) return
        if (x < 0f || x > cardWidthPx || y < 0f || y > cardHeightPx) {
            listener?.onElementSelected(null); return
        }
        for (el in activeElements) {
            if (!el.isVisible || el is TemplateElement.CardBackground
                || el is TemplateElement.FrameElement) continue
            val cx = (el.x + el.width / 2) * scaleX
            val cy = (el.y + el.height / 2) * scaleY
            val (hw, hh) = elementHalfSizes(el)
            if (x in (cx - hw)..(cx + hw) && y in (cy - hh)..(cy + hh)) {
                listener?.onElementSelected(el.id); return
            }
        }
        listener?.onCardBackgroundSelected()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) =
        hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()

    fun clearCache() = renderer.clearBitmapCache()

    private fun parseColorSafe(hex: String) =
        runCatching { Color.parseColor(hex) }.getOrElse { Color.WHITE }

    companion object {
        /** Single background thread shared by all preview instances to avoid thread explosion. */
        private val PREVIEW_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor()
    }
}

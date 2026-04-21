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
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import androidx.core.graphics.toColorInt

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
 *  - activePointerId tracks the exact finger for multitouch safety
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
        fun onShapeWidthResized(id: String, newWidth: Float)
        fun onShapeHeightResized(id: String, newY: Float, newHeight: Float)
        fun onTextWidthResized(id: String, newWidth: Float)
        /** Called during bottom-right drag on a TextElement: all three values are absolute targets.
         *  [newWidth] and [newHeight] are in template-dp; [newSizeSp] is the target font size. */
        fun onTextFontScaled(id: String, newSizeSp: Float, newWidth: Float, newHeight: Float)
    }

    var listener: Listener? = null

    private val renderer = TemplateRenderer(context).apply {
        // WYSIWYG: editor shows FULL quality so the design matches the best possible export
        val q = dev.anonymous.cardsdesignerpro.data.model.ExportQuality.FULL
        maxImageDim = q.maxImageDim
        maxQrDim    = q.maxQrDim
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
    /** Snap-to-square threshold for ShapeElement pill handles (template-dp units). */
    private val SHAPE_SQUARE_SNAP = 8f

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
    // Connector line from bottom-center to move handle
    private val connectorDark  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val connectorLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    // Snap guidelines (drawn during DRAG)
    private val snapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF18C8FF.toInt()   // bright cyan
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    // Pill handle fill (for shape width / height handles)
    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /** Vertical snap lines: X values in template-dp where alignment was detected. */
    private val snapLinesX = mutableListOf<Float>()
    /** Horizontal snap lines: Y values in template-dp where alignment was detected. */
    private val snapLinesY = mutableListOf<Float>()

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
                .obtain(textStr, 0, textStr.length, overlayTextPaint, 8192)
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
        // TextElement bounds are stored in el.width/el.height (set at creation + resized by pill handle)
        // → do NOT measure dynamically; fall through to el.width/el.height path.
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

    private val typefaceCache = mutableMapOf<String, Typeface>()

    private fun resolveTypeface(fontName: String, isBold: Boolean): Typeface {
        val key = "$fontName|$isBold"
        return typefaceCache.getOrPut(key) {
            val style = if (isBold) Typeface.BOLD else Typeface.NORMAL
            val baseTypeface = when (fontName.lowercase()) {
                "default", "" -> Typeface.DEFAULT
                "serif"       -> Typeface.SERIF
                "monospace"   -> Typeface.MONOSPACE
                "sans-serif"  -> Typeface.SANS_SERIF
                else -> {
                    try {
                        val resId = context.resources.getIdentifier(fontName, "font", context.packageName)
                        if (resId != 0) androidx.core.content.res.ResourcesCompat.getFont(context, resId) ?: Typeface.DEFAULT
                        else Typeface.DEFAULT
                    } catch (_: Exception) { Typeface.DEFAULT }
                }
            }
            Typeface.create(baseTypeface, style)
        }
    }

    private fun isTextEl(el: TemplateElement) = textMeasureInfo(el) != null

    // ── Handle icons (lazy) ───────────────────────────────────────────────────
    private val iconResize: Drawable? by lazy { loadIcon(R.drawable.ic_handle_resize) }
    private val iconRotate: Drawable? by lazy { loadIcon(R.drawable.ic_handle_rotate) }
    private val iconDelete: Drawable? by lazy { loadIcon(R.drawable.ic_handle_delete) }
    private val iconMove:   Drawable? by lazy { loadIcon(R.drawable.ic_handle_move)   }
    private val iconHeight: Drawable? by lazy { loadIcon(R.drawable.ic_handle_height) }

    // Visibility toggle (eye) icon — loaded dynamically based on el.isVisible state
    private val iconVisOn:  Drawable? by lazy { loadIcon(R.drawable.ic_visibility_24)     }
    private val iconVisOff: Drawable? by lazy { loadIcon(R.drawable.ic_visibility_off_24) }
    private fun loadIcon(res: Int) = AppCompatResources.getDrawable(context, res)
        ?.mutate()?.apply { setTint(Color.DKGRAY) }

    // ── Touch state ─────────────────────────────────────────────────────
    private enum class Mode { NONE, DRAG, RESIZE, RESIZE_W, RESIZE_H, ROTATE, HEIGHT_DRAG, CONSUMED }
    private var mode = Mode.NONE
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var lastAngle = 0f
    private var resizeStartW = 0f; private var resizeStartH = 0f
    private var resizeStartX = 0f; private var resizeStartY = 0f
    private var resizeStartVisualW = 0f
    private var resizeStartAspect = 0f     // height/width ratio at drag start (0 = free resize)
    private var resizeStartRotation = 0f   // element rotation (degrees) at drag start
    private var resizeShapeStartElY = 0f   // el.y at RESIZE_H drag start (to keep bottom fixed)
    private var resizeStartTextSizeSp = 0f // TextElement font size at RESIZE drag start
    // Drag (MOVE) start tracking — used for absolute-position snap calculation
    private var dragStartElX    = 0f      // el.x at the moment DRAG mode was entered
    private var dragStartElY    = 0f      // el.y at the moment DRAG mode was entered
    private var dragStartTouchX = 0f      // view-pixel x of the finger at DRAG start
    private var dragStartTouchY = 0f      // view-pixel y of the finger at DRAG start
    /** Tracks whether the shape's W/H snap-to-square is currently active (for haptic de-dup). */
    private var shapeSquareSnapped = false
    /** When false: the dashed selection border is hidden so the user can preview
     *  the element's own border/stroke. Handles remain visible. Resets on re-selection. */
    private var showSelectionOverlay = true

    // ── Public API ────────────────────────────────────────────────────────────

    fun bind(template: Template, selectedId: String?, activeSide: CardSide = CardSide.FRONT) {
        val needsLayout = this.template == null
            || this.template!!.card.heightRatio != template.card.heightRatio
            || this.template!!.card.widthDp != template.card.widthDp
        // Reset overlay visibility whenever the selected element changes
        if (selectedId != this.selectedId) showSelectionOverlay = true
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
                val dstRect = RectF(0f, 0f, cardWidthPx, cardHeightPx)
                canvas.drawBitmap(cached, null, dstRect, null)
            } else {
                // Draw placeholder (card background color) while rendering in background
                val bgPaint = Paint().apply { color = parseColorSafe(t.card.backgroundColor) }
                canvas.drawRect(0f, 0f, cardWidthPx, cardHeightPx, bgPaint)

                if (!previewRendering && cardWidthPx > 0 && cardHeightPx > 0) {
                    previewRendering = true
                    val tCopy = t
                    
                    // Cap the preview dimension to balance performance and sharpness.
                    // 900px gives good quality in the list while keeping render times short
                    // (≈4× fewer pixels than 1600px → much faster landscape scrolling).
                    val maxDim = 1200f
                    val scale = if (cardWidthPx > maxDim) maxDim / cardWidthPx else 1f
                    val reqW = (cardWidthPx * scale).toInt().coerceAtLeast(1)
                    val reqH = (cardHeightPx * scale).toInt().coerceAtLeast(1)

                    PREVIEW_EXECUTOR.execute {
                        try {
                            val q = dev.anonymous.cardsdesignerpro.data.model.ExportQuality.MEDIUM
                            renderer.maxImageDim = q.maxImageDim
                            renderer.maxQrDim    = q.maxQrDim

                            val bmp = createBitmap(reqW, reqH)
                            renderer.draw(Canvas(bmp), tCopy, 0f, 0f, reqW.toFloat(),
                                reqH.toFloat()
                            )
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
        drawSnapLines(canvas)
        drawElementOverlay(canvas, renderTemplate)
        drawHeightHandleIcon(canvas)
    }

    private fun drawElementOverlay(canvas: Canvas, t: Template) {
        val id = selectedId ?: return
        if (id == "card_background") return
        val el = t.elements.firstOrNull { it.id == id } ?: return
        // Frame fills the entire card — no handles needed
        if (el is TemplateElement.FrameElement) return
        // Preview mode (ShapeElement eye-button): hide dashes + handles,
        // but keep the eye icon visible so user can toggle back.
        if (!showSelectionOverlay) {
            val cx = (el.x + el.width  / 2) * scaleX
            val cy = (el.y + el.height / 2) * scaleY
            val (hw, hh) = elementHalfSizes(el)
            canvas.withRotation(el.rotation, cx, cy) {
                drawHandle(this, cx - hw - HANDLE_OFF, cy + hh + HANDLE_OFF, iconVisOff)
            }
            return
        }

        val cx = (el.x + el.width / 2) * scaleX
        val cy = (el.y + el.height / 2) * scaleY
        // For text elements: use measured text bounds; for others: use model bounds
        val (hw, hh) = elementHalfSizes(el)

        canvas.withRotation(el.rotation, cx, cy) {
            // Two-stripe dashed border: hidden when user toggles the eye-handle for preview
            if (showSelectionOverlay) {
                val dashOn = 10f
                val dashOff = 6f
                selBorderDark.pathEffect = DashPathEffect(floatArrayOf(dashOn, dashOff), 0f)
                selBorderLight.pathEffect =
                    DashPathEffect(floatArrayOf(dashOn, dashOff), (dashOn + dashOff) / 2)
                val rect = RectF(cx - hw, cy - hh, cx + hw, cy + hh)
                drawRect(rect, selBorderDark)
                drawRect(rect, selBorderLight)
                selBorderDark.pathEffect = null; selBorderLight.pathEffect = null
            }
            // Handles:
            //  top-left      → delete
            //  top-right     → rotate
            //  bottom-right  → resize (proportional for ShapeElement)
            //  bottom-left   → overlay toggle (eye): hides/shows the dashed selection border
            //  bottom-center → move
            //  right edge    → width  (ShapeElement only, pill style)
            //  top edge      → height (ShapeElement only, pill style)
            drawHandle(this, cx + hw + HANDLE_OFF, cy + hh + HANDLE_OFF, iconResize)
            drawHandle(this, cx + hw + HANDLE_OFF, cy - hh - HANDLE_OFF, iconRotate)
            drawHandle(this, cx - hw - HANDLE_OFF, cy - hh - HANDLE_OFF, iconDelete)
            // ShapeElement-only: pill handles on right/top edges + eye toggle at bottom-left
            if (el is TemplateElement.ShapeElement) {
                // Pass the full edge length so the pill scales with the element size
                drawPillHandle(
                    this,
                    cx + hw,
                    cy,
                    isVertical = true,
                    edgePx = hh * 2f
                )  // right edge → width
                drawPillHandle(
                    this,
                    cx,
                    cy - hh,
                    isVertical = false,
                    edgePx = hw * 2f
                )  // top edge  → height
                drawHandle(
                    this,
                    cx - hw - HANDLE_OFF,
                    cy + hh + HANDLE_OFF,                  // bottom-left → eye
                    if (showSelectionOverlay) iconVisOn else iconVisOff
                )
            }
            // TextElement-only: right-edge pill handle for width resize
            if (el is TemplateElement.TextElement) {
                drawPillHandle(
                    this,
                    cx + hw,
                    cy,
                    isVertical = true,
                    edgePx = hh * 2f
                )  // right edge → width
            }
            // Connector line: bottom-center of rect → move handle
            val connDash = DashPathEffect(floatArrayOf(6f, 5f), 0f)
            connectorDark.pathEffect = connDash
            connectorLight.pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 5.5f)
            drawLine(cx, cy + hh, cx, cy + hh + HANDLE_OFF * 2 - HR, connectorDark)
            drawLine(cx, cy + hh, cx, cy + hh + HANDLE_OFF * 2 - HR, connectorLight)
            connectorDark.pathEffect = null; connectorLight.pathEffect = null
            drawHandle(this, cx, cy + hh + HANDLE_OFF * 2, iconMove) // bottom-center → move
        }
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

    /**
     * Draws a bottom-sheet-style drag pill handle centered at (cx, cy) and positioned
     * ON the element edge (half inside / half outside — same as the card height handle).
     *
     * [isVertical] = true  → tall thin pill on the right edge  (width resize)
     * [isVertical] = false → wide thin pill on the top edge    (height resize)
     * [edgePx]             → length of the edge in view-pixels; the pill's long
     *                        dimension scales to ~35% of it, clamped to [10dp, 40dp]
     *
     * Appearance: light rounded-rect shell + smaller lighter inner bar.
     */
    private fun drawPillHandle(
        canvas: Canvas, cx: Float, cy: Float,
        isVertical: Boolean, edgePx: Float
    ) {
        // Long dimension scales with the element edge, clamped to a tasteful range
        val maxLong  = 40f * dp
        val minLong  = 10f * dp
        val dynLong  = (edgePx * 0.35f).coerceIn(minLong, maxLong)

        val outerLong  = dynLong
        val outerShort =  7f * dp   // thin slab
        val innerLong  = outerLong * 0.55f
        val innerShort =  3.5f * dp
        val r          =  4f * dp

        val outerW = if (isVertical) outerShort else outerLong
        val outerH = if (isVertical) outerLong  else outerShort
        val innerW = if (isVertical) innerShort else innerLong
        val innerH = if (isVertical) innerLong  else innerShort

        val outer = RectF(cx - outerW / 2f, cy - outerH / 2f, cx + outerW / 2f, cy + outerH / 2f)
        val inner = RectF(cx - innerW / 2f, cy - innerH / 2f, cx + innerW / 2f, cy + innerH / 2f)

        // Outer shell: very light gray with a subtle border
        pillFill.color = 0xFFEBEBEB.toInt()
        canvas.drawRoundRect(outer, r, r, pillFill)
        canvas.drawRoundRect(outer, r, r, handleStroke)

        // Inner bar: medium gray
        pillFill.color = 0xFF999999.toInt()
        canvas.drawRoundRect(inner, r, r, pillFill)
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
        snapLinesX.clear()
        snapLinesY.clear()
        shapeSquareSnapped = false   // reset square-snap state for next drag
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

        // Preview mode: only allow eye-tap (restore overlay) and body drag
        if (!showSelectionOverlay) {
            if (el is TemplateElement.ShapeElement &&
                dist(lx, ly, cx - hw - HANDLE_OFF, cy + hh + HANDLE_OFF) < HR * 2f) {
                showSelectionOverlay = true   // restore overlay
                invalidate()
                mode = Mode.CONSUMED
                return
            }
            // Allow body drag without restoring overlay
            val elRect = RectF(cx - hw, cy - hh, cx + hw, cy + hh)
            if (elRect.contains(lx, ly)) {
                mode = Mode.DRAG
                dragStartElX = el.x; dragStartElY = el.y
                dragStartTouchX = x; dragStartTouchY = y
            }
            return
        }

        // Delete handle (top-left, offset)
        if (dist(lx, ly, cx - hw - HANDLE_OFF, cy - hh - HANDLE_OFF) < HR * 2f) {
            listener?.onElementDeleteRequested(id)
            mode = Mode.CONSUMED
            return
        }
        // Eye handle (bottom-left) — ShapeElement only: hides ALL overlay for preview
        if (el is TemplateElement.ShapeElement &&
            dist(lx, ly, cx - hw - HANDLE_OFF, cy + hh + HANDLE_OFF) < HR * 2f) {
            showSelectionOverlay = false   // hides dashes + ALL handles → full preview
            invalidate()
            mode = Mode.CONSUMED
            return
        }
        // Move handle — bottom-center (further out for clear separation from frame)
        if (dist(lx, ly, cx, cy + hh + HANDLE_OFF * 2) < HR * 2f) {
            mode = Mode.DRAG
            dragStartElX = el.x; dragStartElY = el.y
            dragStartTouchX = x; dragStartTouchY = y
            return
        }
        if (dist(lx, ly, cx + hw + HANDLE_OFF, cy - hh - HANDLE_OFF) < HR * 2f) {   // rotate (top-right)
            mode = Mode.ROTATE
            lastAngle = atan2((y - cy).toDouble(), (x - cx).toDouble()).toFloat(); return
        }
        if (dist(lx, ly, cx + hw + HANDLE_OFF, cy + hh + HANDLE_OFF) < HR * 2f) {   // resize (bottom-right)
            mode = Mode.RESIZE
            resizeStartW        = el.width
            resizeStartH        = el.height
            resizeStartX        = x
            resizeStartY        = y
            resizeStartVisualW  = hw * 2f / scaleX
            resizeStartRotation = el.rotation
            // Store initial font size for ALL text-based elements to support clamped scaling
            resizeStartTextSizeSp = when (el) {
                is TemplateElement.TextElement -> el.textSizeSp
                is TemplateElement.UsernameElement -> el.textSizeSp
                is TemplateElement.PasswordElement -> el.textSizeSp
                is TemplateElement.DateElement -> el.textSizeSp
                else -> 0f
            }
            // Proportional resize for Image, QR, Shape, and other text elements
            resizeStartAspect = when {
                el is TemplateElement.ImageElement || el is TemplateElement.QrElement ||
                el is TemplateElement.ShapeElement ||
                isTextEl(el) -> if (el.width > 0f) el.height / el.width else 1f
                else -> 0f
            }
            return
        }
        // Shape-only pill handles: use a NARROW RECTANGULAR hit zone along each edge
        // (much tighter than HR*3f circle, prevents false triggers from body drags)
        if (el is TemplateElement.ShapeElement) {
            val pillHitShort = 16f * dp   // how far perpendicular to edge counts as a hit
            val pillHitLong  = hh * 0.6f  // how far along the edge (capped at 60% of half-height)
            // Right edge: touch must be within pillHitShort of cx+hw and within pillHitLong of cy
            if (kotlin.math.abs(lx - (cx + hw)) < pillHitShort &&
                kotlin.math.abs(ly - cy)        < pillHitLong) {
                mode = Mode.RESIZE_W
                resizeStartW = el.width
                resizeStartX = x
                return
            }
            val pillHitLong2 = hw * 0.6f  // how far along the top edge
            // Top edge: touch must be within pillHitShort of cy-hh and within pillHitLong of cx
            if (kotlin.math.abs(ly - (cy - hh)) < pillHitShort &&
                kotlin.math.abs(lx - cx)        < pillHitLong2) {
                mode = Mode.RESIZE_H
                resizeStartH        = el.height
                resizeStartY        = y
                resizeShapeStartElY = el.y
                return
            }
        }
        // TextElement right-edge pill handle: width resize (height recalculates automatically)
        if (el is TemplateElement.TextElement) {
            val pillHitShort = 16f * dp
            val pillHitLong  = hh * 0.6f
            if (kotlin.math.abs(lx - (cx + hw)) < pillHitShort &&
                kotlin.math.abs(ly - cy)        < pillHitLong) {
                mode = Mode.RESIZE_W
                resizeStartW = el.width
                resizeStartX = x
                return
            }
        }
        val elRect = RectF(cx - hw, cy - hh, cx + hw, cy + hh)
        if (elRect.contains(lx, ly)) {
            mode = Mode.DRAG
            dragStartElX = el.x; dragStartElY = el.y
            dragStartTouchX = x; dragStartTouchY = y
        }
    }

    // ── Gesture processing on ACTION_MOVE ─────────────────────────────────────

    private fun processMove(x: Float, y: Float) {
        val id = selectedId ?: return
        when (mode) {
            Mode.DRAG -> {
                // Pass absolute touch position — computeSnap uses dragStart to compute total displacement
                val (adjDx, adjDy) = computeSnap(id, x, y)
                listener?.onElementMoved(id, adjDx, adjDy)
            }
            Mode.RESIZE -> {
                val el = activeElements.firstOrNull { it.id == id } ?: return
                // Project the screen-space drag vector onto the element's local axes.
                // This makes resize behave correctly regardless of element rotation.
                val rad    = Math.toRadians(resizeStartRotation.toDouble())
                val cosR   = kotlin.math.cos(rad).toFloat()
                val sinR   = kotlin.math.sin(rad).toFloat()
                val screenDx = (x - resizeStartX) / scaleX
                val screenDy = (y - resizeStartY) / scaleY
                // Local X component = how much we moved along the element's width axis
                val localDx = screenDx * cosR + screenDy * sinR

                if (el is TemplateElement.TextElement || isTextEl(el)) {
                    // ALL text elements: bottom-right drag = uniform SCALE.
                    // Font size is clamped first (10sp - 100sp).
                    // Effective scale is derived from clamped size → stops W/H when font limit hit.
                    val rawScale    = ((resizeStartVisualW + localDx) / resizeStartVisualW).coerceAtLeast(0.1f)
                    val newSizeSp   = (resizeStartTextSizeSp * rawScale).coerceIn(10f, 100f)
                    val effectiveScale = if (resizeStartTextSizeSp > 0f) newSizeSp / resizeStartTextSizeSp else 1f
                    
                    val newW = (resizeStartW * effectiveScale).coerceAtLeast(20f)
                    val newH = (resizeStartH * effectiveScale).coerceAtLeast(8f)
                    
                    listener?.onTextFontScaled(id, newSizeSp, newW, newH)
                } else if (resizeStartAspect > 0f) {
                    // Aspect-locked: Image, QR — use X-axis movement
                    val minW = if (resizeStartAspect > 1f) 20f / resizeStartAspect else 20f
                    val newW = (resizeStartW + localDx).coerceAtLeast(minW)
                    listener?.onElementResized(id, newW, newW * resizeStartAspect)
                } else {
                    // Free resize (e.g. Frame, Line) — independent W and H
                    val localDy = -screenDx * sinR + screenDy * cosR
                    val newW = (resizeStartW + localDx).coerceAtLeast(20f)
                    val newH = (resizeStartH + localDy).coerceAtLeast(20f)
                    listener?.onElementResized(id, newW, newH)
                }
            }
            Mode.ROTATE -> {
                val el = activeElements.firstOrNull { it.id == id } ?: return
                val cx = (el.x + el.width  / 2) * scaleX
                val cy = (el.y + el.height / 2) * scaleY
                val angle = atan2((y - cy).toDouble(), (x - cx).toDouble()).toFloat()
                // Snap to integer degrees — eliminates sub-degree jitter from small finger movement
                val rawDelta = Math.toDegrees((angle - lastAngle).toDouble()).toFloat()
                val snappedDelta = rawDelta.toInt().toFloat()  // truncate to whole degrees
                if (snappedDelta != 0f) {
                    // Haptic bump at 0 / 90 / 180 / 270 degrees
                    val newAngle = ((el.rotation + snappedDelta) % 360f + 360f) % 360f
                    if (isNearCardinalAngle(newAngle) && !isNearCardinalAngle(((el.rotation) % 360f + 360f) % 360f)) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    }
                    listener?.onElementRotated(id, snappedDelta)
                    lastAngle = angle
                }
            }
            Mode.HEIGHT_DRAG -> {
                listener?.onCardHeightDrag((y - lastY) / cardWidthPx)
            }
            Mode.RESIZE_W -> {
                // Drag right-center handle: changes width only, left edge stays fixed
                val el = activeElements.firstOrNull { it.id == id } ?: return
                val rawW = (resizeStartW + (x - resizeStartX) / scaleX).coerceAtLeast(10f)
                if (el is TemplateElement.TextElement) {
                    // TextElement: no snap-to-square; height is auto-recalculated by ViewModel
                    listener?.onTextWidthResized(id, rawW)
                } else {
                    // ShapeElement: snap to square
                    val newW = if (kotlin.math.abs(rawW - el.height) < SHAPE_SQUARE_SNAP) {
                        if (!shapeSquareSnapped) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            shapeSquareSnapped = true
                        }
                        el.height
                    } else {
                        shapeSquareSnapped = false
                        rawW
                    }
                    listener?.onShapeWidthResized(id, newW)
                }
            }
            Mode.RESIZE_H -> {
                // Drag top-center handle: top edge moves, bottom edge stays fixed
                val el = activeElements.firstOrNull { it.id == id } ?: return
                val deltaY     = (y - resizeStartY) / scaleY
                val bottomEdge = resizeShapeStartElY + resizeStartH
                val rawH = (resizeStartH - deltaY).coerceAtLeast(10f)
                // Snap to square: if height ≈ width, lock them equal
                val newH = if (kotlin.math.abs(rawH - el.width) < SHAPE_SQUARE_SNAP) {
                    if (!shapeSquareSnapped) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        shapeSquareSnapped = true
                    }
                    el.width
                } else {
                    shapeSquareSnapped = false
                    rawH
                }
                val newY = bottomEdge - newH
                listener?.onShapeHeightResized(id, newY, newH)
            }
            Mode.NONE, Mode.CONSUMED -> {}
        }
    }

    // ── Tap ───────────────────────────────────────────────────────────────────

    private fun handleTap(x: Float, y: Float) {
        template ?: return
        if (selectedId == "card_background" && dist(x, y, cardWidthPx / 2, cardHeightPx) < HR * 2f) return
        if (x !in 0f..cardWidthPx || y < 0f || y > cardHeightPx) {
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

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) =
        hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()

    /** Returns true when [angle] (0-360°) is within 1° of a cardinal angle (0, 90, 180, 270). */
    private fun isNearCardinalAngle(angle: Float): Boolean {
        val cardinals = floatArrayOf(0f, 90f, 180f, 270f, 360f)
        return cardinals.any { kotlin.math.abs(angle - it) <= 1f }
    }

    private fun parseColorSafe(hex: String) =
        runCatching { hex.toColorInt() }.getOrElse { Color.WHITE }

    // ── Snap Guidelines ───────────────────────────────────────────────────────

    /**
     * Center-only snap: snaps the dragged element's CENTER to:
     *  - the card's horizontal/vertical center axis
     *  - any other visible element's center X or center Y
     *
     * Uses total displacement from drag-start (not per-frame delta) so the element
     * never locks onto a snap line — it escapes as soon as the finger moves
     * more than SNAP_THRESHOLD_PX away on screen.
     */
    private fun computeSnap(id: String, touchX: Float, touchY: Float): Pair<Float, Float> {
        snapLinesX.clear(); snapLinesY.clear()
        val el = activeElements.firstOrNull { it.id == id } ?: return 0f to 0f
        val t  = template ?: return 0f to 0f
        val card  = if (activeSide == CardSide.BACK && t.isBackSideEnabled) t.backCard ?: t.card else t.card

        // Total finger movement since drag started → proposed top-left
        val targetX = dragStartElX + (touchX - dragStartTouchX) / scaleX
        val targetY = dragStartElY + (touchY - dragStartTouchY) / scaleY
        val w = el.width; val h = el.height

        // Proposed center of the dragged element
        val propCX = targetX + w / 2f
        val propCY = targetY + h / 2f

        // Snap targets: card center + other elements' centers ONLY
        val cardW   = card.widthDp
        val cardH   = card.widthDp * card.heightRatio
        val xTargets = mutableListOf(cardW / 2f)
        val yTargets = mutableListOf(cardH / 2f)
        for (other in activeElements) {
            if (other.id == id || !other.isVisible
                || other is TemplateElement.CardBackground
                || other is TemplateElement.FrameElement) continue
            xTargets += other.x + other.width  / 2f
            yTargets += other.y + other.height / 2f
        }

        val thrX = SNAP_THRESHOLD_PX / scaleX
        val thrY = SNAP_THRESHOLD_PX / scaleY

        // Snap element center-X to nearest target-X
        var adjX = targetX; var snapX: Float? = null; var minDx = thrX
        for (tgt in xTargets) {
            val d = kotlin.math.abs(propCX - tgt)
            if (d < minDx) { minDx = d; adjX = tgt - w / 2f; snapX = tgt }
        }

        // Snap element center-Y to nearest target-Y
        var adjY = targetY; var snapY: Float? = null; var minDy = thrY
        for (tgt in yTargets) {
            val d = kotlin.math.abs(propCY - tgt)
            if (d < minDy) { minDy = d; adjY = tgt - h / 2f; snapY = tgt }
        }

        snapX?.let { snapLinesX.add(it) }
        snapY?.let { snapLinesY.add(it) }

        // Delta from current element position to snapped target
        return (adjX - el.x) to (adjY - el.y)
    }

    /** Draws cyan snap guidelines over the card. */
    private fun drawSnapLines(canvas: Canvas) {
        for (xDp in snapLinesX) canvas.drawLine(xDp * scaleX, 0f, xDp * scaleX, cardHeightPx, snapPaint)
        for (yDp in snapLinesY) canvas.drawLine(0f, yDp * scaleY, cardWidthPx, yDp * scaleY, snapPaint)
    }

    companion object {
        /** Single background thread shared by all preview instances to avoid thread explosion. */
        private val PREVIEW_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor()
        /** Snap threshold in screen pixels — keeps snap zone consistent at ~2mm regardless of zoom. */
        private const val SNAP_THRESHOLD_PX = 12f
    }
}

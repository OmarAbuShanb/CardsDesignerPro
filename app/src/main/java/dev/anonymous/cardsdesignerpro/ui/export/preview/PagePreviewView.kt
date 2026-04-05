package dev.anonymous.cardsdesignerpro.ui.export.preview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dev.anonymous.cardsdesignerpro.data.model.FlipEdge
import dev.anonymous.cardsdesignerpro.data.model.Template
import dev.anonymous.cardsdesignerpro.ui.editor.canvas.TemplateRenderer
import dev.anonymous.cardsdesignerpro.util.PdfExporter

/**
 * Renders a scaled-down preview of one PDF page.
 * When [isMirrored]=true it applies the back-face grid offset;
 * SHORT_EDGE additionally rotates each card 180° (upside-down).
 */
class PagePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var template: Template? = null
    private var layout: PdfExporter.LayoutInfo? = null
    private var isMirrored = false
    private var flipEdge = FlipEdge.LONG_EDGE

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000; style = Paint.Style.FILL
    }
    private val renderer = TemplateRenderer(context)

    fun bind(
        template: Template?,
        layout: PdfExporter.LayoutInfo?,
        isMirrored: Boolean = false,
        flipEdge: FlipEdge = FlipEdge.LONG_EDGE
    ) {
        this.template   = template
        this.layout     = layout
        this.isMirrored = isMirrored
        this.flipEdge   = flipEdge
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val l = layout
        val h = if (l != null) (w * l.pageHeightPt / l.pageWidthPt).toInt() else w
        setMeasuredDimension(w, h.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = template ?: return
        val l = layout ?: return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val scale = viewW / l.pageWidthPt

        // Background + shadow
        canvas.drawRoundRect(RectF(6f, 6f, viewW + 6f, viewH + 6f), 4f, 4f, shadowPaint)
        canvas.drawRect(0f, 0f, viewW, viewH, bgPaint)

        val s = l.settings
        for (row in 0 until l.rows) {
            for (col in 0 until l.columns) {
                // Compute mirrored slot for the back face
                val (drawCol, drawRow) = if (isMirrored) {
                    when (flipEdge) {
                        FlipEdge.LONG_EDGE  -> (l.columns - 1 - col) to row
                        FlipEdge.SHORT_EDGE -> col to (l.rows - 1 - row)
                    }
                } else col to row

                val cardLeft = (l.marginLeftPt  + drawCol * (l.cardWidthPt  + s.horizontalSpacingDp)) * scale
                val cardTop  = (l.marginTopPt   + drawRow * (l.cardHeightPt + s.verticalSpacingDp))  * scale
                val cardW    = l.cardWidthPt  * scale
                val cardH    = l.cardHeightPt * scale

                canvas.save()
                // Short-edge flip → card content is upside-down
                if (isMirrored && flipEdge == FlipEdge.SHORT_EDGE) {
                    canvas.rotate(180f, cardLeft + cardW / 2f, cardTop + cardH / 2f)
                }
                renderer.draw(canvas, t, cardLeft, cardTop, cardW, cardH)
                canvas.restore()
            }
        }
    }
}

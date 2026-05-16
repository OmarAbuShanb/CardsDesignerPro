package dev.anonymous.cardsdesignerpro.app.util

import dev.anonymous.cardsdesignerpro.app.data.model.ExportSettings
import dev.anonymous.cardsdesignerpro.app.data.model.Template
import kotlin.math.ceil

/**
 * Calculates card grid layout for PDF export pages.
 *
 * Given a [Template] (card dimensions / aspect ratio) and [ExportSettings]
 * (page size, rows, columns, spacing), computes the exact card size and
 * margins that center the grid on the page while preserving aspect ratio.
 *
 * Used by both [PdfBoxExporter] (actual export) and the preview UI.
 */
object PdfLayoutCalculator {

    /**
     * Computes a [LayoutInfo] that describes how cards are arranged on a page.
     *
     * Cards are sized to fill the grid while preserving the template's aspect ratio.
     * The grid is centered on the page with equal margins.
     */
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

    /**
     * Describes the card grid layout on a single PDF page.
     */
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

package dev.anonymous.cardsdesignerpro.data.model

import kotlinx.serialization.Serializable

/**
 * Root template model. Version field enables forward-compatible migrations.
 *
 * Dual-side support (v2):
 *  - [isBackSideEnabled] toggles the back face in the editor.
 *  - [backElements] holds the back-face element list (null = feature not yet activated).
 *  - [activeSide] persists which face was last focused in the editor.
 * All new fields are optional with safe defaults → existing templates load unchanged.
 */
@Serializable
data class Template(
    val version: Int = 1,
    val id: String,
    val name: String,
    val card: CardStyle,
    val elements: List<TemplateElement> = emptyList(),
    /** Back-face elements. null = back side not enabled. emptyList = enabled but empty. */
    val backElements: List<TemplateElement>? = null,
    val isBackSideEnabled: Boolean = false,
    /** Last active side in the editor — persisted so reopening restores focus. */
    val activeSide: CardSide = CardSide.FRONT,
    val exportSettings: ExportSettings? = null
)

enum class CardSide { FRONT, BACK }


@Serializable
data class CardStyle(
    /** Width in logical units (density-independent, used for ratio calculations). */
    val widthDp: Float = 320f,
    /** Height-to-width ratio, clamped to [0.2, 2.0]. */
    val heightRatio: Float = 0.6f,
    val backgroundColor: String = "#FFFFFF",
    /** Absolute path inside filesDir, null = no background image. */
    val backgroundImagePath: String? = null,
)

@Serializable
data class ExportSettings(
    val pageSize: PageSize = PageSize.A4,
    /** Card width as a fraction of page width, e.g. 0.4 = 40 %. */
    val cardWidthFraction: Float = 0.4f,
    val horizontalSpacingDp: Float = 8f,
    val verticalSpacingDp: Float = 8f,
    /** Which paper edge the sheet is flipped on for duplex alignment. */
    val flipEdge: FlipEdge = FlipEdge.LONG_EDGE,
    /** When true, export only the front face even if back side is enabled. */
    val exportFrontOnly: Boolean = false,
)

enum class PageSize(
    /** Width in pt at 72 dpi (PDF). */
    val widthPt: Float,
    val heightPt: Float,
    val displayName: String
) {
    A4(595f, 842f, "A4"),
    A5(420f, 595f, "A5"),
    LETTER(612f, 792f, "Letter");
}

/**
 * Controls how back-side cards are mirrored to align with the front when printing duplex.
 *
 * LONG_EDGE  (recommended, most printers): columns reversed per row.
 *   Front [r,c=0] ↔ Back [r, cols-1-c]
 *
 * SHORT_EDGE: rows reversed, columns unchanged.
 *   Front [r=0,c] ↔ Back [rows-1, c]
 */
@Serializable
enum class FlipEdge(val displayName: String) {
    LONG_EDGE("القلب على الحافة الطويلة (موصى به)"),
    SHORT_EDGE("القلب على الحافة القصيرة");
}


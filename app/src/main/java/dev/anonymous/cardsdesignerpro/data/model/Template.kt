package dev.anonymous.cardsdesignerpro.data.model

import kotlinx.serialization.Serializable
import androidx.annotation.StringRes

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
    /** Back-face card style. null = uses front card style. */
    val backCard: CardStyle? = null,
    val elements: List<TemplateElement> = emptyList(),
    /** Back-face elements. null = back side not enabled. emptyList = enabled but empty. */
    val backElements: List<TemplateElement>? = null,
    val isBackSideEnabled: Boolean = false,
    /** Last active side in the editor — persisted so reopening restores focus. */
    val activeSide: CardSide = CardSide.FRONT,
    val exportSettings: ExportSettings? = null,
    val isShortNumbersEnabled: Boolean = false
)

enum class CardSide { FRONT, BACK }

enum class ImageScaleType { FIT_XY, CENTER_CROP }


@Serializable
data class CardStyle(
    /** Width in logical units (density-independent, used for ratio calculations). */
    val widthDp: Float = 320f,
    /** Height-to-width ratio, clamped to [0.2, 2.0]. */
    val heightRatio: Float = 0.6f,
    val backgroundColor: String = "#FFFFFF",
    /** Absolute path inside filesDir, null = no background image. */
    val backgroundImagePath: String? = null,
    /** "FIT_XY", "CENTER_CROP", "FIT_CENTER" etc. (persisted as String for forward config safety). */
    val backgroundImageScaleType: String = ImageScaleType.FIT_XY.name,
    /** Whether Username and Password styles are kept fully in sync */
    val linkCredentialsStyle: Boolean = false,
)

@Serializable
data class ExportSettings(
    val pageSize: PageSize = PageSize.A4,
    /** Index into [CardLayoutPreset.ALL] — default = 55 cards (5×11, recommended). */
    val selectedLayoutIndex: Int = CardLayoutPreset.RECOMMENDED_INDEX,
    val horizontalSpacingDp: Float = 2f,
    val verticalSpacingDp: Float = 2f,
    /** Which paper edge the sheet is flipped on for duplex alignment. */
    val flipEdge: FlipEdge = FlipEdge.LONG_EDGE,
    /** When true, export only the front face even if back side is enabled. */
    val exportFrontOnly: Boolean = false,
    /** Rendering quality — controls bitmap resolution for images, QR codes, and patterns. */
    val quality: ExportQuality = ExportQuality.HIGH,
    /** Adds page numbers to exported pages (only shown when a document has multiple pages). */
    val showPageNumbers: Boolean = false,
) {
    /** Resolves the selected preset, falling back to the recommended one. */
    val selectedPreset: CardLayoutPreset
        get() = CardLayoutPreset.ALL.getOrElse(selectedLayoutIndex) {
            CardLayoutPreset.ALL[CardLayoutPreset.RECOMMENDED_INDEX]
        }
}

/**
 * A preset grid layout for cards on a page.
 *
 * Each preset defines the number of columns and rows.
 * Card size is computed automatically from page dimensions, spacing, and card aspect ratio.
 */
@Serializable
data class CardLayoutPreset(
    val columns: Int,
    val rows: Int,
    val isRecommended: Boolean = false,
) {
    val totalCards: Int get() = columns * rows

    companion object {
        val ALL: List<CardLayoutPreset> = listOf(
            // ── 6 أعمدة ──
            CardLayoutPreset(6, 9),    //  0 → 54
            CardLayoutPreset(6, 10),   //  1 → 60
            CardLayoutPreset(6, 11),   //  2 → 66
            CardLayoutPreset(6, 12),   //  3 → 72
            CardLayoutPreset(6, 13),   //  4 → 78
            // ── 5 أعمدة ──
            CardLayoutPreset(5, 8),    //  5 → 40
            CardLayoutPreset(5, 9),    //  6 → 45
            CardLayoutPreset(5, 10),   //  7 → 50
            CardLayoutPreset(5, 11, isRecommended = true),  //  8 → 55 ★ موصى به
            CardLayoutPreset(5, 12),   //  9 → 60
            CardLayoutPreset(5, 13),   // 10 → 65
            CardLayoutPreset(5, 14),   // 11 → 70
            // ── 4 أعمدة ──
            CardLayoutPreset(4, 9),    // 12 → 36
            CardLayoutPreset(4, 10),   // 13 → 40
            CardLayoutPreset(4, 11),   // 14 → 44
            CardLayoutPreset(4, 12),   // 15 → 48
            CardLayoutPreset(4, 13),   // 16 → 52
            CardLayoutPreset(4, 14),   // 17 → 56
            CardLayoutPreset(4, 15),   // 18 → 60
            // ── 3 أعمدة ──
            CardLayoutPreset(3, 6),    // 19 → 18
            CardLayoutPreset(3, 7),    // 20 → 21
            CardLayoutPreset(3, 8),    // 21 → 24
            CardLayoutPreset(3, 9),    // 22 → 27
        )

        /** Index of the recommended preset (55 cards, 5×11). */
        const val RECOMMENDED_INDEX = 8
    }
}

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
enum class FlipEdge(@param:StringRes val labelRes: Int) {
    LONG_EDGE(dev.anonymous.cardsdesignerpro.R.string.flip_long_edge_desc),
    SHORT_EDGE(dev.anonymous.cardsdesignerpro.R.string.flip_short_edge_desc);
}

/**
 * Controls the rendering resolution for raster content during PDF export.
 *
 * Each content type has an independent dimension cap, because small elements
 * (QR codes) need proportionally more pixels than large ones (card backgrounds,
 * regular images) to appear sharp.
 *
 * **Note:** SVG images used as element images or card backgrounds are rendered
 * as vector graphics directly and are NOT affected by these quality settings.
 *
 * @property renderScale  Base multiplier for bitmap request dimensions.
 * @property maxImageDim  Cap for card backgrounds and regular image elements.
 * @property maxQrDim     Cap for QR code bitmaps (never exceeds 1024 for memory safety).
 */
@Serializable
enum class ExportQuality(
    val renderScale: Float,
    val maxImageDim: Int,
    val maxQrDim: Int,
) {
    /** الجودة الكاملة — أقصى وضوح للطباعة */
    FULL(    renderScale = 4.0f,  maxImageDim = 1536, maxQrDim = 900),
    /** جودة عالية — الافتراضي */
    HIGH(    renderScale = 3.5f,  maxImageDim = 1408, maxQrDim = 712),
    /** جودة جيدة — توازن بين الحجم والجودة */
    GOOD(    renderScale = 3.0f,  maxImageDim = 1280, maxQrDim = 525),
    /** جودة متوسطة — ملفات أصغر */
    MEDIUM(  renderScale = 2.5f,  maxImageDim = 1152, maxQrDim = 338),
    /** جودة منخفضة — أسرع تصدير للمسودات */
    LOW(     renderScale = 2.0f,  maxImageDim = 1024, maxQrDim = 150);
}

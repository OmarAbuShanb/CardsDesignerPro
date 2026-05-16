package dev.anonymous.cardsdesignerpro.app.data.model

import androidx.annotation.StringRes
import dev.anonymous.cardsdesignerpro.app.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Discriminated-union of all element types that can appear on a template card.
 * The `type` field acts as the discriminator for kotlinx.serialization polymorphism.
 */
@Serializable
sealed class TemplateElement {
    abstract val id: String
    abstract val x: Float
    abstract val y: Float
    abstract val width: Float
    abstract val height: Float
    abstract val isVisible: Boolean

    /** Rotation in degrees around element center. */
    abstract val rotation: Float

    // ── Background / Card style (always first, not removable) ────────────────

    @Serializable
    @SerialName("card_background")
    data class CardBackground(
        override val id: String = "card_background",
        override val x: Float = 0f,
        override val y: Float = 0f,
        override val width: Float = 1f,
        override val height: Float = 1f,
        override val isVisible: Boolean = true,
        override val rotation: Float = 0f,
    ) : TemplateElement()

    // ── Text ─────────────────────────────────────────────────────────────────

    @Serializable
    @SerialName("text")
    data class TextElement(
        override val id: String,
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        override val isVisible: Boolean = true,
        override val rotation: Float = 0f,
        val text: String = "",
        val textColor: String = "#000000",
        val bgColor: String? = null,
        val isBold: Boolean = false,
        val fontName: String = "default",
        val textSizeSp: Float = 15f,
        val textStrokeWidth: Float = 0f,
        val textStrokeColor: String = "#000000",
        val textAlign: TextAlign = TextAlign.CENTER,
    ) : TemplateElement()

    // ── Username ─────────────────────────────────────────────────────────────

    @Serializable
    @SerialName("username")
    data class UsernameElement(
        override val id: String,
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        override val isVisible: Boolean = true,
        override val rotation: Float = 0f,
        /** Preview digit count, also used as placeholder length. */
        val digitCount: Int = 12,
        val textColor: String = "#000000",
        val bgColor: String? = null,
        val isBold: Boolean = false,
        val fontName: String = "default",
        val textSizeSp: Float = 30f,
        val isShortVariant: Boolean = false,
        val textStrokeWidth: Float = 0f,
        val textStrokeColor: String = "#000000",
    ) : TemplateElement()

    // ── Password ─────────────────────────────────────────────────────────────

    @Serializable
    @SerialName("password")
    data class PasswordElement(
        override val id: String,
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        override val isVisible: Boolean = true,
        override val rotation: Float = 0f,
        val digitCount: Int = 6,
        val textColor: String = "#000000",
        val bgColor: String? = null,
        val isBold: Boolean = false,
        val fontName: String = "default",
        val textSizeSp: Float = 30f,
        val isShortVariant: Boolean = false,
        val textStrokeWidth: Float = 0f,
        val textStrokeColor: String = "#000000",
    ) : TemplateElement()

    // ── Image ─────────────────────────────────────────────────────────────────

    @Serializable
    @SerialName("image")
    data class ImageElement(
        override val id: String,
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        override val isVisible: Boolean = true,
        override val rotation: Float = 0f,
        /** Path inside filesDir (or "pack:packs/dividers/divider1.png" for assets). */
        val imagePath: String,
        val tintColor: String? = null,
    ) : TemplateElement()

    // ── QR Code ──────────────────────────────────────────────────────────────

    @Serializable
    @SerialName("qr")
    data class QrElement(
        override val id: String,
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        override val isVisible: Boolean = true,
        override val rotation: Float = 0f,
        val host: String = "192.168.1.1",
        /** Optional logo image path inside filesDir. */
        val logoPath: String? = null,
        /** Logo size as a fraction of the QR side length (0.0 – 0.6). */
        val logoSizeFraction: Float = 0.22f,
        val tintLogo: Boolean = false,
        val qrColor: String = "#000000",
        val backgroundColor: String = "#FFFFFF",
        /** Dark pixel shape: "round" | "square" | "circle" */
        val pixelShape: String = "round",
        /** Eye (ball + frame) shape: "round" | "square" | "circle" */
        val eyeShape: String = "round",
        /** Quiet zone padding fraction (0.0 – 0.25). */
        val qrPadding: Float = 0.05f,
        val linkToShortNumbers: Boolean = false,
    ) : TemplateElement()

    // ── Date ─────────────────────────────────────────────────────────────────

    @Serializable
    @SerialName("date")
    data class DateElement(
        override val id: String,
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        override val isVisible: Boolean = true,
        override val rotation: Float = 0f,
        val format: DateFormat = DateFormat.YEAR_MONTH_DAY_SLASH,
        val textColor: String = "#000000",
        val bgColor: String? = null,
        val isBold: Boolean = false,
        val fontName: String = "default",
        val textSizeSp: Float = 15f,
        val textStrokeWidth: Float = 0f,
        val textStrokeColor: String = "#000000",
    ) : TemplateElement()

    // ── Frame / Border ────────────────────────────────────────────────────────

    @Serializable
    @SerialName("frame")
    data class FrameElement(
        override val id: String,
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        override val isVisible: Boolean = true,
        override val rotation: Float = 0f,
        val color: String = "#000000",
        val strokeWidthDp: Float = 2f,
        val cornerRadiusDp: Float = 0f,
        val paddingDp: Float = 8f,
        val isDashed: Boolean = false,
        val dashLengthDp: Float = 8f,
        val dashGapDp: Float = 4f,
        val isDashRounded: Boolean = false,
    ) : TemplateElement()
    // ── Geometric Shape ───────────────────────────────────────────────────────

    @Serializable
    @SerialName("shape")
    data class ShapeElement(
        override val id: String,
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        override val isVisible: Boolean = true,
        override val rotation: Float = 0f,
        /** Fill color ARGB hex. */
        val fillColor: String = "#FFCC0000",
        /** Stroke/border color. */
        val strokeColor: String = "#FF000000",
        /** Stroke width in template dp (0 = no stroke). */
        val strokeWidthDp: Float = 0f,
        /** Corner radius in template dp (0 = sharp corners). */
        val cornerRadiusDp: Float = 0f,
        /** Whether the stroke is drawn as a dashed line. */
        val isDashed: Boolean = false,
        /** Dash segment length in template dp. */
        val dashLengthDp: Float = 10f,
        /** Gap between dashes in template dp. */
        val dashGapDp: Float = 5f,
        /** Whether dash caps are rounded. */
        val isDashRounded: Boolean = false,
    ) : TemplateElement()

    // ── Line ──────────────────────────────────────────────────────────────────

    @Serializable
    @SerialName("line")
    data class LineElement(
        override val id: String,
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        override val isVisible: Boolean = true,
        override val rotation: Float = 0f,
        /** Line color ARGB hex. */
        val color: String = "#000000",
        /** Whether the line endpoints are rounded. */
        val roundedCaps: Boolean = false,
    ) : TemplateElement()
}

// ── Supporting enums ──────────────────────────────────────────────────────────

@Serializable
enum class TextAlign {
    START, CENTER, END
}

enum class DateFormat(val pattern: String, @param:StringRes val displayNameRes: Int) {
    YEAR_MONTH_DAY_SLASH("yyyy/MM/dd", R.string.date_format_year_month_day_slash),
    YEAR_MONTH_DAY_DASH("yyyy-MM-dd", R.string.date_format_year_month_day_dash),
    MONTH_DAY_SLASH("MM/dd", R.string.date_format_month_day_slash),
    MONTH_DAY_DASH("MM-dd", R.string.date_format_month_day_dash);
}

package dev.anonymous.cardsdesignerpro.data.model

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
        /** Dark pixel shape: "square" | "round" | "circle" */
        val pixelShape: String = "square",
        /** Eye (ball + frame) shape: "square" | "round" | "circle" */
        val eyeShape: String = "square",
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
}

// ── Supporting enums ──────────────────────────────────────────────────────────

enum class DateFormat(val pattern: String, val displayName: String) {
    YEAR_MONTH_DAY_SLASH("yyyy/MM/dd", "السنة/الشهر/اليوم"),
    YEAR_MONTH_DAY_DASH("yyyy-MM-dd", "السنة-الشهر-اليوم"),
    MONTH_DAY_SLASH("MM/dd", "الشهر/اليوم"),
    MONTH_DAY_DASH("MM-dd", "الشهر-اليوم");
}

enum class DecorationShape(val displayName: String) {
    STARS_FOUR_POINT("نجوم"),
    DOTS_SMALL("نقاط صغيرة"),
    CIRCLES_HOLLOW("دوائر مفرغة"),
    CUSTOM_IMAGE("تخصيص (إختيار شعار مُفرّغ من المعرض)");
}

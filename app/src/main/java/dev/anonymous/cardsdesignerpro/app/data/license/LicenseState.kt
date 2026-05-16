package dev.anonymous.cardsdesignerpro.app.data.license

/**
 * Overall license status computed from persisted trial/activation data.
 */
enum class LicenseStatus {
    /** App is fully activated with a valid activation code. */
    ACTIVE,
    /** Within the 7-day trial and export count is below the limit. */
    TRIAL_ACTIVE,
    /** Trial period has ended (time-based). */
    TRIAL_EXPIRED,
    /** Trial is still active time-wise but all 15 free exports are used. */
    EXPORT_LIMIT_REACHED,
    /** First launch — no trial info yet. */
    UNKNOWN,
}

/**
 * Premium features that require activation (or are limited during trial).
 */
enum class PremiumFeature {
    PDF_EXPORT,
    IMPORT_TEMPLATES_BACKUP,
    EXPORT_TEMPLATES_BACKUP,
    READY_MADE_ELEMENTS,
    PREMIUM_DEFAULT_TEMPLATE,
    SCREENSHOT_IN_EDITOR,
}

/**
 * Immutable snapshot of the current license state, observed by the UI layer.
 */
data class LicenseState(
    val status: LicenseStatus = LicenseStatus.UNKNOWN,
    val isActivated: Boolean = false,
    val trialStartedAtMillis: Long = 0L,
    val trialEndsAtMillis: Long = 0L,
    val exportSuccessCount: Int = 0,
    val maxTrialExports: Int = AppConstants.MAX_TRIAL_EXPORTS,
) {
    /** Remaining free exports (clamped to 0). */
    val remainingExports: Int
        get() = (maxTrialExports - exportSuccessCount).coerceAtLeast(0)
}

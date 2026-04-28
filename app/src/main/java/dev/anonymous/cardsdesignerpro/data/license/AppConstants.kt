package dev.anonymous.cardsdesignerpro.data.license

/**
 * Centralized constants for the licensing/trial system.
 * Update [DEVELOPER_WHATSAPP_NUMBER] with the real phone number.
 */
object AppConstants {
    /** Developer WhatsApp number (international format, no spaces). */
    const val DEVELOPER_WHATSAPP_NUMBER = "+970597152714"

    /** Free trial duration in days from first launch. */
    const val TRIAL_DURATION_DAYS = 7

    /** Maximum successful PDF exports allowed during the free trial. */
    const val MAX_TRIAL_EXPORTS = 15

    /**
     * Default template directory names that are considered premium.
     * Templates not in this set are free for all users.
     */
    val PREMIUM_TEMPLATE_DIRS: Set<String> = setOf(
        "template6",
        "template7",
        "template8",
        "template9",
        "template10",
        "template11",
        "template12",
        "template13",
    )
}

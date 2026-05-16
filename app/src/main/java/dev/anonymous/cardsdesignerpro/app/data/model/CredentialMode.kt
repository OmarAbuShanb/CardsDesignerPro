package dev.anonymous.cardsdesignerpro.app.data.model

import kotlinx.serialization.Serializable

/**
 * Controls how credential elements (username / password) are presented on cards.
 *
 * - [NORMAL]        — Standard username + password from the main data file.
 * - [SHORT]         — Shortened numbers from ISP scripts; requires a separate short data file at export.
 * - [USERNAME_ONLY] — Only username appears; password elements are disabled.
 */
@Serializable
enum class CredentialMode {
    NORMAL,
    SHORT,
    USERNAME_ONLY
}

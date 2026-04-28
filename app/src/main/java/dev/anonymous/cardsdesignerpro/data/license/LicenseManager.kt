package dev.anonymous.cardsdesignerpro.data.license

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Centralized license gatekeeper — singleton accessed via [getInstance].
 *
 * Persists all licensing data in [SharedPreferences] ("license_prefs").
 * Exposes a reactive [licenseState] StateFlow for UI observation.
 *
 * Usage:
 *   val lm = LicenseManager.getInstance(context)
 *   lm.licenseState.collect { state -> … }
 *   lm.canAccess(PremiumFeature.PDF_EXPORT)
 */
class LicenseManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: LicenseManager? = null

        fun getInstance(context: Context): LicenseManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LicenseManager(context.applicationContext).also { INSTANCE = it }
            }

        // SharedPreferences keys
        private const val PREFS_NAME = "license_prefs"
        private const val KEY_TRIAL_STARTED_AT = "trial_started_at_millis"
        private const val KEY_TRIAL_ENDS_AT = "trial_ends_at_millis"
        private const val KEY_IS_ACTIVATED = "is_activated"
        private const val KEY_ACTIVATION_CODE = "activation_code"
        private const val KEY_EXPORT_SUCCESS_COUNT = "export_success_count"
        private const val KEY_SERVER_SYNCED = "server_synced"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _licenseState = MutableStateFlow(buildState())
    val licenseState: StateFlow<LicenseState> = _licenseState.asStateFlow()

    init {
        refreshState()
    }

    // ── Trial Initialization ─────────────────────────────────────────────────

    /**
     * Whether the app has ever successfully contacted the server.
     * If false, the app MUST block usage until sync completes.
     */
    val hasCompletedServerSync: Boolean
        get() = prefs.getBoolean(KEY_SERVER_SYNCED, false)


    /**
     * Updates trial dates from server response.
     * Called after successful Firebase Cloud Function response.
     */
    fun updateTrialFromServer(trialStartedAtMillis: Long, trialEndsAtMillis: Long, serverExportCount: Int) {
        val localCount = prefs.getInt(KEY_EXPORT_SUCCESS_COUNT, 0)
        val bestCount = maxOf(localCount, serverExportCount)
        prefs.edit()
            .putLong(KEY_TRIAL_STARTED_AT, trialStartedAtMillis)
            .putLong(KEY_TRIAL_ENDS_AT, trialEndsAtMillis)
            .putInt(KEY_EXPORT_SUCCESS_COUNT, bestCount)
            .putBoolean(KEY_SERVER_SYNCED, true)
            .apply()
        refreshState()
    }

    /**
     * Contacts Firebase to register or retrieve trial dates.
     *
     * On first-ever launch, this MUST succeed before the user can use the app.
     * [onSuccess] is called when trial data is received and stored.
     * [onFailure] is called with an error message on failure.
     */
    fun syncTrialWithServer(onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        if (isActivated) {
            onSuccess()
            return
        }

        val deviceHash = DeviceIdProvider.getHashedId(context)
        val data = hashMapOf("deviceHash" to deviceHash)

        FirebaseFunctions.getInstance()
            .getHttpsCallable("registerOrCheckTrial")
            .call(data)
            .addOnSuccessListener { result ->
                val map = result.data as? Map<*, *>
                val startedAt = (map?.get("trialStartedAt") as? Number)?.toLong()
                val endsAt = (map?.get("trialEndsAt") as? Number)?.toLong()
                val serverExportCount = (map?.get("exportCount") as? Number)?.toInt() ?: 0
                if (startedAt != null && endsAt != null) {
                    updateTrialFromServer(startedAt, endsAt, serverExportCount)
                    Log.d("LicenseManager", "Trial synced from server")
                    onSuccess()
                } else {
                    onFailure("Invalid server response")
                }
            }
            .addOnFailureListener { e ->
                Log.w("LicenseManager", "Failed to sync trial: ${e.message}")
                onFailure(e.localizedMessage ?: "Connection error")
            }
    }

    // ── Activation ───────────────────────────────────────────────────────────

    /**
     * Attempts to activate the app with the given [code].
     * Returns true if successful, false if the code is invalid.
     */
    fun activate(code: String): Boolean {
        val deviceHash = DeviceIdProvider.getHashedId(context)
        if (!ActivationCodeVerifier.verify(deviceHash, code)) return false

        prefs.edit()
            .putBoolean(KEY_IS_ACTIVATED, true)
            .putString(KEY_ACTIVATION_CODE, code)
            .apply()
        refreshState()
        return true
    }

    /** Whether the app is currently activated. */
    val isActivated: Boolean
        get() = prefs.getBoolean(KEY_IS_ACTIVATED, false)

    // ── Export Counting ──────────────────────────────────────────────────────

    /**
     * Increments the successful export counter locally.
     * Call ONLY after a PDF has been successfully generated.
     * For trial users, use [incrementServerExportCount] instead.
     */
    fun incrementExportCount() {
        if (isActivated) return
        val current = prefs.getInt(KEY_EXPORT_SUCCESS_COUNT, 0)
        prefs.edit().putInt(KEY_EXPORT_SUCCESS_COUNT, current + 1).apply()
        refreshState()
    }

    /**
     * Increments the export counter on the server (atomic, with server-side cap).
     * For trial users — called BEFORE the export starts.
     * [onSuccess] receives the new count from the server.
     * [onFailure] is called with an error message if offline or limit reached.
     */
    fun incrementServerExportCount(onSuccess: (Int) -> Unit, onFailure: (String) -> Unit) {
        if (isActivated) {
            onSuccess(0)
            return
        }

        val deviceHash = DeviceIdProvider.getHashedId(context)
        val data = hashMapOf("deviceHash" to deviceHash)

        FirebaseFunctions.getInstance()
            .getHttpsCallable("incrementExportCount")
            .call(data)
            .addOnSuccessListener { result ->
                val map = result.data as? Map<*, *>
                val newCount = (map?.get("exportCount") as? Number)?.toInt() ?: 0
                // Sync local count with server
                prefs.edit().putInt(KEY_EXPORT_SUCCESS_COUNT, newCount).apply()
                refreshState()
                Log.d("LicenseManager", "Server export count: $newCount")
                onSuccess(newCount)
            }
            .addOnFailureListener { e ->
                Log.w("LicenseManager", "Failed to increment export count: ${e.message}")
                onFailure(e.localizedMessage ?: "Connection error")
            }
    }

    // ── Feature Gating ───────────────────────────────────────────────────────

    /**
     * Checks if the given [feature] is accessible in the current license state.
     */
    fun canAccess(feature: PremiumFeature): Boolean {
        if (isActivated) return true

        val state = _licenseState.value
        return when (feature) {
            PremiumFeature.PDF_EXPORT ->
                state.status == LicenseStatus.TRIAL_ACTIVE

            // These features are always blocked during trial
            PremiumFeature.IMPORT_TEMPLATES_BACKUP,
            PremiumFeature.EXPORT_TEMPLATES_BACKUP,
            PremiumFeature.READY_MADE_ELEMENTS,
            PremiumFeature.PREMIUM_DEFAULT_TEMPLATE,
            PremiumFeature.SCREENSHOT_IN_EDITOR -> false
        }
    }

    /**
     * Checks if a default template directory is premium (requires activation).
     */
    fun isPremiumTemplate(dirName: String): Boolean =
        dirName in AppConstants.PREMIUM_TEMPLATE_DIRS

    // ── State Computation ────────────────────────────────────────────────────

    private fun refreshState() {
        _licenseState.value = buildState()
    }

    private fun buildState(): LicenseState {
        val activated = prefs.getBoolean(KEY_IS_ACTIVATED, false)
        val trialStarted = prefs.getLong(KEY_TRIAL_STARTED_AT, 0L)
        val trialEnds = prefs.getLong(KEY_TRIAL_ENDS_AT, 0L)
        val exportCount = prefs.getInt(KEY_EXPORT_SUCCESS_COUNT, 0)
        val serverSynced = prefs.getBoolean(KEY_SERVER_SYNCED, false)

        val status = when {
            activated -> LicenseStatus.ACTIVE
            !serverSynced -> LicenseStatus.UNKNOWN
            System.currentTimeMillis() > trialEnds -> LicenseStatus.TRIAL_EXPIRED
            exportCount >= AppConstants.MAX_TRIAL_EXPORTS -> LicenseStatus.EXPORT_LIMIT_REACHED
            else -> LicenseStatus.TRIAL_ACTIVE
        }

        return LicenseState(
            status = status,
            isActivated = activated,
            trialStartedAtMillis = trialStarted,
            trialEndsAtMillis = trialEnds,
            exportSuccessCount = exportCount,
        )
    }
}

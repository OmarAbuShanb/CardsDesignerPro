package dev.anonymous.cardsdesignerpro.ui.export

import android.net.Uri
import dev.anonymous.cardsdesignerpro.data.model.ExportSettings
import dev.anonymous.cardsdesignerpro.data.model.Template
import dev.anonymous.cardsdesignerpro.data.parser.ParseResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Singleton manager to securely hold export data in-memory between ViewModel and Service.
 * Ensures data safely survives configuration changes while avoiding Intent transaction limits
 * (which crash with large ParseResults).
 */
object ExportManager {

    data class ExportRequest(
        val template: Template,
        val parseResult: ParseResult,
        val shortParseResult: ParseResult?,
        val settings: ExportSettings,
        val outputUri: Uri?,
        val frontUri: Uri?,
        val backUri: Uri?,
        val mode: Mode
    ) {
        enum class Mode { SINGLE, DUAL, SEPARATE }
    }

    var currentRequest: ExportRequest? = null

    val isExporting = MutableStateFlow(false)
    val exportProgress = MutableStateFlow(0f)
    
    // SharedFlow is better for one-time events rather than StateFlow
    val exportEvent = MutableSharedFlow<ExportEvent>(replay = 0, extraBufferCapacity = 1)
    
    fun clearEvent() {
        // Try to clear the replay buffer by emitting Idle (or using a buffer approach)
        exportEvent.tryEmit(ExportEvent.Idle)
    }
}

sealed class ExportEvent {
    data class ExportSuccess(val outputUri: Uri) : ExportEvent()
    data class ExportSuccessDual(val frontUri: Uri, val backUri: Uri) : ExportEvent()
    object ExportFailed : ExportEvent()
    object Idle : ExportEvent()
}

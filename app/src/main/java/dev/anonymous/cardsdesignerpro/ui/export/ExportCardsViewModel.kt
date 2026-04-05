package dev.anonymous.cardsdesignerpro.ui.export

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.anonymous.cardsdesignerpro.data.model.ExportSettings
import dev.anonymous.cardsdesignerpro.data.model.FlipEdge
import dev.anonymous.cardsdesignerpro.data.model.PageSize
import dev.anonymous.cardsdesignerpro.data.model.Template
import dev.anonymous.cardsdesignerpro.data.parser.CsvParser
import dev.anonymous.cardsdesignerpro.data.parser.ExcelParser
import dev.anonymous.cardsdesignerpro.data.parser.ParseResult
import dev.anonymous.cardsdesignerpro.data.repository.TemplateRepository
import dev.anonymous.cardsdesignerpro.util.PdfExporter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A single file chosen by the user for export data. */
data class SelectedFile(
    val uri: android.net.Uri,
    val displayName: String,
    val isSupported: Boolean,
    val parseResult: ParseResult? = null,  // null while parsing
    val isParsing: Boolean = false,
)

data class ExportUiState(
    val templates: List<Template> = emptyList(),
    val selectedTemplateIndex: Int = 0,
    val selectedFiles: List<SelectedFile> = emptyList(),
    val combinedParseResult: ParseResult? = null,
    // keep legacy alias for renderState compat
    val settings: ExportSettings = ExportSettings(),
    val layout: PdfExporter.LayoutInfo? = null,
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val event: ExportEvent? = null,
    val hasBackSide: Boolean = false,
    val backLayout: PdfExporter.LayoutInfo? = null,
) {
    /** Convenience: true when at least one file is still being parsed. */
    val isParsingFile: Boolean get() = selectedFiles.any { it.isParsing }
    /** Convenience accessor used by renderState. */
    val parseResult: ParseResult? get() = combinedParseResult
    val fileName: String? get() = selectedFiles.firstOrNull()?.displayName
    val fileUri: android.net.Uri? get() = selectedFiles.firstOrNull()?.uri
}


sealed class ExportEvent {
    data class ExportSuccess(val outputUri: Uri) : ExportEvent()
    data class ExportSuccessDual(val frontUri: Uri, val backUri: Uri) : ExportEvent()
    object ExportFailed : ExportEvent()
}

class ExportCardsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = TemplateRepository(application)
    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    private var exportJob: Job? = null
    private val prefs = application.getSharedPreferences("export_prefs", Context.MODE_PRIVATE)

    init { loadTemplates() }

    private fun loadTemplates() {
        viewModelScope.launch {
            val templates = repo.getAll()
            val lastId = prefs.getString("last_template_id", null)
            val selectedIdx = if (lastId != null)
                templates.indexOfFirst { it.id == lastId }.coerceAtLeast(0) else 0
            val defaultSettings = templates.getOrNull(selectedIdx)?.exportSettings ?: ExportSettings()
            _uiState.value = _uiState.value.copy(
                templates = templates,
                selectedTemplateIndex = selectedIdx,
                settings = defaultSettings,
                hasBackSide = templates.getOrNull(selectedIdx)?.isBackSideEnabled == true
            )
            recalcLayout()
        }
    }

    fun selectTemplate(index: Int) {
        val t = _uiState.value.templates.getOrNull(index) ?: return
        _uiState.value = _uiState.value.copy(
            selectedTemplateIndex = index,
            settings = t.exportSettings ?: ExportSettings(),
            hasBackSide = t.isBackSideEnabled
        )
        recalcLayout()
    }

    fun addFiles(uris: List<Uri>, displayNames: List<String>) {
        val current = _uiState.value.selectedFiles.toMutableList()
        val supported = setOf("csv", "xlsx", "xls")
        uris.forEachIndexed { i, uri ->
            val name = displayNames.getOrElse(i) { uri.lastPathSegment ?: "file" }
            val ext = name.substringAfterLast('.', "").lowercase()
            val isSupported = ext in supported
            val entry = SelectedFile(uri, name, isSupported, isParsing = isSupported)
            current.add(entry)
        }
        _uiState.value = _uiState.value.copy(selectedFiles = current)
        parseNewFiles(uris.filterIndexed { i, _ ->
            val name = displayNames.getOrElse(i) { "" }
            name.substringAfterLast('.', "").lowercase() in supported
        }, displayNames.filterIndexed { i, name ->
            name.substringAfterLast('.', "").lowercase() in supported
        })
    }

    fun removeFile(uri: Uri) {
        val updated = _uiState.value.selectedFiles.filterNot { it.uri == uri }
        _uiState.value = _uiState.value.copy(
            selectedFiles = updated,
            combinedParseResult = combineParseResults(updated)
        )
        recalcLayout()
    }

    /** Legacy single-file API kept for compatibility. */
    fun setFile(uri: Uri, displayName: String) = addFiles(listOf(uri), listOf(displayName))

    private fun parseNewFiles(uris: List<Uri>, names: List<String>) {
        uris.forEachIndexed { i, uri ->
            val name = names.getOrElse(i) { "" }
            viewModelScope.launch {
                val ctx = getApplication<Application>()
                val result = when {
                    name.endsWith(".csv",  ignoreCase = true) -> CsvParser.parse(ctx, uri)
                    name.endsWith(".xlsx", ignoreCase = true) -> ExcelParser.parse(ctx, uri, isXlsx = true)
                    name.endsWith(".xls",  ignoreCase = true) -> ExcelParser.parse(ctx, uri, isXlsx = false)
                    else -> null
                }
                val updatedFiles = _uiState.value.selectedFiles.map { f ->
                    if (f.uri == uri) f.copy(parseResult = result, isParsing = false) else f
                }
                _uiState.value = _uiState.value.copy(
                    selectedFiles = updatedFiles,
                    combinedParseResult = combineParseResults(updatedFiles)
                )
                recalcLayout()
            }
        }
    }

    private fun combineParseResults(files: List<SelectedFile>): ParseResult? {
        val valid = files.filter { it.isSupported && it.parseResult?.isSuccess == true }
        if (valid.isEmpty()) return null
        val first = valid.first().parseResult!!
        if (valid.size == 1) return first
        val combined = valid.flatMap { it.parseResult!!.records }
        return first.copy(records = combined)
    }


    fun updateCardWidthFraction(v: Float) { mutateSettings { it.copy(cardWidthFraction = v) } }
    fun updateHorizontalSpacing(v: Float) { mutateSettings { it.copy(horizontalSpacingDp = v) } }
    fun updateVerticalSpacing(v: Float)   { mutateSettings { it.copy(verticalSpacingDp = v) } }
    fun updatePageSize(p: PageSize)       { mutateSettings { it.copy(pageSize = p) } }
    fun updateFlipEdge(e: FlipEdge)       {
        _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(flipEdge = e))
    }
    fun updateExportFrontOnly(v: Boolean) {
        _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(exportFrontOnly = v))
    }

    fun export(outputUri: Uri) {
        val state   = _uiState.value
        val template = state.templates.getOrNull(state.selectedTemplateIndex) ?: return
        val parse   = state.parseResult ?: return
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            _uiState.value = state.copy(isExporting = true, exportProgress = 0f)
            val ctx = getApplication<Application>()
            val success = runCatching {
                ctx.contentResolver.openOutputStream(outputUri)?.use { stream ->
                    PdfExporter.export(ctx, template, parse, state.settings, stream) { done, total ->
                        _uiState.value = _uiState.value.copy(exportProgress = done.toFloat() / total)
                    }
                }; true
            }.getOrElse { e -> e.printStackTrace(); false }
            finishSingleExport(success, outputUri, template)
        }
    }

    fun exportDual(outputUri: Uri) {
        val state   = _uiState.value
        val template = state.templates.getOrNull(state.selectedTemplateIndex) ?: return
        val parse   = state.parseResult ?: return
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            _uiState.value = state.copy(isExporting = true, exportProgress = 0f)
            val ctx = getApplication<Application>()
            val success = runCatching {
                ctx.contentResolver.openOutputStream(outputUri)?.use { stream ->
                    PdfExporter.exportDual(ctx, template, parse, state.settings, stream) { done, total ->
                        _uiState.value = _uiState.value.copy(exportProgress = done.toFloat() / total)
                    }
                }; true
            }.getOrElse { e -> e.printStackTrace(); false }
            finishSingleExport(success, outputUri, template)
        }
    }

    fun exportSeparate(frontUri: Uri, backUri: Uri) {
        val state   = _uiState.value
        val template = state.templates.getOrNull(state.selectedTemplateIndex) ?: return
        val parse   = state.parseResult ?: return
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            _uiState.value = state.copy(isExporting = true, exportProgress = 0f)
            val ctx = getApplication<Application>()
            val success = runCatching {
                val fs = ctx.contentResolver.openOutputStream(frontUri)
                val bs = ctx.contentResolver.openOutputStream(backUri)
                if (fs != null && bs != null) {
                    fs.use { f -> bs.use { b ->
                        PdfExporter.exportSeparate(ctx, template, parse, state.settings, f, b) { done, total ->
                            _uiState.value = _uiState.value.copy(exportProgress = done.toFloat() / total)
                        }
                    }}
                }; true
            }.getOrElse { e -> e.printStackTrace(); false }
            repo.save(template.copy(exportSettings = state.settings))
            prefs.edit().putString("last_template_id", template.id).apply()
            _uiState.value = _uiState.value.copy(
                isExporting = false,
                event = if (success == true) ExportEvent.ExportSuccessDual(frontUri, backUri)
                        else ExportEvent.ExportFailed
            )
        }
    }

    fun consumeEvent() { _uiState.value = _uiState.value.copy(event = null) }
    fun cancelExport() { exportJob?.cancel() }

    val selectedTemplate: Template?
        get() = uiState.value.let { it.templates.getOrNull(it.selectedTemplateIndex) }

    private suspend fun finishSingleExport(success: Boolean?, outputUri: Uri, template: Template) {
        if (success == true) {
            repo.save(template.copy(exportSettings = _uiState.value.settings))
            prefs.edit().putString("last_template_id", template.id).apply()
            _uiState.value = _uiState.value.copy(isExporting = false, event = ExportEvent.ExportSuccess(outputUri))
        } else {
            _uiState.value = _uiState.value.copy(isExporting = false, event = ExportEvent.ExportFailed)
        }
    }

    private fun mutateSettings(transform: (ExportSettings) -> ExportSettings) {
        _uiState.value = _uiState.value.copy(settings = transform(_uiState.value.settings))
        recalcLayout()
    }

    private fun recalcLayout() {
        val state    = _uiState.value
        val template = state.templates.getOrNull(state.selectedTemplateIndex) ?: return
        val layout   = PdfExporter.calculateLayout(template, state.settings)
        val backLayout = if (template.isBackSideEnabled) layout else null
        _uiState.value = _uiState.value.copy(layout = layout, backLayout = backLayout)
    }
}

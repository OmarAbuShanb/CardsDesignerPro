package dev.anonymous.cardsdesignerpro.ui.export

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.anonymous.cardsdesignerpro.data.model.ExportQuality
import dev.anonymous.cardsdesignerpro.data.model.ExportSettings
import dev.anonymous.cardsdesignerpro.data.model.FlipEdge
import dev.anonymous.cardsdesignerpro.data.model.PageSize
import dev.anonymous.cardsdesignerpro.data.model.Template
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.data.parser.CsvParser
import dev.anonymous.cardsdesignerpro.data.parser.ExcelParser
import dev.anonymous.cardsdesignerpro.data.parser.ParseResult
import dev.anonymous.cardsdesignerpro.data.parser.PdfParser
import dev.anonymous.cardsdesignerpro.data.repository.TemplateRepository
import dev.anonymous.cardsdesignerpro.util.PdfExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A single file chosen by the user for export data. */
data class SelectedFile(
    val uri: Uri,
    val displayName: String,
    val isSupported: Boolean,
    val parseResult: ParseResult? = null,  // null while parsing
    val isParsing: Boolean = false,
)

/**
 * A record whose username/password digit length doesn't match the template's [digitCount].
 * [recordIndex] is 1-based — it reflects the user's position in the merged data list,
 * not the physical row number in the original spreadsheet.
 */
data class MismatchedRecord(
    val recordIndex: Int,
    val username: String?,
    val password: String?,
    val expectedUserLen: Int?,
    val expectedPassLen: Int?,
)

data class ExportUiState(
    val templates: List<Template> = emptyList(),
    val selectedTemplateIndex: Int = 0,
    val selectedFiles: List<SelectedFile> = emptyList(),
    val combinedParseResult: ParseResult? = null,
    val selectedShortFiles: List<SelectedFile> = emptyList(),
    val combinedShortParseResult: ParseResult? = null,
    // keep legacy alias for renderState compat
    val settings: ExportSettings = ExportSettings(),
    val layout: PdfExporter.LayoutInfo? = null,
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val event: ExportEvent? = null,
    val hasBackSide: Boolean = false,
    val backLayout: PdfExporter.LayoutInfo? = null,
    val isShortNumbersEnabled: Boolean = false,
) {
    /** Convenience: true when at least one file is still being parsed. */
    val isParsingFile: Boolean get() = selectedFiles.any { it.isParsing }

    /** Convenience accessor used by renderState. */
    val parseResult: ParseResult? get() = combinedParseResult
}


class ExportCardsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = TemplateRepository(application)
    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    private val prefs = application.getSharedPreferences("export_prefs", Context.MODE_PRIVATE)
    
    /** Flag to prevent re-processing the same intent (e.g. during system recreation). */
    private var _isIntentProcessed = false
    var isIntentProcessed: Boolean
        get() = _isIntentProcessed
        set(value) { _isIntentProcessed = value }

    enum class PendingExportMode { SINGLE_OR_DUAL, SEPARATE }

    private var pendingExportMode: PendingExportMode? = null

    init {
        loadTemplates()
        observeExportManager()
    }

    private fun observeExportManager() {
        viewModelScope.launch {
            ExportManager.isExporting.collect { isExp ->
                _uiState.value = _uiState.value.copy(isExporting = isExp)
            }
        }
        viewModelScope.launch {
            ExportManager.exportProgress.collect { prog ->
                _uiState.value = _uiState.value.copy(exportProgress = prog)
            }
        }
        viewModelScope.launch {
            ExportManager.exportEvent.collect { ev ->
                if (ev !is ExportEvent.Idle) {
                    _uiState.value = _uiState.value.copy(event = ev)
                }
            }
        }
    }

    private fun loadTemplates() {
        viewModelScope.launch {
            val templates = repo.getAll()
            val lastId = prefs.getString("last_template_id", null)
            val selectedIdx = if (lastId != null)
                templates.indexOfFirst { it.id == lastId }.coerceAtLeast(0) else 0
            val t = templates.getOrNull(selectedIdx)
            val defaultSettings = t?.exportSettings ?: ExportSettings()
            _uiState.value = _uiState.value.copy(
                templates = templates,
                selectedTemplateIndex = selectedIdx,
                settings = defaultSettings,
                hasBackSide = t?.isBackSideEnabled == true,
                isShortNumbersEnabled = t?.isShortNumbersEnabled == true
            )
            recalcLayout()
        }
    }

    fun selectTemplate(index: Int) {
        val t = _uiState.value.templates.getOrNull(index) ?: return
        _uiState.value = _uiState.value.copy(
            selectedTemplateIndex = index,
            settings = t.exportSettings ?: ExportSettings(),
            hasBackSide = t.isBackSideEnabled,
            isShortNumbersEnabled = t.isShortNumbersEnabled
        )
        recalcLayout()
    }

    fun addFiles(uris: List<Uri>, displayNames: List<String>, isShort: Boolean = false) {
        val current =
            if (isShort) _uiState.value.selectedShortFiles.toMutableList() else _uiState.value.selectedFiles.toMutableList()
        val supported = setOf("csv", "xlsx", "xls", "pdf")
        uris.forEachIndexed { i, uri ->
            val name = displayNames.getOrElse(i) { uri.lastPathSegment ?: "file" }
            val ext = name.substringAfterLast('.', "").lowercase()
            val isSupported = ext in supported
            val entry = SelectedFile(uri, name, isSupported, isParsing = isSupported)
            current.add(entry)
        }
        _uiState.value =
            if (isShort) _uiState.value.copy(selectedShortFiles = current) else _uiState.value.copy(
                selectedFiles = current
            )
        parseNewFiles(uris.filterIndexed { i, _ ->
            val name = displayNames.getOrElse(i) { "" }
            name.substringAfterLast('.', "").lowercase() in supported
        }, displayNames.filterIndexed { _, name ->
            name.substringAfterLast('.', "").lowercase() in supported
        }, isShort)
    }

    fun removeFile(uri: Uri, isShort: Boolean = false) {
        if (isShort) {
            val updated = _uiState.value.selectedShortFiles.filterNot { it.uri == uri }
            _uiState.value = _uiState.value.copy(
                selectedShortFiles = updated,
                combinedShortParseResult = combineParseResults(updated)
            )
        } else {
            val updated = _uiState.value.selectedFiles.filterNot { it.uri == uri }
            _uiState.value = _uiState.value.copy(
                selectedFiles = updated,
                combinedParseResult = combineParseResults(updated)
            )
        }
        recalcLayout()
    }

    fun setFilesOrder(files: List<SelectedFile>, isShort: Boolean = false) {
        if (isShort) {
            _uiState.value = _uiState.value.copy(
                selectedShortFiles = files,
                combinedShortParseResult = combineParseResults(files)
            )
        } else {
            _uiState.value = _uiState.value.copy(
                selectedFiles = files,
                combinedParseResult = combineParseResults(files)
            )
        }
        recalcLayout()
    }

    private fun parseNewFiles(uris: List<Uri>, names: List<String>, isShort: Boolean = false) {
        uris.forEachIndexed { i, uri ->
            val name = names.getOrElse(i) { "" }
            viewModelScope.launch {
                val ctx = getApplication<Application>()
                val result = when {
                    name.endsWith(".csv", ignoreCase = true) -> CsvParser.parse(ctx, uri)
                    name.endsWith(".xlsx", ignoreCase = true) -> ExcelParser.parse(
                        ctx,
                        uri,
                        isXlsx = true
                    )

                    name.endsWith(".xls", ignoreCase = true) -> ExcelParser.parse(
                        ctx,
                        uri,
                        isXlsx = false
                    )

                    name.endsWith(".pdf", ignoreCase = true) -> PdfParser.parse(ctx, uri)

                    else -> null
                }
                if (isShort) {
                    val updatedFiles = _uiState.value.selectedShortFiles.map { f ->
                        if (f.uri == uri) f.copy(parseResult = result, isParsing = false) else f
                    }
                    _uiState.value = _uiState.value.copy(
                        selectedShortFiles = updatedFiles,
                        combinedShortParseResult = combineParseResults(updatedFiles)
                    )
                } else {
                    val updatedFiles = _uiState.value.selectedFiles.map { f ->
                        if (f.uri == uri) f.copy(parseResult = result, isParsing = false) else f
                    }
                    _uiState.value = _uiState.value.copy(
                        selectedFiles = updatedFiles,
                        combinedParseResult = combineParseResults(updatedFiles)
                    )
                }
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


    fun updateCardLayout(index: Int) {
        mutateSettings { it.copy(selectedLayoutIndex = index) }
    }

    fun updateHorizontalSpacing(v: Float) {
        mutateSettings { it.copy(horizontalSpacingDp = v) }
    }

    fun updateVerticalSpacing(v: Float) {
        mutateSettings { it.copy(verticalSpacingDp = v) }
    }

    fun updatePageSize(p: PageSize) {
        mutateSettings { it.copy(pageSize = p) }
    }

    fun updateQuality(q: ExportQuality) {
        _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(quality = q))
    }

    fun updateShowPageNumbers(enabled: Boolean) {
        mutateSettings { it.copy(showPageNumbers = enabled) }
    }

    fun updateFlipEdge(e: FlipEdge) {
        _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(flipEdge = e))
    }

    fun updateExportFrontOnly(v: Boolean) {
        _uiState.value =
            _uiState.value.copy(settings = _uiState.value.settings.copy(exportFrontOnly = v))
    }

    fun export(outputUri: Uri) {
        if (ExportManager.isExporting.value) return
        val state = _uiState.value
        val template = state.templates.getOrNull(state.selectedTemplateIndex) ?: return
        val parse = state.parseResult ?: return

        ExportManager.currentRequest = ExportManager.ExportRequest(
            template = template,
            parseResult = parse,
            shortParseResult = state.combinedShortParseResult,
            settings = state.settings,
            outputUri = outputUri,
            frontUri = null,
            backUri = null,
            mode = ExportManager.ExportRequest.Mode.SINGLE
        )

        startExportService(template)
    }

    fun exportDual(outputUri: Uri) {
        if (ExportManager.isExporting.value) return
        val state = _uiState.value
        val template = state.templates.getOrNull(state.selectedTemplateIndex) ?: return
        val parse = state.parseResult ?: return

        ExportManager.currentRequest = ExportManager.ExportRequest(
            template = template,
            parseResult = parse,
            shortParseResult = state.combinedShortParseResult,
            settings = state.settings,
            outputUri = outputUri,
            frontUri = null,
            backUri = null,
            mode = ExportManager.ExportRequest.Mode.DUAL
        )

        startExportService(template)
    }

    fun exportSeparate(frontUri: Uri, backUri: Uri) {
        if (ExportManager.isExporting.value) return
        val state = _uiState.value
        val template = state.templates.getOrNull(state.selectedTemplateIndex) ?: return
        val parse = state.parseResult ?: return

        ExportManager.currentRequest = ExportManager.ExportRequest(
            template = template,
            parseResult = parse,
            shortParseResult = state.combinedShortParseResult,
            settings = state.settings,
            outputUri = null,
            frontUri = frontUri,
            backUri = backUri,
            mode = ExportManager.ExportRequest.Mode.SEPARATE
        )

        startExportService(template)
    }

    fun setPendingExportMode(mode: PendingExportMode) {
        pendingExportMode = mode
    }

    fun consumePendingExportMode(): PendingExportMode? {
        val mode = pendingExportMode
        pendingExportMode = null
        return mode
    }

    private fun startExportService(template: Template) {
        val ctx = getApplication<Application>()
        val intent = android.content.Intent(ctx, PdfExportService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(ctx, intent)

        viewModelScope.launch {
            repo.save(template.copy(exportSettings = _uiState.value.settings))
            prefs.edit { putString("last_template_id", template.id) }
        }
    }

    fun consumeEvent() {
        _uiState.value = _uiState.value.copy(event = null)
        ExportManager.clearEvent()
    }

    val selectedTemplate: Template?
        get() = uiState.value.let { it.templates.getOrNull(it.selectedTemplateIndex) }

    private fun mutateSettings(transform: (ExportSettings) -> ExportSettings) {
        _uiState.value = _uiState.value.copy(settings = transform(_uiState.value.settings))
        recalcLayout()
    }

    /**
     * Validates that each record's username/password length matches the template's [digitCount].
     * Returns a list of [MismatchedRecord] for records that don't match (empty = all OK).
     * [recordIndex] is the 1-based position of the user in the final data list (not the Excel row).
     */
    fun validateDigitLengths(): List<MismatchedRecord> {
        val state = _uiState.value
        val template = state.templates.getOrNull(state.selectedTemplateIndex) ?: return emptyList()
        val parse = state.combinedParseResult ?: return emptyList()

        val userEl = template.elements
            .filterIsInstance<TemplateElement.UsernameElement>()
            .firstOrNull { !it.isShortVariant }
        val passEl = template.elements
            .filterIsInstance<TemplateElement.PasswordElement>()
            .firstOrNull { !it.isShortVariant }

        // If the template has no username/password elements, skip validation
        if (userEl == null && passEl == null) return emptyList()

        val expectedUserLen = userEl?.digitCount
        val expectedPassLen = passEl?.digitCount

        return parse.records.mapIndexedNotNull { index, record ->
            val username = parse.usernameColumn?.let { record[it] }
            val password = parse.passwordColumn?.let { record[it] }
            val userMismatch =
                username != null && expectedUserLen != null && username.length != expectedUserLen
            val passMismatch =
                password != null && expectedPassLen != null && password.length != expectedPassLen
            if (userMismatch || passMismatch) {
                MismatchedRecord(
                    recordIndex = index + 1,
                    username = username,
                    password = password,
                    expectedUserLen = expectedUserLen,
                    expectedPassLen = expectedPassLen
                )
            } else null
        }
    }

    private fun recalcLayout() {
        val state = _uiState.value
        val template = state.templates.getOrNull(state.selectedTemplateIndex) ?: return
        val layout = PdfExporter.calculateLayout(template, state.settings)
        val backLayout = if (template.isBackSideEnabled) layout else null
        _uiState.value = _uiState.value.copy(layout = layout, backLayout = backLayout)
    }
}

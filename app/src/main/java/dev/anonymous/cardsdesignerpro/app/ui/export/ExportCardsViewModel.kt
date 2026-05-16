package dev.anonymous.cardsdesignerpro.app.ui.export

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.anonymous.cardsdesignerpro.app.data.model.CredentialMode
import dev.anonymous.cardsdesignerpro.app.data.model.ExportQuality
import dev.anonymous.cardsdesignerpro.app.data.model.CardLayoutPreset
import dev.anonymous.cardsdesignerpro.app.data.model.ExportSettings
import dev.anonymous.cardsdesignerpro.app.data.model.FlipEdge
import dev.anonymous.cardsdesignerpro.app.data.model.PageSize
import dev.anonymous.cardsdesignerpro.app.data.model.Template
import dev.anonymous.cardsdesignerpro.app.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.app.data.parser.ColumnMapping
import dev.anonymous.cardsdesignerpro.app.data.parser.ColumnMappingStore
import dev.anonymous.cardsdesignerpro.app.data.parser.CsvParser
import dev.anonymous.cardsdesignerpro.app.data.parser.ExcelParser
import dev.anonymous.cardsdesignerpro.app.data.parser.ParseResult
import dev.anonymous.cardsdesignerpro.app.data.parser.PdfParser
import dev.anonymous.cardsdesignerpro.app.data.repository.TemplateRepository
import dev.anonymous.cardsdesignerpro.app.util.PdfLayoutCalculator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import dev.anonymous.cardsdesignerpro.app.R

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
    val layout: PdfLayoutCalculator.LayoutInfo? = null,
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val event: ExportEvent? = null,
    val hasBackSide: Boolean = false,
    val backLayout: PdfLayoutCalculator.LayoutInfo? = null,
    val credentialMode: CredentialMode = CredentialMode.NORMAL,
    val selectedDirectoryUri: Uri? = null,
    val selectedDirectoryName: String? = null,
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

    /** Event to request column mapping dialog from the Activity. */
    data class ColumnMappingRequest(val uri: Uri, val headers: List<String>, val isShort: Boolean)
    private val _columnMappingEvent = MutableSharedFlow<ColumnMappingRequest>(extraBufferCapacity = 1)
    val columnMappingEvent: SharedFlow<ColumnMappingRequest> = _columnMappingEvent.asSharedFlow()

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
            val dirUriStr = prefs.getString("selected_dir_uri", null)
            val dirName = prefs.getString("selected_dir_name", null)
            val dirUri = dirUriStr?.toUri()
            
            _uiState.value = _uiState.value.copy(
                templates = templates,
                selectedTemplateIndex = selectedIdx,
                settings = defaultSettings,
                hasBackSide = t?.isBackSideEnabled == true,
                credentialMode = t?.credentialMode ?: CredentialMode.NORMAL,
                selectedDirectoryUri = dirUri,
                selectedDirectoryName = dirName
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
            credentialMode = t.credentialMode
        )
        recalcLayout()
    }

    fun addFiles(uris: List<Uri>, displayNames: List<String>, isShort: Boolean = false) {
        val current =
            if (isShort) _uiState.value.selectedShortFiles.toMutableList() else _uiState.value.selectedFiles.toMutableList()
        val supported = setOf("csv", "xlsx", "pdf")
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
                var result = when {
                    name.endsWith(".csv", ignoreCase = true) -> CsvParser.parse(ctx, uri)
                    name.endsWith(".xlsx", ignoreCase = true) -> ExcelParser.parse(ctx, uri)
                    name.endsWith(".pdf", ignoreCase = true) -> PdfParser.parse(ctx, uri)
                    else -> null
                }

                // If auto-detection failed, try saved column mapping
                if (result != null && result.needsColumnMapping && result.headers.isNotEmpty()) {
                    val savedMapping = ColumnMappingStore.load(ctx, result.headers)
                    if (savedMapping != null) {
                        // Re-parse with saved mapping
                        result = when {
                            name.endsWith(".csv", ignoreCase = true) -> CsvParser.parse(ctx, uri, savedMapping)
                            name.endsWith(".xlsx", ignoreCase = true) -> ExcelParser.parse(ctx, uri, savedMapping)
                            else -> result
                        }
                    } else {
                        // No saved mapping — ask user via dialog
                        _columnMappingEvent.tryEmit(
                            ColumnMappingRequest(uri, result.headers, isShort)
                        )
                    }
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

    /**
     * Re-parse a file with the user-selected column mapping.
     * Called after [ColumnMappingDialogFragment] returns a result.
     */
    fun reparseWithMapping(uri: Uri, mapping: ColumnMapping, isShort: Boolean) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            // Find the file's display name
            val files = if (isShort) _uiState.value.selectedShortFiles else _uiState.value.selectedFiles
            val file = files.firstOrNull { it.uri == uri } ?: return@launch
            val name = file.displayName

            val result = when {
                name.endsWith(".csv", ignoreCase = true) -> CsvParser.parse(ctx, uri, mapping)
                name.endsWith(".xlsx", ignoreCase = true) -> ExcelParser.parse(ctx, uri, mapping)
                else -> return@launch
            }

            // Save the mapping for future auto-resolution
            if (result.headers.isNotEmpty()) {
                ColumnMappingStore.save(ctx, result.headers, mapping)
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

    /**
     * Validates that the selected template has the required credential elements
     * for the current [CredentialMode]. Returns an error string resource ID
     * if validation fails, or null if all required elements are present.
     */
    fun validateCredentialElements(): Int? {
        val template = selectedTemplate ?: return null
        val mode = template.credentialMode

        val allElements = template.elements + (template.backElements ?: emptyList())

        val hasUsername = allElements.any { it is TemplateElement.UsernameElement }
        val hasPassword = allElements.any { it is TemplateElement.PasswordElement }

        return when (mode) {
            CredentialMode.NORMAL, CredentialMode.SHORT -> {
                if (!hasUsername || !hasPassword) dev.anonymous.cardsdesignerpro.app.R.string.error_missing_credentials_message
                else null
            }
            CredentialMode.USERNAME_ONLY -> {
                if (!hasUsername) dev.anonymous.cardsdesignerpro.app.R.string.error_missing_username_message
                else null
            }
        }
    }

    /**
     * Validates that the data files have the required columns mapped for the
     * current template's [CredentialMode]. Returns a string resource ID if
     * validation fails, or null if all required columns are present.
     */
    fun validateColumnMapping(): Int? {
        val template = selectedTemplate ?: return null
        val mode = template.credentialMode
        val parse = _uiState.value.combinedParseResult ?: return null

        val hasUsername = parse.usernameColumn != null
        val hasPassword = parse.passwordColumn != null

        // Check if any file still needs column mapping
        val anyNeedsMapping = _uiState.value.selectedFiles.any {
            it.parseResult?.needsColumnMapping == true
        } || _uiState.value.selectedShortFiles.any {
            it.parseResult?.needsColumnMapping == true
        }
        if (anyNeedsMapping) return R.string.error_columns_unmapped_snackbar

        return when (mode) {
            CredentialMode.NORMAL, CredentialMode.SHORT -> {
                if (!hasUsername || !hasPassword) R.string.error_columns_missing_for_template
                else null
            }
            CredentialMode.USERNAME_ONLY -> {
                if (!hasUsername) R.string.error_username_column_missing
                else null
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
        val preset = CardLayoutPreset.ALL.getOrNull(index) ?: return
        mutateSettings { it.copy(layoutColumns = preset.columns, layoutRows = preset.rows) }
    }

    fun updateCardLayoutCustom(columns: Int, rows: Int) {
        mutateSettings { it.copy(layoutColumns = columns, layoutRows = rows) }
    }

    fun setSaveDirectory(uri: Uri?, name: String?) {
        _uiState.value = _uiState.value.copy(
            selectedDirectoryUri = uri,
            selectedDirectoryName = name
        )
        prefs.edit {
            putString("selected_dir_uri", uri?.toString())
            putString("selected_dir_name", name)
        }
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
        val layout = PdfLayoutCalculator.calculateLayout(template, state.settings)
        val backLayout = if (template.isBackSideEnabled) layout else null
        _uiState.value = _uiState.value.copy(layout = layout, backLayout = backLayout)
    }
}

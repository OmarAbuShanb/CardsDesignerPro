package dev.anonymous.cardsdesignerpro.app.ui.unusedcards

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.anonymous.cardsdesignerpro.app.R
import dev.anonymous.cardsdesignerpro.app.data.parser.CsvParser
import dev.anonymous.cardsdesignerpro.app.data.parser.ExcelParser
import dev.anonymous.cardsdesignerpro.app.data.parser.ParseResult
import dev.anonymous.cardsdesignerpro.app.data.parser.PdfParser
import dev.anonymous.cardsdesignerpro.app.data.parser.ActiveUsersExcelParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.floor

data class UnusedCard(
    val username: String,
    val password: String,
    val dateText: String,
    val date: LocalDate?,
    val packageName: String
)

data class ConnectedCard(
    val username: String,
    val creationDateText: String,
    val firstLoginDateText: String,
    val waitingDays: Long,
    val creationDate: LocalDate,
    val packageName: String
)

data class ActiveSoldRecord(
    val username: String,
    val createdDate: LocalDate?,
    val createdDateText: String,
    val firstLoginDate: LocalDate?,
    val firstLoginDateText: String,
    val packageName: String,
    val status: String,
    val saleStatus: String
)

data class DiscoveredPackage(
    val packageName: String,
    val noPasswordCount: Int,
    val filteredUnused: Int,
    val oldestDate: java.time.LocalDate?,
    val newestDate: java.time.LocalDate?
)

data class TempExportFile(
    val uri: Uri,
    val displayName: String,
    val cardCount: Int
)

/**
 * Fingerprint of the last CSV export for anti-tampering verification.
 * Used to allow "Go to Export" for the same data that was already downloaded via CSV.
 */
data class CsvExportFingerprint(
    val packageName: String,
    val soldFileUris: Set<Uri>,
    val activeFileUri: Uri?,
    val cardCount: Int,
    val filterByOldest: Boolean,
    val oldestFilterMonths: Int,
    val startDate: LocalDate?,
    val endDate: LocalDate?
)

data class UnusedCardsUiState(
    val selectedSoldFiles: List<SelectedSoldFile> = emptyList(),
    val selectedActiveFile: SelectedSoldFile? = null,
    val packageOptions: List<String> = emptyList(),
    val selectedPackage: String = "",
    val filterByOldest: Boolean = true,
    val oldestFilterMonths: Int = 3,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val isProcessing: Boolean = false,
    
    // Statistics
    val totalActiveCount: Int = 0,
    val filteredUnusedCount: Int = 0,
    val filteredNoPassCount: Int = 0,
    val numPackages: Int = 0,
    val numSoldFiles: Int = 0,
    val oldestCardDate: LocalDate? = null,
    val newestCardDate: LocalDate? = null,
    val oldestConnectedCards: List<ConnectedCard> = emptyList(),
    val discoveredPackages: List<DiscoveredPackage> = emptyList(),
    val oldestActiveCardDate: LocalDate? = null,
    
    // Warnings & Export state
    val fileWithMultiplePackages: String? = null,
    val isExporting: Boolean = false,
    val isExportActionPending: Boolean = false,
    val exportSuccess: Boolean = false,
    val exportError: String? = null
) {
    val isDataLoaded: Boolean get() = selectedActiveFile != null && selectedActiveFile.parseResult?.isSuccess == true
    val isParsing: Boolean get() = selectedSoldFiles.any { it.isParsing } || (selectedActiveFile?.isParsing == true)
}

class UnusedCardsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UnusedCardsUiState())
    val uiState: StateFlow<UnusedCardsUiState> = _uiState.asStateFlow()

    private val USERNAME_KEYS = setOf("username", "user", "اسم المستخدم", "المستخدم", "يوزر", "الرقم", "رقم")
    private val PASSWORD_KEYS = setOf("password", "pass", "كلمة المرور", "الباسورد", "باس", "كلمة السر", "السر")
    private val DATE_KEYS = setOf("date", "created", "creation", "تاريخ", "تاريخ الانشاء", "تاريخ الإنشاء", "تاريخ إنشاء", "تاريخ انشاء")
    private val PACKAGE_KEYS = setOf("package", "profile", "باقة", "الباقة", "العرض", "العرض/الباقة", "ملف", "بروفايل", "اسم الحزمة", "اسم الحزمه")
    private val CREATED_AT_KEYS_STRICT = setOf("تاريخ إنشاء المستخدم", "تاريخ انشاء المستخدم")
    private val FIRST_LOGIN_KEYS_STRICT = setOf("تاريخ أول اتصال", "تاريخ اول اتصال")
    private val STATUS_KEYS = setOf("الحالة", "status", "state")
    private val SALE_STATUS_KEYS = setOf("حالة البيع", "sale status", "sale")

    private var allMatchedCards: List<UnusedCard> = emptyList()
    private var allConnectedCards: List<ConnectedCard> = emptyList()
    private var allActiveSoldRecords: List<ActiveSoldRecord> = emptyList()
    private var oldestActiveCardDate: LocalDate? = null
    private var currentSoldMap: Map<String, String> = emptyMap()
    private var reprocessJob: Job? = null

    /** Fingerprint of the most recent CSV export — used for the "Go to Export" exception. */
    private var lastCsvExportFingerprint: CsvExportFingerprint? = null

    fun setExportActionPending(pending: Boolean) {
        if (_uiState.value.isExportActionPending == pending) return
        _uiState.value = _uiState.value.copy(isExportActionPending = pending)
    }

    // ── File Management ────────────────────────────────────────────────────────

    fun addSoldFiles(uris: List<Uri>, displayNames: List<String>) {
        val current = _uiState.value.selectedSoldFiles.toMutableList()
        val supported = setOf("csv", "xlsx", "pdf")
        
        uris.forEachIndexed { i, uri ->
            val name = displayNames.getOrElse(i) { uri.lastPathSegment ?: "file" }
            val ext = name.substringAfterLast('.', "").lowercase()
            val isSupported = ext in supported
            if (current.none { it.uri == uri }) {
                current.add(SelectedSoldFile(uri, name, isSupported, isParsing = isSupported))
            }
        }
        
        _uiState.value = _uiState.value.copy(selectedSoldFiles = current.sortedForSoldFilesDisplay())
        // Data changed — invalidate the last CSV fingerprint
        // Trigger async parsing
        uris.forEachIndexed { i, uri ->
            val name = displayNames.getOrElse(i) { "" }
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in supported) {
                parseSoldFile(uri, name)
            }
        }
    }

    fun removeSoldFile(uri: Uri) {
        val updated = _uiState.value.selectedSoldFiles.filterNot { it.uri == uri }
        _uiState.value = _uiState.value.copy(selectedSoldFiles = updated)
        // Data changed — invalidate the last CSV fingerprint
        reprocessMatchingAndFilters()
    }

    fun setActiveFile(uri: Uri, name: String) {
        val ext = name.substringAfterLast('.', "").lowercase()
        val isSupported = ext in setOf("csv", "xlsx", "pdf")
        
        _uiState.value = _uiState.value.copy(
            selectedActiveFile = SelectedSoldFile(uri, name, isSupported, isParsing = isSupported)
        )
        
        if (isSupported) {
            parseActiveFile(uri, name)
        }
        // Data changed — invalidate the last CSV fingerprint
    }

    fun removeActiveFile() {
        _uiState.value = _uiState.value.copy(
            selectedActiveFile = null,
            totalActiveCount = 0,
            filteredUnusedCount = 0,
            numPackages = 0,
            oldestCardDate = null,
            newestCardDate = null
        )
        allMatchedCards = emptyList()
        // Data changed — invalidate the last CSV fingerprint
        reprocessMatchingAndFilters()
    }

    // ── Parsing Logics ─────────────────────────────────────────────────────────

    private fun parseSoldFile(uri: Uri, name: String) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val result = runCatching {
                when {
                    name.endsWith(".csv", ignoreCase = true) -> CsvParser.parse(ctx, uri)
                    name.endsWith(".xlsx", ignoreCase = true) -> ExcelParser.parse(ctx, uri)
                    name.endsWith(".pdf", ignoreCase = true) -> PdfParser.parse(ctx, uri)
                    else -> null
                }
            }.getOrNull()

            val updated = _uiState.value.selectedSoldFiles.map { f ->
                if (f.uri == uri) f.copy(parseResult = result, isParsing = false) else f
            }.sortedForSoldFilesDisplay()

            _uiState.value = _uiState.value.copy(
                selectedSoldFiles = updated
            )
            reprocessMatchingAndFilters()
        }
    }

    private fun parseActiveFile(uri: Uri, name: String) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val result = runCatching {
                if (name.endsWith(".xlsx", ignoreCase = true)) {
                    ActiveUsersExcelParser.parse(ctx, uri)
                } else {
                    ParseResult.error(ctx.getString(R.string.error_file_invalid_data))
                }
            }.getOrElse {
                ParseResult.error(ctx.getString(R.string.error_file_invalid_data))
            }

            _uiState.value = _uiState.value.copy(
                selectedActiveFile = _uiState.value.selectedActiveFile?.copy(parseResult = result, isParsing = false)
            )
            reprocessMatchingAndFilters()
        }
    }

    fun dismissPackageWarning() {
        _uiState.value = _uiState.value.copy(fileWithMultiplePackages = null)
    }

    private fun List<SelectedSoldFile>.sortedForSoldFilesDisplay(): List<SelectedSoldFile> {
        return sortedWith(
            compareByDescending<SelectedSoldFile> { it.hasDisplayError }
                .thenBy { it.displayName }
                .thenBy { it.uri.toString() }
        )
    }

    // ── Matching and Filters Processing ────────────────────────────────────────

    private fun String.normalizeHeader(): String {
        return trim()
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("آ", "ا")
            .lowercase()
    }

    private fun reprocessMatchingAndFilters() {
        reprocessJob?.cancel()
        reprocessJob = viewModelScope.launch {
            delay(200) // Debounce: batch rapid file parse completions
            val state = _uiState.value
            val soldFiles = state.selectedSoldFiles.filter { it.isSupported && it.parseResult?.isSuccess == true }
            val activeFile = state.selectedActiveFile

            // Heavy computation on background thread
            val result = withContext(Dispatchers.Default) {
                computeMatchingData(state, soldFiles, activeFile)
            }

            // Apply results on Main thread (class fields must be updated on Main)
            allActiveSoldRecords = result.activeSoldRecords
            oldestActiveCardDate = result.oldestActiveDate
            allMatchedCards = result.matchedCards
            allConnectedCards = result.connectedCards
            currentSoldMap = result.soldMap

            if (!result.hasActiveData) {
                _uiState.value = _uiState.value.copy(
                    totalActiveCount = 0,
                    filteredUnusedCount = 0,
                    filteredNoPassCount = 0,
                    numPackages = result.packageOpts.size,
                    packageOptions = result.packageOpts,
                    selectedPackage = result.selectedPackage,
                    numSoldFiles = _uiState.value.selectedSoldFiles.size,
                    oldestCardDate = null,
                    newestCardDate = null,
                    oldestConnectedCards = emptyList(),
                    discoveredPackages = emptyList(),
                    oldestActiveCardDate = result.oldestActiveDate
                )
            } else {
                applyFiltersAndStats(result.packageOpts, result.selectedPackage)
            }
        }
    }

    private data class MatchingResult(
        val activeSoldRecords: List<ActiveSoldRecord>,
        val oldestActiveDate: LocalDate?,
        val matchedCards: List<UnusedCard>,
        val connectedCards: List<ConnectedCard>,
        val soldMap: Map<String, String>,
        val packageOpts: List<String>,
        val selectedPackage: String,
        val hasActiveData: Boolean
    )

    private fun computeMatchingData(
        state: UnusedCardsUiState,
        soldFiles: List<SelectedSoldFile>,
        activeFile: SelectedSoldFile?
    ): MatchingResult {
        var packageOpts = emptyList<String>()
        var activeSoldRecords = emptyList<ActiveSoldRecord>()
        var oldestActiveDate: LocalDate? = null

        if (activeFile != null && activeFile.parseResult?.isSuccess == true) {
            val activePr = activeFile.parseResult!!
            val activeUserCol = activePr.usernameColumn ?: activePr.headers.firstOrNull { h -> USERNAME_KEYS.any { it.equals(h.lowercase(), ignoreCase = true) } }
            val activeDateCol = activePr.headers.firstOrNull { h -> DATE_KEYS.any { it.equals(h.lowercase(), ignoreCase = true) } || CREATED_AT_KEYS_STRICT.any { it.normalizeHeader() == h.normalizeHeader() } }
            val activePackageCol = activePr.headers.firstOrNull { h -> PACKAGE_KEYS.any { it.normalizeHeader() == h.normalizeHeader() } }
            val activeFirstLoginCol = activePr.headers.firstOrNull { h -> FIRST_LOGIN_KEYS_STRICT.any { it.normalizeHeader() == h.normalizeHeader() } }
            val activeStatusCol = activePr.headers.firstOrNull { h -> STATUS_KEYS.any { it.normalizeHeader() == h.normalizeHeader() } }
            val activeSaleStatusCol = activePr.headers.firstOrNull { h -> SALE_STATUS_KEYS.any { it.normalizeHeader() == h.normalizeHeader() } }

            val listRecords = mutableListOf<ActiveSoldRecord>()
            if (activeUserCol != null) {
                activePr.records.forEach { record ->
                    val user = record[activeUserCol]?.trim() ?: return@forEach
                    if (user.isEmpty()) return@forEach

                    val statusVal = activeStatusCol?.let { record[it] } ?: ""
                    val lowerStatus = statusVal.lowercase().trim().replace("أ", "ا").replace("إ", "ا")
                    val isActive = lowerStatus == "active" || lowerStatus == "نشط" || lowerStatus == "مفعل" || lowerStatus == "فعال"

                    val saleVal = activeSaleStatusCol?.let { record[it] } ?: ""
                    val lowerSale = saleVal.lowercase().trim().replace("أ", "ا").replace("إ", "ا")
                    val isSold = lowerSale == "sold" || lowerSale == "تم بيعه" || lowerSale == "مباع" || lowerSale == "تم البيع"

                    if (isActive && isSold) {
                        val rawDate = activeDateCol?.let { record[it] } ?: ""
                        val parsedDate = parseArabicOrEnglishDate(rawDate)
                        val rawFirstLogin = activeFirstLoginCol?.let { record[it] } ?: ""
                        val parsedFirstLogin = parseArabicOrEnglishDate(rawFirstLogin)
                        val pack = activePackageCol?.let { record[it]?.trim() } ?: ""
                        listRecords.add(
                            ActiveSoldRecord(
                                username = user,
                                createdDate = parsedDate,
                                createdDateText = rawDate,
                                firstLoginDate = parsedFirstLogin,
                                firstLoginDateText = rawFirstLogin,
                                packageName = if (pack.isEmpty()) "None" else pack,
                                status = statusVal,
                                saleStatus = saleVal
                            )
                        )
                    }
                }
            }

            activeSoldRecords = listRecords
            val uniquePackages = listRecords.map { it.packageName }.filter { it.isNotEmpty() && it != "None" }.distinct().sorted()
            packageOpts = uniquePackages
            oldestActiveDate = listRecords.mapNotNull { it.createdDate }.minOrNull()
        }

        // Auto-select first package
        var newSelectedPackage = state.selectedPackage
        if ((newSelectedPackage.isEmpty() || !packageOpts.contains(newSelectedPackage)) && packageOpts.isNotEmpty()) {
            newSelectedPackage = packageOpts.first()
        } else if (packageOpts.isEmpty()) {
            newSelectedPackage = ""
        }

        // Early return if no valid active data
        if (activeFile == null || activeFile.parseResult?.isSuccess != true) {
            return MatchingResult(
                activeSoldRecords = activeSoldRecords,
                oldestActiveDate = oldestActiveDate,
                matchedCards = emptyList(),
                connectedCards = emptyList(),
                soldMap = emptyMap(),
                packageOpts = packageOpts,
                selectedPackage = newSelectedPackage,
                hasActiveData = false
            )
        }

        // 1. Build a combined map of Sold cards: Username -> Password
        val soldMap = mutableMapOf<String, String>()
        soldFiles.forEach { sf ->
            val pr = sf.parseResult ?: return@forEach
            val userCol = pr.usernameColumn ?: pr.headers.firstOrNull { it.lowercase() in USERNAME_KEYS }
            val passCol = pr.passwordColumn ?: pr.headers.firstOrNull { it.lowercase() in PASSWORD_KEYS }
            if (userCol != null && passCol != null) {
                pr.records.forEach { record: Map<String, String> ->
                    val user = record[userCol]?.trim()
                    val pass = record[passCol]?.trim()
                    if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) {
                        soldMap[user] = pass
                    }
                }
            }
        }

        // 2. Process Matched Unused Cards (Active, Sold, and NO First Login Date)
        val matched = mutableListOf<UnusedCard>()
        activeSoldRecords.forEach { record ->
            val isUnused = record.firstLoginDateText.isEmpty() || record.firstLoginDateText == "—" || record.firstLoginDateText == "-"
            if (isUnused) {
                val pass = soldMap[record.username] ?: ""
                matched.add(
                    UnusedCard(
                        username = record.username,
                        password = pass,
                        dateText = record.createdDateText,
                        date = record.createdDate,
                        packageName = record.packageName
                    )
                )
            }
        }

        // 2.5 Process Connected Cards (Active, Sold, and HAS valid First Login Date)
        val connectedList = mutableListOf<ConnectedCard>()
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.getDefault())
        activeSoldRecords.forEach { record ->
            val hasFirstLogin = record.firstLoginDate != null
            if (hasFirstLogin) {
                val createdDate = record.createdDate ?: return@forEach
                val firstLoginDate = record.firstLoginDate!!
                val waiting = ChronoUnit.DAYS.between(createdDate, firstLoginDate)

                val displayCreated = createdDate.format(dateFormatter)
                val displayFirstLogin = firstLoginDate.format(dateFormatter)

                connectedList.add(
                    ConnectedCard(
                        username = record.username,
                        creationDateText = displayCreated,
                        firstLoginDateText = displayFirstLogin,
                        waitingDays = if (waiting >= 0) waiting else 0L,
                        creationDate = createdDate,
                        packageName = record.packageName
                    )
                )
            }
        }

        return MatchingResult(
            activeSoldRecords = activeSoldRecords,
            oldestActiveDate = oldestActiveDate,
            matchedCards = matched,
            connectedCards = connectedList,
            soldMap = soldMap,
            packageOpts = packageOpts,
            selectedPackage = newSelectedPackage,
            hasActiveData = true
        )
    }

    fun hasMultiplePackagesInFilteredList(): Boolean {
        val list = getFilteredCardsList()
        val packages = list.map { it.packageName }.filter { it.isNotEmpty() && it != "None" }.toSet()
        return packages.size > 1
    }

    private fun applyFiltersAndStats(
        packageOpts: List<String>, 
        selectedPack: String
    ) {
        val state = _uiState.value
        val allActiveSoldRecords = this.allActiveSoldRecords
        val oldestActiveDate = this.oldestActiveCardDate
        
        // Filter by oldest / range (applies to EVERYTHING except connected cards)
        var dateFilteredList = allMatchedCards
        val now = LocalDate.now()
        if (state.filterByOldest) {
            val limitDate = now.minusMonths(state.oldestFilterMonths.toLong())
            dateFilteredList = dateFilteredList.filter { card ->
                card.date == null || card.date.isBefore(limitDate)
            }
        } else {
            val start = state.startDate
            val end = state.endDate
            if (start != null) {
                dateFilteredList = dateFilteredList.filter { card ->
                    card.date == null || !card.date.isBefore(start)
                }
            }
            if (end != null) {
                dateFilteredList = dateFilteredList.filter { card ->
                    card.date == null || !card.date.isAfter(end)
                }
            }
        }

        // Calculate global statistics (ignores package filter)
        val validDates = dateFilteredList.mapNotNull { it.date }
        val oldest = validDates.minOrNull()
        val newest = validDates.maxOrNull()
        val filteredNoPass = dateFilteredList.count { it.password.isEmpty() }

        // Filter connected cards by selected package and get top 10 oldest connected
        var filteredConnected = allConnectedCards
        if (selectedPack.isNotEmpty()) {
            filteredConnected = filteredConnected.filter { it.packageName == selectedPack }
        }
        val top10Connected = filteredConnected
            .sortedBy { it.creationDate }
            .take(10)

        // Calculate Discovered Packages statistics (ignores package filter, applies date filter)
        val discoveredPackagesList = packageOpts.map { packName ->
            val packageFiltered = dateFilteredList.filter { it.packageName == packName }
            val noPasswordCount = packageFiltered.count { it.password.isEmpty() }
            val filteredUnused = packageFiltered.size
            
            val packValidDates = packageFiltered.mapNotNull { it.date }
            val oldestDate = packValidDates.minOrNull()
            val newestDate = packValidDates.maxOrNull()
            
            DiscoveredPackage(packName, noPasswordCount, filteredUnused, oldestDate, newestDate)
        }

        _uiState.value = state.copy(
            packageOptions = packageOpts,
            selectedPackage = selectedPack,
            totalActiveCount = allMatchedCards.size,
            filteredUnusedCount = dateFilteredList.size,
            filteredNoPassCount = filteredNoPass,
            numPackages = packageOpts.size,
            numSoldFiles = state.selectedSoldFiles.size,
            oldestCardDate = oldest,
            newestCardDate = newest,
            oldestConnectedCards = top10Connected,
            discoveredPackages = discoveredPackagesList,
            oldestActiveCardDate = oldestActiveDate
        )
    }

    // ── Filter Modifiers ───────────────────────────────────────────────────────

    fun setFilterByOldest(enabled: Boolean) {
        if (_uiState.value.filterByOldest == enabled) return
        _uiState.value = _uiState.value.copy(filterByOldest = enabled)
        applyFiltersAndStats(_uiState.value.packageOptions, _uiState.value.selectedPackage)
    }

    fun setOldestFilterMonths(months: Int) {
        if (_uiState.value.oldestFilterMonths == months) return
        _uiState.value = _uiState.value.copy(oldestFilterMonths = months)
        applyFiltersAndStats(_uiState.value.packageOptions, _uiState.value.selectedPackage)
    }

    fun setDateRange(start: LocalDate?, end: LocalDate?) {
        val state = _uiState.value
        if (!state.filterByOldest && state.startDate == start && state.endDate == end) return
        _uiState.value = _uiState.value.copy(
            startDate = start,
            endDate = end,
            filterByOldest = false // Disables the first filter automatically
        )
        applyFiltersAndStats(_uiState.value.packageOptions, _uiState.value.selectedPackage)
    }

    fun clearDateRange() {
        if (_uiState.value.startDate == null && _uiState.value.endDate == null) return
        _uiState.value = _uiState.value.copy(
            startDate = null,
            endDate = null
        )
        applyFiltersAndStats(_uiState.value.packageOptions, _uiState.value.selectedPackage)
    }

    fun setSelectedPackage(pack: String) {
        applyFiltersAndStats(_uiState.value.packageOptions, pack)
    }

    // ── Export and Transfer ────────────────────────────────────────────────────

    fun exportToExcel(context: Context, destinationUri: Uri) {
        _uiState.value = _uiState.value.copy(isExporting = true, exportError = null, exportSuccess = false)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val filteredList = getFilteredCardsList().filter { it.password.isNotEmpty() }
                    context.contentResolver.openOutputStream(destinationUri)?.use { os ->
                        // Excel UTF-8 BOM
                        os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                        os.bufferedWriter(Charsets.UTF_8).use { writer ->
                            // Columns
                            writer.write("\"Username\",\"Password\",\"Package\",\"تاريخ إنشاء المستخدم\"")
                            writer.newLine()
                            filteredList.forEach { card ->
                                writer.write("\"=\"\"${card.username}\"\"\",\"=\"\"${card.password}\"\"\",\"=\"\"${card.packageName}\"\"\",\"=\"\"${card.dateText}\"\"\"")
                                writer.newLine()
                            }
                        }
                    }
                    true
                }
            }
            
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(isExporting = false, exportSuccess = true)
            } else {
                _uiState.value = _uiState.value.copy(
                    isExporting = false, 
                    exportError = result.exceptionOrNull()?.message ?: context.getString(R.string.export_failed_message)
                )
            }
        }
    }

    private fun getExportableCards(packageName: String, maxCards: Int = Int.MAX_VALUE): List<UnusedCard> =
        getFilteredCardsList(packageName)
            .filter { it.password.isNotEmpty() }
            .take(maxCards)

    private fun String.safeFileSegment(): String {
        val safe = trim()
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            .trim('_', '.', '-')
        return safe.ifEmpty { "package" }
    }

    private fun LocalDate.fileDate(): String = format(DateTimeFormatter.ISO_LOCAL_DATE)

    private fun exportPeriodSegment(packageName: String): String {
        val state = _uiState.value
        val exportableDates = getFilteredCardsList(packageName)
            .filter { it.password.isNotEmpty() }
            .mapNotNull { it.date }
        val fallbackFrom = exportableDates.minOrNull()
        val fallbackTo = exportableDates.maxOrNull()

        val fromDate: LocalDate?
        val toDate: LocalDate?
        if (state.filterByOldest) {
            fromDate = fallbackFrom
            toDate = LocalDate.now().minusMonths(state.oldestFilterMonths.toLong())
        } else {
            fromDate = state.startDate ?: fallbackFrom
            toDate = state.endDate ?: fallbackTo
        }

        val fromPart = fromDate?.fileDate() ?: "unknown"
        val toPart = toDate?.fileDate() ?: LocalDate.now().fileDate()
        return "from_${fromPart}_to_${toPart}"
    }

    fun buildUnusedExportFileName(packageName: String): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US))
        return "unused_${packageName.safeFileSegment()}_${exportPeriodSegment(packageName)}_$timestamp.csv"
    }

    fun exportPackageToCsv(context: Context, packageName: String, destinationUri: Uri, maxCards: Int = Int.MAX_VALUE) {
        _uiState.value = _uiState.value.copy(isExporting = true, exportError = null, exportSuccess = false)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val exportList = getExportableCards(packageName, maxCards)

                    context.contentResolver.openOutputStream(destinationUri)?.use { os ->
                        // UTF-8 BOM
                        os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                        os.bufferedWriter(Charsets.UTF_8).use { writer ->
                            // Columns
                            writer.write("\"Username\",\"Password\",\"Package\",\"تاريخ إنشاء المستخدم\"")
                            writer.newLine()
                            exportList.forEach { card ->
                                writer.write("\"=\"\"${card.username}\"\"\",\"=\"\"${card.password}\"\"\",\"=\"\"${card.packageName}\"\"\",\"=\"\"${card.dateText}\"\"\"")
                                writer.newLine()
                            }
                        }
                    }
                    exportList.size
                }
            }
            
            if (result.isSuccess) {
                val exportedCount = result.getOrDefault(0)
                _uiState.value = _uiState.value.copy(isExporting = false, exportSuccess = true)
            } else {
                _uiState.value = _uiState.value.copy(
                    isExporting = false, 
                    exportError = result.exceptionOrNull()?.message ?: context.getString(R.string.export_failed_message)
                )
            }
        }
    }

    fun consumeExportEvent() {
        _uiState.value = _uiState.value.copy(exportSuccess = false, exportError = null)
    }

    fun getFilteredCardsList(targetPackage: String? = null): List<UnusedCard> {
        val state = _uiState.value
        var list = allMatchedCards
        
        // Filter by package
        val packToFilter = targetPackage ?: state.selectedPackage
        if (packToFilter.isNotEmpty() && packToFilter != "None") {
            list = list.filter { it.packageName == packToFilter }
        }

        // Filter by oldest / range
        val now = LocalDate.now()
        if (state.filterByOldest) {
            val limitDate = now.minusMonths(state.oldestFilterMonths.toLong())
            list = list.filter { it.date == null || it.date.isBefore(limitDate) }
        } else {
            val start = state.startDate
            val end = state.endDate
            if (start != null) {
                list = list.filter { it.date == null || !it.date.isBefore(start) }
            }
            if (end != null) {
                list = list.filter { it.date == null || !it.date.isAfter(end) }
            }
        }
        return list
    }

    /**
     * Saves the current filtered list as a temporary CSV inside the app's cache dir.
     * Returns the Uri of this temporary file so it can be passed to ExportCardsActivity.
     */
    suspend fun createTempFileForExport(context: Context, packageName: String, maxCards: Int = Int.MAX_VALUE): TempExportFile? = withContext(Dispatchers.IO) {
        runCatching {
            val filteredList = getExportableCards(packageName, maxCards)
            if (filteredList.isEmpty()) return@runCatching null

            val tempDir = File(context.cacheDir, "temp_exports")
            if (!tempDir.exists()) tempDir.mkdirs()
            val fileName = buildUnusedExportFileName(packageName)
            val tempFile = File(tempDir, fileName)
            
            tempFile.outputStream().bufferedWriter().use { writer ->
                writer.write("\uFEFF") // BOM for Excel
                writer.write("\"اسم المستخدم\",\"كلمة السر\",\"الحزمة\"")
                writer.newLine()
                filteredList.forEach { card ->
                    writer.write("\"=\"\"${card.username}\"\"\",\"=\"\"${card.password}\"\"\",\"=\"\"${card.packageName}\"\"\"")
                    writer.newLine()
                }
            }

            TempExportFile(
                uri = Uri.fromFile(tempFile),
                displayName = fileName,
                cardCount = filteredList.size
            )
        }.getOrNull()
    }

    
    // ── Helper Date Parser ─────────────────────────────────────────────────────

    private fun String.normalizeArabicDigits(): String {
        var str = this
        val arabic = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
        val english = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
        for (i in 0..9) {
            str = str.replace(arabic[i], english[i])
        }
        return str
    }

    private fun parseArabicOrEnglishDate(dateStr: String): LocalDate? {
        val normalized = dateStr.normalizeArabicDigits().trim()
        val parts = normalized.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
        if (parts.size < 3) return null

        var year = -1
        var month = -1
        var day = -1

        if (parts[0].length == 4) {
            year = parts[0].toIntOrNull() ?: -1
            month = parts[1].toIntOrNull() ?: -1
            day = parts[2].toIntOrNull() ?: -1
        } else if (parts[2].length == 4) {
            day = parts[0].toIntOrNull() ?: -1
            month = parts[1].toIntOrNull() ?: -1
            year = parts[2].toIntOrNull() ?: -1
        } else {
            val p0 = parts[0].toIntOrNull() ?: -1
            val p2 = parts[2].toIntOrNull() ?: -1
            if (p0 > 31) {
                year = p0 + 2000
                month = parts[1].toIntOrNull() ?: -1
                day = p2
            } else if (p2 > 31) {
                day = p0
                month = parts[1].toIntOrNull() ?: -1
                year = p2 + 2000
            } else {
                if (p0 in 2000..2100) {
                    year = p0
                    month = parts[1].toIntOrNull() ?: -1
                    day = p2
                } else if (p2 in 2000..2100) {
                    day = p0
                    month = parts[1].toIntOrNull() ?: -1
                    year = p2
                } else {
                    year = p2 + 2000
                    month = parts[1].toIntOrNull() ?: -1
                    day = p0
                }
            }
        }

        return try {
            if (year > 0 && month in 1..12 && day in 1..31) {
                LocalDate.of(year, month, day)
            } else null
        } catch (e: Exception) {
            null
        }
    }
}

package dev.anonymous.cardsdesignerpro.app.ui.unusedcards

import android.app.DatePickerDialog
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.documentfile.provider.DocumentFile
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.anonymous.cardsdesignerpro.app.R
import dev.anonymous.cardsdesignerpro.app.databinding.ActivityUnusedCardsBinding
import dev.anonymous.cardsdesignerpro.app.databinding.ItemSelectedFileBinding
import dev.anonymous.cardsdesignerpro.app.databinding.ItemConnectedCardBinding
import dev.anonymous.cardsdesignerpro.app.databinding.ItemDiscoveredPackageBinding
import dev.anonymous.cardsdesignerpro.app.ui.export.ExportCardsActivity
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

class UnusedCardsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnusedCardsBinding
    private val viewModel: UnusedCardsViewModel by viewModels()
    private lateinit var soldAdapter: SelectedSoldFileAdapter
    private var isTransferFlow = false
    private var pendingTransferUri: Uri? = null
    private var lastRenderedSoldFileUris: List<Uri> = emptyList()
    private var lastRenderedSoldFileErrorUris: Set<Uri> = emptySet()

    private val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.getDefault())

    // ── Activity Launchers for SAF ─────────────────────────────────────────────

    private val soldFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val names = uris.map { queryFileName(it) ?: "sold_cards.xlsx" }
        viewModel.addSoldFiles(uris, names)
    }

    private val soldFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val documentFile = DocumentFile.fromTreeUri(this, uri) ?: return@registerForActivityResult
        val supportedExtensions = setOf("pdf", "csv", "xlsx")
        val files = documentFile.listFiles().filter {
            val ext = it.name?.substringAfterLast('.', "")?.lowercase()
            ext in supportedExtensions
        }
        val uris = files.map { it.uri }
        val names = files.map { it.name ?: "unknown" }
        if (uris.isNotEmpty()) {
            viewModel.addSoldFiles(uris, names)
        }
    }

    private val activeFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val name = queryFileName(uri) ?: "active_cards.xlsx"
        viewModel.setActiveFile(uri, name)
    }

    private val excelSaveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        if (isTransferFlow) {
            pendingTransferUri = uri
        }
        viewModel.exportToExcel(this, uri)
    }

    private var pendingPackageNameForCsv: String? = null
    private val packageCsvSaveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        val packageName = pendingPackageNameForCsv
        if (uri != null && packageName != null) {
            viewModel.exportPackageToCsv(this, packageName, uri)
        }
        pendingPackageNameForCsv = null
        finishUnusedExportAction()
    }

    private val exportCardsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        pendingPackageNameForCsv = null
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnusedCardsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupSoldFilesList()
        setupPickers()
        setupFilters()
        observeViewModel()
    }

    // ── UI Setup ──────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupSoldFilesList() {
        soldAdapter = SelectedSoldFileAdapter { uri ->
            viewModel.removeSoldFile(uri)
        }
        binding.rvSoldFiles.layoutManager = LinearLayoutManager(this)
        binding.rvSoldFiles.adapter = soldAdapter
    }

    private fun setupPickers() {
        binding.btnChooseSold.setOnClickListener {
            soldFilesLauncher.launch(arrayOf(
                "text/csv", 
                "text/comma-separated-values",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/pdf"
            ))
        }

        binding.btnChooseSoldFolder.setOnClickListener {
            soldFolderLauncher.launch(null)
        }

        binding.btnChooseActive.setOnClickListener {
            activeFileLauncher.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            ))
        }



        binding.layoutStatFilteredNoPass.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.isDataLoaded) {
                if (state.filteredNoPassCount == 0) {
                    Snackbar.make(binding.root, R.string.error_no_password_cards, Snackbar.LENGTH_LONG).show()
                } else {
                    NoPasswordCardsDialogFragment.newInstance().show(supportFragmentManager, NoPasswordCardsDialogFragment.TAG)
                }
            }
        }

        binding.cardGuideBanner.setOnClickListener {
            val intent = Intent(this, UnusedCardsGuideActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupFilters() {
        val oldestOptions = listOf(
            getString(R.string.filter_older_3_months),
            getString(R.string.filter_older_4_months),
            getString(R.string.filter_older_5_months),
            getString(R.string.filter_older_6_months),
            getString(R.string.filter_older_1_year)
        )
        binding.spinnerOldestFilter.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            oldestOptions
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        binding.spinnerOldestFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val months = when (position) {
                    0 -> 3
                    1 -> 4
                    2 -> 5
                    3 -> 6
                    4 -> 12
                    else -> 3
                }
                viewModel.setOldestFilterMonths(months)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.rgFilterMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rb_filter_oldest) {
                viewModel.setFilterByOldest(true)
                binding.layoutOldestParams.visibility = View.VISIBLE
                binding.layoutPeriodParams.visibility = View.GONE
            } else if (checkedId == R.id.rb_filter_period) {
                viewModel.setFilterByOldest(false)
                binding.layoutOldestParams.visibility = View.GONE
                binding.layoutPeriodParams.visibility = View.VISIBLE
            }
        }

        binding.btnStartDate.setOnClickListener {
            showDatePicker(true)
        }
        binding.btnEndDate.setOnClickListener {
            showDatePicker(false)
        }
        binding.btnClearPeriod.setOnClickListener {
            viewModel.clearDateRange()
            updateDateRangeButtons(null, null)
        }
    }

    private fun showDatePicker(isStart: Boolean) {
        val state = viewModel.uiState.value
        if (!isStart && state.startDate == null) {
            Snackbar.make(binding.root, R.string.error_date_range_missing_start, Snackbar.LENGTH_SHORT).show()
            return
        }

        val defaultDate = if (isStart) {
            state.startDate ?: state.oldestActiveCardDate ?: LocalDate.now()
        } else {
            state.endDate ?: state.startDate?.plusDays(1) ?: LocalDate.now()
        }

        val dialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selected = LocalDate.of(year, month + 1, dayOfMonth)
                var currentStart = state.startDate
                var currentEnd = state.endDate
                if (isStart) {
                    currentStart = selected
                    if (currentEnd != null && !currentEnd.isAfter(currentStart)) {
                        currentEnd = null
                    }
                } else {
                    currentEnd = selected
                }
                viewModel.setDateRange(currentStart, currentEnd)
                updateDateRangeButtons(currentStart, currentEnd)
            },
            defaultDate.year,
            defaultDate.monthValue - 1,
            defaultDate.dayOfMonth
        )

        // Min Date = oldest active card date or 5 years ago if null
        val oldest = state.oldestActiveCardDate ?: LocalDate.now().minusYears(5)
        val minCal = Calendar.getInstance().apply {
            set(oldest.year, oldest.monthValue - 1, oldest.dayOfMonth, 0, 0, 0)
        }
        dialog.datePicker.minDate = minCal.timeInMillis

        // Max Date = today
        val maxCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        dialog.datePicker.maxDate = maxCal.timeInMillis

        if (!isStart) {
            val start = state.startDate
            if (start != null) {
                val startCal = Calendar.getInstance().apply {
                    set(start.year, start.monthValue - 1, start.dayOfMonth, 0, 0, 0)
                    add(Calendar.DAY_OF_YEAR, 1)
                }
                val minTime = if (startCal.timeInMillis < minCal.timeInMillis) minCal.timeInMillis else startCal.timeInMillis
                dialog.datePicker.minDate = minTime
            }
        }

        dialog.show()
    }

    private fun updateDateRangeButtons(start: LocalDate?, end: LocalDate?) {
        binding.btnStartDate.text = if (start != null) {
            start.format(formatter)
        } else {
            getString(R.string.btn_start_date)
        }

        binding.btnEndDate.text = if (end != null) {
            end.format(formatter)
        } else {
            getString(R.string.btn_end_date)
        }

        binding.btnClearPeriod.visibility = if (start != null || end != null) View.VISIBLE else View.GONE
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderUiState(state)
                }
            }
        }
    }

    private fun renderUiState(state: UnusedCardsUiState) {
        val soldFiles = state.selectedSoldFiles
        if (soldFiles.isNotEmpty()) {
            val shouldScrollSoldFilesToTop = shouldScrollSoldFilesToTop(soldFiles)
            binding.rvSoldFiles.visibility = View.VISIBLE
            binding.tvSoldFilesPlaceholder.visibility = View.GONE
            soldAdapter.submitList(soldFiles)
            if (shouldScrollSoldFilesToTop) {
                binding.rvSoldFiles.post {
                    binding.rvSoldFiles.scrollToPosition(0)
                }
            }
        } else {
            binding.rvSoldFiles.visibility = View.GONE
            binding.tvSoldFilesPlaceholder.visibility = View.VISIBLE
            lastRenderedSoldFileUris = emptyList()
            lastRenderedSoldFileErrorUris = emptySet()
        }

        val activeFile = state.selectedActiveFile
        if (activeFile != null) {
            binding.layoutActiveFileContainer.visibility = View.VISIBLE
            binding.tvActiveFilePlaceholder.visibility = View.GONE

            val activeContainer = binding.layoutActiveFileContainer
            val cellBinding = if (activeContainer.childCount > 0) {
                ItemSelectedFileBinding.bind(activeContainer.getChildAt(0))
            } else {
                ItemSelectedFileBinding.inflate(LayoutInflater.from(this), activeContainer, true)
            }
            cellBinding.tvFileName.text = activeFile.displayName
            cellBinding.ivDragHandle.visibility = View.GONE
            cellBinding.btnEditMapping.visibility = View.GONE
            cellBinding.btnRemoveFile.setOnClickListener {
                viewModel.removeActiveFile()
            }
            
            val isParsedAndInvalid = !activeFile.isParsing && activeFile.isSupported && (activeFile.parseResult?.isSuccess == false || activeFile.parseResult?.count == 0)
            cellBinding.tvUnsupported.visibility = if (isParsedAndInvalid) View.VISIBLE else View.GONE
            cellBinding.tvUnsupported.text = if (isParsedAndInvalid) getString(R.string.error_file_invalid_data) else ""

            cellBinding.tvCardCount.text = when {
                activeFile.isParsing -> getString(R.string.label_file_parsing)
                else -> {
                    val count = activeFile.parseResult?.count ?: 0
                    if (count > 0) getString(R.string.label_cards_count, count) else ""
                }
            }
            cellBinding.tvCardCount.alpha = if (activeFile.isParsing) 0.6f else 1f
            cellBinding.root.alpha = if (activeFile.isParsing) 0.6f else 1f
        } else {
            binding.layoutActiveFileContainer.visibility = View.GONE
            binding.layoutActiveFileContainer.removeAllViews()
            binding.tvActiveFilePlaceholder.visibility = View.VISIBLE
        }

        val hasData = state.isDataLoaded
        binding.cardFiltersAndStats.alpha = 1.0f
        setContainerEnabled(binding.cardFiltersAndStats, hasData)
        binding.cardPackageConnected.alpha = 1.0f
        setContainerEnabled(binding.cardPackageConnected, hasData)
        binding.btnEndDate.isEnabled = hasData && state.startDate != null

        if (hasData) {
            binding.layoutStatsGrid.visibility = View.VISIBLE
            binding.tvStatFiltered.text = state.filteredUnusedCount.toString()
            binding.tvStatFilteredNoPass.text = state.filteredNoPassCount.toString()

            // Render Discovered Packages Table
            binding.dividerDiscoveredPackages.visibility = View.VISIBLE
            binding.tvLabelDiscoveredPackages.visibility = View.VISIBLE
            if (state.discoveredPackages.isNotEmpty()) {
                binding.scrollDiscoveredPackages.visibility = View.VISIBLE
                binding.tvNoDiscoveredPackagesPlaceholder.visibility = View.GONE
                
                @Suppress("UNCHECKED_CAST")
                val oldPackages = binding.layoutDiscoveredPackagesContainer.tag as? List<DiscoveredPackage>
                if (oldPackages != state.discoveredPackages) {
                    binding.layoutDiscoveredPackagesContainer.tag = state.discoveredPackages
                    binding.layoutDiscoveredPackagesContainer.removeAllViews()
                    state.discoveredPackages.forEach { pack ->
                        val rowBinding = ItemDiscoveredPackageBinding.inflate(layoutInflater, binding.layoutDiscoveredPackagesContainer, true)
                        rowBinding.tvPackageName.text = pack.packageName
                        val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.getDefault())
                        rowBinding.tvNoPasswordCards.text = pack.noPasswordCount.toString()
                        rowBinding.tvUnusedCards.text = pack.filteredUnused.toString()
                        rowBinding.tvWithPasswordCards.text = (pack.filteredUnused - pack.noPasswordCount).toString()
                        rowBinding.tvOldestDate.text = pack.oldestDate?.format(formatter) ?: "—"
                        rowBinding.tvNewestDate.text = pack.newestDate?.format(formatter) ?: "—"
                        
                        val hasExportableCards = state.selectedSoldFiles.isNotEmpty() &&
                            pack.filteredUnused > pack.noPasswordCount
                        if (!hasExportableCards) {
                            rowBinding.layoutActions.visibility = View.GONE
                            rowBinding.tvDownloadCsvDash.visibility = View.VISIBLE
                        } else {
                            rowBinding.layoutActions.visibility = View.VISIBLE
                            rowBinding.tvDownloadCsvDash.visibility = View.GONE
                            rowBinding.btnDownloadCsv.setOnClickListener {
                                launchCsvSave(pack.packageName)
                            }
                            rowBinding.btnGoToExport.setOnClickListener {
                                navigateToExport(pack.packageName)
                            }
                        }

                        rowBinding.tvNoPasswordCards.setOnClickListener {
                            if (pack.noPasswordCount > 0) {
                                NoPasswordCardsDialogFragment.newInstance(pack.packageName).show(supportFragmentManager, NoPasswordCardsDialogFragment.TAG)
                            } else {
                                Snackbar.make(binding.root, R.string.error_no_password_cards, Snackbar.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            } else {
                binding.scrollDiscoveredPackages.visibility = View.VISIBLE
                binding.tvNoDiscoveredPackagesPlaceholder.visibility = View.VISIBLE
                binding.layoutDiscoveredPackagesContainer.tag = null
                binding.layoutDiscoveredPackagesContainer.removeAllViews()
            }

            // Render Connected Cards Table
            binding.tvLabelConnectedCards.visibility = View.VISIBLE
            if (state.oldestConnectedCards.isNotEmpty()) {
                binding.scrollConnectedCards.visibility = View.VISIBLE
                binding.tvNoConnectedCardsPlaceholder.visibility = View.GONE
                
                @Suppress("UNCHECKED_CAST")
                val oldConnected = binding.layoutConnectedCardsContainer.tag as? List<ConnectedCard>
                if (oldConnected != state.oldestConnectedCards) {
                    binding.layoutConnectedCardsContainer.tag = state.oldestConnectedCards
                    binding.layoutConnectedCardsContainer.removeAllViews()
                    state.oldestConnectedCards.forEach { card ->
                        val rowBinding = ItemConnectedCardBinding.inflate(layoutInflater, binding.layoutConnectedCardsContainer, true)
                        rowBinding.tvUsername.text = card.username
                        rowBinding.tvCreated.text = card.creationDateText
                        rowBinding.tvConnected.text = card.firstLoginDateText
                        rowBinding.tvWaiting.text = getString(R.string.label_days_format, card.waitingDays)
                    }
                }
            } else {
                binding.scrollConnectedCards.visibility = View.VISIBLE
                binding.tvNoConnectedCardsPlaceholder.visibility = View.VISIBLE
                binding.layoutConnectedCardsContainer.tag = null
                binding.layoutConnectedCardsContainer.removeAllViews()
            }
        } else {
            binding.layoutStatsGrid.visibility = View.VISIBLE
            binding.tvStatFiltered.text = getString(R.string.dash)
            binding.tvStatFilteredNoPass.text = getString(R.string.dash)

            binding.dividerDiscoveredPackages.visibility = View.VISIBLE
            binding.tvLabelDiscoveredPackages.visibility = View.VISIBLE
            binding.scrollDiscoveredPackages.visibility = View.VISIBLE
            binding.tvNoDiscoveredPackagesPlaceholder.visibility = View.VISIBLE
            binding.layoutDiscoveredPackagesContainer.tag = null
            binding.layoutDiscoveredPackagesContainer.removeAllViews()

            binding.tvLabelConnectedCards.visibility = View.VISIBLE
            binding.scrollConnectedCards.visibility = View.VISIBLE
            binding.tvNoConnectedCardsPlaceholder.visibility = View.VISIBLE
            binding.layoutConnectedCardsContainer.tag = null
            binding.layoutConnectedCardsContainer.removeAllViews()
        }

        setContainerEnabled(binding.scrollDiscoveredPackages, true)
        setContainerEnabled(binding.scrollConnectedCards, true)
        setContainerEnabled(binding.layoutStatsGrid, true)
        binding.tvLabelDiscoveredPackages.isEnabled = true
        binding.tvLabelConnectedCards.isEnabled = true
        setUnusedExportActionButtonsEnabled(!state.isExportActionPending)

        val packageSpinner = binding.spinnerPackageFilter
        val displayPackageOptions = state.packageOptions.ifEmpty { listOf(getString(R.string.msg_no_data_packages)) }
        val oldOptions = packageSpinner.tag as? List<*>
        if (oldOptions != displayPackageOptions) {
            packageSpinner.tag = displayPackageOptions
            packageSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                displayPackageOptions
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            packageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (state.packageOptions.isNotEmpty() && position < state.packageOptions.size) {
                        viewModel.setSelectedPackage(state.packageOptions[position])
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        val selectedIndex = if (state.packageOptions.isNotEmpty()) {
            state.packageOptions.indexOf(state.selectedPackage).coerceAtLeast(0)
        } else 0
        if (packageSpinner.selectedItemPosition != selectedIndex) {
            packageSpinner.setSelection(selectedIndex)
        }

        updateDateRangeButtons(state.startDate, state.endDate)
        val rbId = if (state.filterByOldest) R.id.rb_filter_oldest else R.id.rb_filter_period
        if (binding.rgFilterMode.checkedRadioButtonId != rbId) {
            binding.rgFilterMode.check(rbId)
        }
        if (state.filterByOldest) {
            binding.layoutOldestParams.visibility = View.VISIBLE
            binding.layoutPeriodParams.visibility = View.GONE
        } else {
            binding.layoutOldestParams.visibility = View.GONE
            binding.layoutPeriodParams.visibility = View.VISIBLE
        }

        state.fileWithMultiplePackages?.let { fileName ->
            viewModel.dismissPackageWarning()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.import_warning_title)
                .setMessage(getString(R.string.error_multiple_packages_in_file, fileName))
                .setPositiveButton(R.string.btn_confirm, null)
                .show()
        }

        val isExporting = state.isExporting

        if (state.exportSuccess) {
            viewModel.consumeExportEvent()
            Snackbar.make(binding.root, R.string.success_export_excel, Snackbar.LENGTH_LONG).show()
            finishUnusedExportAction()

            val uri = pendingTransferUri
            if (isTransferFlow && uri != null) {
                isTransferFlow = false
                pendingTransferUri = null
                val intent = Intent(this, ExportCardsActivity::class.java).apply {
                    putExtra("extra_temp_file_uri", uri.toString())
                }
                startActivity(intent)
            }
        }
        state.exportError?.let { err ->
            viewModel.consumeExportEvent()
            Snackbar.make(binding.root, err, Snackbar.LENGTH_LONG).show()
            isTransferFlow = false
            pendingTransferUri = null
            finishUnusedExportAction()
        }
    }

    private fun shouldScrollSoldFilesToTop(soldFiles: List<SelectedSoldFile>): Boolean {
        val currentUris = soldFiles.map { it.uri }
        val currentErrorUris = soldFiles.filter { it.hasDisplayError }.map { it.uri }.toSet()
        val hasAddedFiles = currentUris.size > lastRenderedSoldFileUris.size
        val hasNewErrors = currentErrorUris.any { it !in lastRenderedSoldFileErrorUris }

        lastRenderedSoldFileUris = currentUris
        lastRenderedSoldFileErrorUris = currentErrorUris

        return hasAddedFiles || hasNewErrors
    }

    private fun setContainerEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                setContainerEnabled(view.getChildAt(i), enabled)
            }
        }
    }

    // ── Helper functions ───────────────────────────────────────────────────────

    private fun beginUnusedExportAction(): Boolean {
        if (viewModel.uiState.value.isExportActionPending) return false
        viewModel.setExportActionPending(true)
        setUnusedExportActionButtonsEnabled(false)
        return true
    }

    private fun finishUnusedExportAction() {
        viewModel.setExportActionPending(false)
        setUnusedExportActionButtonsEnabled(true)
    }

    private fun setUnusedExportActionButtonsEnabled(enabled: Boolean) {
        val container = binding.layoutDiscoveredPackagesContainer
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i)
            val rowBinding = runCatching { ItemDiscoveredPackageBinding.bind(row) }.getOrNull() ?: continue
            rowBinding.btnDownloadCsv.isEnabled = enabled
            rowBinding.btnGoToExport.isEnabled = enabled
            rowBinding.layoutActions.alpha = if (enabled) 1f else 0.45f
        }
    }

    private fun queryFileName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col < 0) null else c.getString(col)
        }

    // ── CSV and PDF Export ──────────────────────────────────────────────────

    /** Launches the SAF file-create dialog for CSV download. */
    private fun launchCsvSave(packageName: String) {
        if (!beginUnusedExportAction()) return
        pendingPackageNameForCsv = packageName
        packageCsvSaveLauncher.launch(viewModel.buildUnusedExportFileName(packageName))
    }

    /** Creates the temp file and navigates to ExportCardsActivity. */
    private fun navigateToExport(packageName: String) {
        if (!beginUnusedExportAction()) return
        lifecycleScope.launch {
            val tempExportFile = viewModel.createTempFileForExport(
                this@UnusedCardsActivity,
                packageName
            )
            if (tempExportFile == null) {
                Snackbar.make(binding.root, R.string.export_failed_message, Snackbar.LENGTH_LONG).show()
                finishUnusedExportAction()
                return@launch
            }

            val intent = Intent(this@UnusedCardsActivity, ExportCardsActivity::class.java).apply {
                putExtra("extra_temp_file_uri", tempExportFile.uri.toString())
                putExtra("extra_temp_file_name", tempExportFile.displayName)
                putExtra("extra_is_unused_cards_export", true)
            }
            pendingPackageNameForCsv = packageName
            exportCardsLauncher.launch(intent)
            finishUnusedExportAction()
        }
    }
}

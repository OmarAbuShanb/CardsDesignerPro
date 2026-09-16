package dev.anonymous.cardsdesignerpro.app.ui.export

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.anonymous.cardsdesignerpro.app.R
import dev.anonymous.cardsdesignerpro.app.data.model.CardLayoutPreset
import dev.anonymous.cardsdesignerpro.app.data.model.ExportQuality
import dev.anonymous.cardsdesignerpro.app.data.model.FlipEdge
import dev.anonymous.cardsdesignerpro.app.data.model.PageSize
import dev.anonymous.cardsdesignerpro.app.databinding.ActivityExportCardsBinding
import dev.anonymous.cardsdesignerpro.app.ui.viewer.PdfViewerActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.edit

class ExportCardsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExportCardsBinding
    private val viewModel: ExportCardsViewModel by viewModels()
    private lateinit var fileAdapter: SelectedFileAdapter
    private lateinit var shortFileAdapter: SelectedFileAdapter
    private val exportPrefs by lazy { getSharedPreferences("export_prefs", MODE_PRIVATE) }

    private var lastRenderedLayoutColumns = -1
    private var lastRenderedLayoutRows = -1

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        val pendingMode = viewModel.consumePendingExportMode() ?: return@registerForActivityResult
        if (!hasNotificationPermission()) {
            Snackbar.make(
                binding.root,
                R.string.notification_permission_denied_export_continues,
                Snackbar.LENGTH_LONG
            ).show()
        }
        doLaunchExport(pendingMode == ExportCardsViewModel.PendingExportMode.SEPARATE)
    }

    companion object {
        private const val PREF_NOTIFICATION_PERMISSION_REQUESTED =
            "pref_notification_permission_requested"
    }

    // ── File pickers ──────────────────────────────────────────────────────────

    private val multiFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        val names = uris.map { queryFileName(it) ?: it.lastPathSegment ?: "file" }
        viewModel.addFiles(uris, names)
    }

    private val multiFilePickerShort = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        val names = uris.map { queryFileName(it) ?: it.lastPathSegment ?: "file" }
        viewModel.addFiles(uris, names, isShort = true)
    }
    private val isUnusedCardsExport: Boolean
        get() = viewModel.uiState.value.isUnusedCardsExport

    /** Directory picker for saving files */
    private val directoryPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
        val name = dir?.name ?: "Selected Folder"
        viewModel.setSaveDirectory(uri, name)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExportCardsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupNotificationPermissionFlow()
        setupCustomLayoutDialogResult()

        setupToolbar()
        setupBottomSheetDrag()
        setupFileList()
        setupFilePicker()
        setupLayoutSpinner()
        setupSpacingSliders()
        setupPageSizeSpinner()
        setupPageNumbersCheckbox()
        setupQualitySpinner()
        setupFlipEdgeToggle()
        setupFrontOnlyCheckbox()
        setupExportButtons()
        observeViewModel()

        checkIntentForDualPreview(intent)
        checkIntentForTempFile(intent)
        handleIncomingFileIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkIntentForDualPreview(intent)
        checkIntentForTempFile(intent)
        handleIncomingFileIntent(intent)
    }

    private fun checkIntentForDualPreview(intent: Intent) {
        if (intent.getBooleanExtra("show_dual_preview", false)) {
            intent.removeExtra("show_dual_preview") // Prevent dialog from showing again on rotation
            val frontStr = intent.getStringExtra("front_uri")
            val backStr = intent.getStringExtra("back_uri")
            if (frontStr != null && backStr != null) {
                showSeparateSuccessDialog(frontStr.toUri(), backStr.toUri())
            }
        }
    }

    private fun checkIntentForTempFile(intent: Intent) {
        val tempFileUriStr = intent.getStringExtra("extra_temp_file_uri")
        val isUnusedCardsExportIntent = intent.getBooleanExtra("extra_is_unused_cards_export", false)
        if (tempFileUriStr != null) {
            intent.removeExtra("extra_temp_file_uri")
            intent.removeExtra("extra_is_unused_cards_export")
            viewModel.setUnusedCardsExport(isUnusedCardsExportIntent)
            val name = intent.getStringExtra("extra_temp_file_name")
            intent.removeExtra("extra_temp_file_name")
            if (name.isNullOrBlank()) return

            val uri = tempFileUriStr.toUri()
            viewModel.addFiles(listOf(uri), listOf(name), isTemporary = true)
        }
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupToolbar() = binding.toolbar.setNavigationOnClickListener { finish() }

    private fun setupBottomSheetDrag() {
        var initialDragY = 0f
        var initialHeight = 0

        // Adjust initial height in landscape to prevent obscuring the whole screen
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            binding.dragHeaderArea.post {
                binding.bottomSheetHost.layoutParams.height = binding.dragHeaderArea.height
                binding.bottomSheetHost.requestLayout()
            }
        }

        binding.dragHeaderArea.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialDragY = event.rawY; initialHeight = binding.bottomSheetHost.height; true
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    val minH =
                        binding.dragHeaderArea.height
                    val maxH = binding.root.height
                    val newH =
                        (initialHeight + (initialDragY - event.rawY)).toInt().coerceIn(minH, maxH)
                    binding.bottomSheetHost.layoutParams.height = newH
                    binding.bottomSheetHost.requestLayout()
                    true
                }

                else -> false
            }
        }
    }

    private fun setupFileList() {
        fileAdapter = SelectedFileAdapter(
            onRemove = { uri -> viewModel.removeFile(uri) },
            onEditMapping = { file -> showMappingDialogForFile(file, isShort = false) }
        )
        shortFileAdapter = SelectedFileAdapter(
            onRemove = { uri -> viewModel.removeFile(uri, isShort = true) },
            onEditMapping = { file -> showMappingDialogForFile(file, isShort = true) }
        )

        val touchHelper = androidx.recyclerview.widget.ItemTouchHelper(object :
            androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN,
                0
            ) {
            override fun onMove(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == androidx.recyclerview.widget.RecyclerView.NO_POSITION || to == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return false
                fileAdapter.swapItems(from, to)
                return true
            }

            override fun onSwiped(
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                direction: Int
            ) {
            }

            override fun clearView(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                fileAdapter.commitDragSession()
            }
        })

        fileAdapter.onStartDrag = {
            fileAdapter.startDragSession()
            touchHelper.startDrag(it)
        }
        fileAdapter.onDropCommit = { newOrder ->
            viewModel.setFilesOrder(newOrder)
        }

        binding.rvSelectedFiles.apply {
            adapter = fileAdapter
            layoutManager = LinearLayoutManager(this@ExportCardsActivity)
            isNestedScrollingEnabled = false
            touchHelper.attachToRecyclerView(this)
        }

        val touchHelperShort = androidx.recyclerview.widget.ItemTouchHelper(object :
            androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN,
                0
            ) {
            override fun onMove(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == androidx.recyclerview.widget.RecyclerView.NO_POSITION || to == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return false
                shortFileAdapter.swapItems(from, to)
                return true
            }

            override fun onSwiped(
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                direction: Int
            ) {
            }

            override fun clearView(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                shortFileAdapter.commitDragSession()
            }
        })

        shortFileAdapter.onStartDrag = {
            shortFileAdapter.startDragSession()
            touchHelperShort.startDrag(it)
        }
        shortFileAdapter.onDropCommit = { newOrder ->
            viewModel.setFilesOrder(newOrder, isShort = true)
        }

        binding.rvSelectedShortFiles.apply {
            adapter = shortFileAdapter
            layoutManager = LinearLayoutManager(this@ExportCardsActivity)
            isNestedScrollingEnabled = false
            touchHelperShort.attachToRecyclerView(this)
        }
    }

    /** Opens the column mapping dialog for a specific file (from the edit button in the file list). */
    private fun showMappingDialogForFile(file: SelectedFile, isShort: Boolean) {
        val parseResult = file.parseResult ?: return
        val headers = parseResult.headers
        if (headers.isEmpty()) return
        val mode = viewModel.uiState.value.credentialMode
        val dialog = ColumnMappingDialogFragment.newInstance(
            uri = file.uri,
            headers = headers,
            isShortFile = isShort,
            currentUsernameCol = parseResult.usernameColumn,
            currentPasswordCol = parseResult.passwordColumn,
            fileName = file.displayName,
            isUsernameOnly = mode == dev.anonymous.cardsdesignerpro.app.data.model.CredentialMode.USERNAME_ONLY
        )
        dialog.show(supportFragmentManager, ColumnMappingDialogFragment.TAG)
    }

    private fun setupFilePicker() {
        binding.btnChooseFile.setOnClickListener {
            if (viewModel.uiState.value.isUnusedCardsExport) {
                Snackbar.make(binding.root, R.string.unused_export_files_locked, Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            multiFilePicker.launch(
                arrayOf(
                    "text/csv", "text/comma-separated-values",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/pdf",
                    "*/*"
                )
            )
        }
        binding.btnChooseShortFile.setOnClickListener {
            if (viewModel.uiState.value.isUnusedCardsExport) {
                Snackbar.make(binding.root, R.string.unused_export_files_locked, Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            multiFilePickerShort.launch(
                arrayOf(
                    "text/csv", "text/comma-separated-values",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/pdf",
                    "*/*"
                )
            )
        }
    }

    private fun setupLayoutSpinner() {
        // Edit button for modifying an active custom layout
        binding.btnEditCustomLayout.setOnClickListener {
            showCustomLayoutDialog()
        }
    }

    /**
     * Rebuilds the layout spinner adapter with the "Custom…" item at position 0
     * followed by all predefined presets. If the current settings use custom
     * (non-predefined) values, position 0 shows those values instead of generic text.
     */
    private fun rebuildLayoutSpinnerAdapter() {
        val presets = CardLayoutPreset.ALL
        val state = viewModel.uiState.value
        val presetIdx = CardLayoutPreset.indexFor(
            state.settings.layoutColumns, state.settings.layoutRows
        )

        val customLabel = if (presetIdx < 0) {
            // Active custom layout — show its values
            val cols = state.settings.layoutColumns
            val rows = state.settings.layoutRows
            getString(R.string.layout_custom_active_format, cols * rows, cols, rows)
        } else {
            getString(R.string.layout_custom_option)
        }

        val labels = mutableListOf(customLabel)
        labels.addAll(presets.map { preset ->
            val suffix =
                if (preset.isRecommended) getString(R.string.label_recommended_suffix) else ""
            getString(
                R.string.layout_preset_format,
                preset.totalCards,
                preset.columns,
                preset.rows
            ) + suffix
        })

        binding.spinnerCardLayout.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    /**
     * Shows the [CustomLayoutDialogFragment] for picking a custom columns/rows layout.
     * Prevents showing duplicate dialogs.
     */
    private fun showCustomLayoutDialog() {
        if (supportFragmentManager.findFragmentByTag(CustomLayoutDialogFragment.TAG) != null) return
        val state = viewModel.uiState.value
        CustomLayoutDialogFragment.newInstance(
            columns = state.settings.layoutColumns,
            rows = state.settings.layoutRows
        ).show(supportFragmentManager, CustomLayoutDialogFragment.TAG)
    }

    /**
     * Listens for results from [CustomLayoutDialogFragment].
     * On confirm → applies custom layout. On dismiss → reverts spinner.
     */
    private fun setupCustomLayoutDialogResult() {
        supportFragmentManager.setFragmentResultListener(
            CustomLayoutDialogFragment.REQUEST_KEY,
            this
        ) { _, bundle ->
            supportFragmentManager.clearFragmentResult(CustomLayoutDialogFragment.REQUEST_KEY)
            val action = bundle.getString(CustomLayoutDialogFragment.RESULT_ACTION)
                ?: return@setFragmentResultListener

            when (action) {
                CustomLayoutDialogFragment.ACTION_CONFIRM -> {
                    val cols = bundle.getInt(CustomLayoutDialogFragment.RESULT_COLUMNS)
                    val rows = bundle.getInt(CustomLayoutDialogFragment.RESULT_ROWS)
                    viewModel.updateCardLayoutCustom(cols, rows)
                }
                CustomLayoutDialogFragment.ACTION_DISMISS -> {
                    syncLayoutSpinnerSelection()
                }
            }
        }
    }

    /** Syncs the layout spinner selection to match the current ViewModel state. */
    private fun syncLayoutSpinnerSelection() {
        val state = viewModel.uiState.value
        val presetIdx = CardLayoutPreset.indexFor(
            state.settings.layoutColumns, state.settings.layoutRows
        )
        val spinnerPos = if (presetIdx >= 0) presetIdx + 1 else 0
        if (binding.spinnerCardLayout.selectedItemPosition != spinnerPos) {
            binding.spinnerCardLayout.setSelection(spinnerPos)
        }
    }

    private fun setupSpacingSliders() {
        binding.stepperHSpacing.onValueChanged = { v ->
            viewModel.updateHorizontalSpacing(v.toFloat())
        }
        binding.stepperVSpacing.onValueChanged = { v ->
            viewModel.updateVerticalSpacing(v.toFloat())
        }
    }

    private fun setupPageSizeSpinner() {
        val sizes = PageSize.entries.toTypedArray()
        binding.spinnerPageSize.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item, sizes.map { it.displayName }).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerPageSize.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                    viewModel.updatePageSize(sizes[pos])

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
    }

    private fun setupQualitySpinner() {
        val qualities = ExportQuality.entries.toTypedArray()
        val labels = qualities.map { getString(it.labelRes()) }
        binding.spinnerQuality.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item, labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerQuality.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    viewModel.updateQuality(qualities[pos])
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
    }

    private fun setupPageNumbersCheckbox() {
        binding.checkboxPageNumbers.setOnCheckedChangeListener { _, checked ->
            viewModel.updateShowPageNumbers(checked)
        }
    }

    /** Maps each [ExportQuality] to its string resource. */
    private fun ExportQuality.labelRes(): Int = when (this) {
        ExportQuality.FULL -> R.string.quality_full
        ExportQuality.HIGH -> R.string.quality_high
        ExportQuality.GOOD -> R.string.quality_good
        ExportQuality.MEDIUM -> R.string.quality_medium
        ExportQuality.LOW -> R.string.quality_low
    }

    private fun setupFlipEdgeToggle() {
        binding.toggleFlipEdge.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val edge =
                if (checkedId == R.id.btn_flip_long) FlipEdge.LONG_EDGE else FlipEdge.SHORT_EDGE
            viewModel.updateFlipEdge(edge)
            updateFlipHint(edge)
        }
        binding.toggleFlipEdge.check(R.id.btn_flip_long)
    }

    private fun updateFlipHint(edge: FlipEdge) {
        binding.tvFlipHint.text = getString(
            if (edge == FlipEdge.LONG_EDGE) R.string.flip_hint_long_edge else R.string.flip_hint_short_edge
        )
    }

    private fun setupFrontOnlyCheckbox() {
        binding.checkboxFrontOnly.setOnCheckedChangeListener { _, checked ->
            viewModel.updateExportFrontOnly(checked)
        }
    }

    private fun setupExportButtons() {
        binding.btnExport.setOnClickListener { handleExportClick(isSeparate = false) }
        binding.btnExportSeparate.setOnClickListener { handleExportClick(isSeparate = true) }
        binding.btnChooseDirectory.setOnClickListener { directoryPicker.launch(null) }
    }

    private fun setupNotificationPermissionFlow() {
        supportFragmentManager.setFragmentResultListener(
            NotificationPermissionRationaleDialogFragment.REQUEST_KEY,
            this
        ) { _, bundle ->
            supportFragmentManager.clearFragmentResult(NotificationPermissionRationaleDialogFragment.REQUEST_KEY)
            val action =
                bundle.getString(NotificationPermissionRationaleDialogFragment.RESULT_ACTION)
                    ?: return@setFragmentResultListener
            val isSeparate =
                bundle.getBoolean(
                    NotificationPermissionRationaleDialogFragment.RESULT_IS_SEPARATE,
                    false
                )

            when (action) {
                NotificationPermissionRationaleDialogFragment.ACTION_REQUEST_PERMISSION ->
                    requestNotificationPermissionThenExport(isSeparate)

                NotificationPermissionRationaleDialogFragment.ACTION_EXPORT_ANYWAY ->
                    doLaunchExport(isSeparate)
            }
        }
    }

    private fun launchExportWithNotificationPermissionGate(isSeparate: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasNotificationPermission()) {
            doLaunchExport(isSeparate)
            return
        }

        if (!shouldShowNotificationPermissionDialog()) {
            doLaunchExport(isSeparate)
            return
        }

        if (supportFragmentManager.findFragmentByTag(NotificationPermissionRationaleDialogFragment.TAG) != null) {
            return
        }

        NotificationPermissionRationaleDialogFragment.newInstance(isSeparate)
            .show(supportFragmentManager, NotificationPermissionRationaleDialogFragment.TAG)
    }

    private fun requestNotificationPermissionThenExport(isSeparate: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasNotificationPermission()) {
            doLaunchExport(isSeparate)
            return
        }

        exportPrefs.edit {
            putBoolean(PREF_NOTIFICATION_PERMISSION_REQUESTED, true)
        }

        viewModel.setPendingExportMode(
            if (isSeparate) {
                ExportCardsViewModel.PendingExportMode.SEPARATE
            } else {
                ExportCardsViewModel.PendingExportMode.SINGLE_OR_DUAL
            }
        )
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun shouldShowNotificationPermissionDialog(): Boolean {
        val hasRequestedBefore =
            exportPrefs.getBoolean(PREF_NOTIFICATION_PERMISSION_REQUESTED, false)
        return !hasRequestedBefore || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Shared guard logic for both export buttons:
     * 1. Block if any file is still being parsed.
     * 2. Block if no valid file data is available.
     * 3. Validate digit lengths — show mismatch dialog if needed.
     * 4. Proceed to launch the PDF saver.
     */
    private fun validateExportPreconditions(): Boolean {
        val state = viewModel.uiState.value

        if (state.templates.isEmpty() || viewModel.selectedTemplate == null) {
            Snackbar.make(binding.root, R.string.error_no_templates, Snackbar.LENGTH_SHORT).show()
            return false
        }

        if (state.isParsingFile) {
            Snackbar.make(binding.root, R.string.error_file_still_parsing, Snackbar.LENGTH_SHORT).show()
            return false
        }

        val parse = state.combinedParseResult
        if (parse == null || parse.count == 0) {
            Snackbar.make(binding.root, R.string.error_no_valid_file, Snackbar.LENGTH_SHORT).show()
            return false
        }

        val dirUri = state.selectedDirectoryUri
        if (dirUri == null) {
            Snackbar.make(binding.root, R.string.error_directory_missing, Snackbar.LENGTH_LONG).show()
            return false
        }

        val documentTree = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, dirUri)
        if (documentTree == null || !documentTree.exists() || !documentTree.canWrite()) {
            Snackbar.make(binding.root, R.string.error_directory_invalid, Snackbar.LENGTH_LONG).show()
            viewModel.setSaveDirectory(null, null)
            return false
        }

        return true
    }

    private fun handleExportClick(isSeparate: Boolean) {
        if (!validateExportPreconditions()) return

        // Strict credential element validation — hard block
        val credentialError = viewModel.validateCredentialElements()
        if (credentialError != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.error_missing_credentials_title)
                .setMessage(credentialError)
                .setPositiveButton(R.string.btn_close, null)
                .show()
            return
        }

        // Column mapping validation — snackbar block
        val columnError = viewModel.validateColumnMapping()
        if (columnError != null) {
            Snackbar.make(binding.root, columnError, Snackbar.LENGTH_LONG).show()
            return
        }

        val state = viewModel.uiState.value
        val parse = state.combinedParseResult ?: return

        val hasInvalidNormalFile = state.selectedFiles.any {
            !it.isSupported || it.parseResult?.isSuccess == false || it.parseResult?.count == 0
        }

        if (hasInvalidNormalFile) {
            Snackbar.make(binding.root, R.string.error_invalid_file_selected, Snackbar.LENGTH_LONG).show()
            return
        }

        if (state.credentialMode == dev.anonymous.cardsdesignerpro.app.data.model.CredentialMode.SHORT) {
            val shortParse = state.combinedShortParseResult

            if (shortParse == null || shortParse.count == 0) {
                Snackbar.make(binding.root, R.string.error_short_data_missing, Snackbar.LENGTH_LONG).show()
                return
            }

            val hasInvalidShortFile = state.selectedShortFiles.any {
                !it.isSupported || it.parseResult?.isSuccess == false || it.parseResult?.count == 0
            }

            if (hasInvalidShortFile) {
                Snackbar.make(binding.root, R.string.error_invalid_short_file_selected, Snackbar.LENGTH_LONG).show()
                return
            }

            if (parse.count != shortParse.count) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.short_numbers_warning_format, parse.count, shortParse.count),
                    Snackbar.LENGTH_LONG
                ).show()
                return
            }
        }

        val mismatches = viewModel.validateDigitLengths()
        if (mismatches.isNotEmpty()) {
            showMismatchDialog(mismatches) {
                if (validateExportPreconditions()) {
                    checkLicenseAndProceed(isSeparate)
                }
            }
            return
        }

        checkLicenseAndProceed(isSeparate)
    }

    private fun checkLicenseAndProceed(isSeparate: Boolean) {
        if (!validateExportPreconditions()) return
        launchExportWithNotificationPermissionGate(isSeparate)
    }

    /** Creates the document in the selected directory and launches the export without extra checks. */
    private fun doLaunchExport(isSeparate: Boolean) {
        if (!validateExportPreconditions()) return

        val template = viewModel.selectedTemplate
        if (template == null) {
            Snackbar.make(binding.root, R.string.error_no_templates, Snackbar.LENGTH_SHORT).show()
            return
        }

        val state = viewModel.uiState.value
        val name = template.name
        val dirUri = state.selectedDirectoryUri ?: return
        val documentTree = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, dirUri) ?: return

        if (isSeparate) {
            val frontName = "${name}_${getString(R.string.front_filename)}_${timestamp()}.pdf"
            val backName = "${name}_${getString(R.string.back_side_filename)}_${timestamp()}.pdf"

            val frontFile = documentTree.createFile("application/pdf", frontName)
            val backFile = documentTree.createFile("application/pdf", backName)

            if (frontFile != null && backFile != null) {
                viewModel.exportSeparate(frontFile.uri, backFile.uri)
            } else {
                Snackbar.make(binding.root, R.string.error_directory_create_file, Snackbar.LENGTH_LONG).show()
            }
        } else {
            val fname = if (state.hasBackSide && !state.settings.exportFrontOnly)
                "${name}_${getString(R.string.dual_filename)}_${timestamp()}.pdf"
            else
                "${name}_${getString(R.string.front_filename)}_${timestamp()}.pdf"

            val singleFile = documentTree.createFile("application/pdf", fname)
            if (singleFile != null) {
                if (state.hasBackSide && !state.settings.exportFrontOnly)
                    viewModel.exportDual(singleFile.uri)
                else
                    viewModel.export(singleFile.uri)
            } else {
                Snackbar.make(binding.root, R.string.error_directory_create_file, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Shows a [MaterialAlertDialogBuilder] dialog listing all [MismatchedRecord] entries.
     * [onContinue] is called only when the user explicitly chooses to proceed.
     */
    private fun showMismatchDialog(mismatches: List<MismatchedRecord>, onContinue: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_mismatched_records, null)

        // Build description based on which fields have mismatches
        viewModel.uiState.value
        val template = viewModel.selectedTemplate
        val userEl = template?.elements
            ?.filterIsInstance<dev.anonymous.cardsdesignerpro.app.data.model.TemplateElement.UsernameElement>()
            ?.firstOrNull { !it.isShortVariant }
        val passEl = template?.elements
            ?.filterIsInstance<dev.anonymous.cardsdesignerpro.app.data.model.TemplateElement.PasswordElement>()
            ?.firstOrNull { !it.isShortVariant }
        val hasUserEl = userEl != null
        val hasPassEl = passEl != null
        val desc = when {
            hasUserEl && hasPassEl ->
                getString(R.string.dialog_mismatch_desc, userEl.digitCount, passEl.digitCount)

            hasUserEl ->
                getString(R.string.dialog_mismatch_desc_user_only, userEl.digitCount)

            hasPassEl ->
                getString(R.string.dialog_mismatch_desc_pass_only, passEl.digitCount)

            else -> ""
        }
        dialogView.findViewById<android.widget.TextView>(R.id.tv_mismatch_desc).text = desc

        val rv =
            dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_mismatched)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = MismatchedRecordAdapter(mismatches)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_mismatch_title)
            .setView(dialogView)
            .setPositiveButton(R.string.action_continue_export) { _, _ -> onContinue() }
            .setNegativeButton(R.string.action_fix_data, null)
            .show()
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { renderState(it) }
            }
        }

        // Observe column mapping dialog requests
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.columnMappingEvent.collect { request ->
                    val mode = viewModel.uiState.value.credentialMode
                    val dialog = ColumnMappingDialogFragment.newInstance(
                        uri = request.uri,
                        headers = request.headers,
                        isShortFile = request.isShort,
                        fileName = request.fileName,
                        isUsernameOnly = mode == dev.anonymous.cardsdesignerpro.app.data.model.CredentialMode.USERNAME_ONLY
                    )
                    dialog.show(supportFragmentManager, ColumnMappingDialogFragment.TAG)
                }
            }
        }

        // Listen for column mapping dialog result
        supportFragmentManager.setFragmentResultListener(
            ColumnMappingDialogFragment.RESULT_KEY, this
        ) { _, bundle ->
            val uriStr = bundle.getString(ColumnMappingDialogFragment.KEY_URI) ?: return@setFragmentResultListener
            val usernameCol = bundle.getString(ColumnMappingDialogFragment.KEY_USERNAME_COL)
            val passwordCol = bundle.getString(ColumnMappingDialogFragment.KEY_PASSWORD_COL)
            val isShort = bundle.getBoolean(ColumnMappingDialogFragment.KEY_IS_SHORT, false)

            val mapping = dev.anonymous.cardsdesignerpro.app.data.parser.ColumnMapping(usernameCol, passwordCol)
            viewModel.reparseWithMapping(uriStr.toUri(), mapping, isShort)
        }
    }

    private fun renderState(state: ExportUiState) {
        // File list
        fileAdapter.submitList(state.selectedFiles.toList())
        shortFileAdapter.submitList(state.selectedShortFiles.toList())
        val canAttachDataFiles = !state.isUnusedCardsExport
        binding.btnChooseFile.isEnabled = canAttachDataFiles
        binding.btnChooseFile.alpha = if (canAttachDataFiles) 1f else 0.45f
        binding.btnChooseShortFile.isEnabled = canAttachDataFiles
        binding.btnChooseShortFile.alpha = if (canAttachDataFiles) 1f else 0.45f

        // Setup Short Numbers UI based on template flag
        if (state.credentialMode == dev.anonymous.cardsdesignerpro.app.data.model.CredentialMode.SHORT) {
            binding.layoutShortFilePicker.visibility = View.VISIBLE
            val shortParse = state.combinedShortParseResult
            val normalParse = state.combinedParseResult

            if (shortParse != null && normalParse != null && shortParse.count > 0 && normalParse.count > 0 && shortParse.count != normalParse.count) {
                binding.tvShortRecordsWarning.visibility = View.VISIBLE
                binding.tvShortRecordsWarning.text = getString(
                    R.string.short_numbers_warning_format,
                    normalParse.count,
                    shortParse.count
                )
            } else {
                binding.tvShortRecordsWarning.visibility = View.GONE
            }
        } else {
            binding.layoutShortFilePicker.visibility = View.GONE
        }

        // Template spinner
        val templates = state.templates
        val templateNames = if (templates.isEmpty()) {
            listOf(getString(R.string.error_no_templates))
        } else {
            templates.map { it.name }
        }

        val oldNames = binding.spinnerTemplate.tag as? List<*>
        if (oldNames != templateNames) {
            binding.spinnerTemplate.tag = templateNames
            binding.spinnerTemplate.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item, templateNames).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            binding.spinnerTemplate.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        if (templates.isNotEmpty()) {
                            viewModel.selectTemplate(pos)
                        }
                    }

                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
        }
        if (templates.isNotEmpty() && binding.spinnerTemplate.selectedItemPosition != state.selectedTemplateIndex)
            binding.spinnerTemplate.setSelection(state.selectedTemplateIndex)

        // Card layout spinner
        val cols = state.settings.layoutColumns
        val rows = state.settings.layoutRows
        val layoutIdx = CardLayoutPreset.indexFor(cols, rows)
        // layoutIdx == -1 means custom, spinner pos 0; otherwise offset +1 for the custom item
        val spinnerPos = if (layoutIdx >= 0) layoutIdx + 1 else 0

        val layoutChanged = cols != lastRenderedLayoutColumns || rows != lastRenderedLayoutRows
        if (binding.spinnerCardLayout.adapter == null || layoutChanged) {
            lastRenderedLayoutColumns = cols
            lastRenderedLayoutRows = rows

            rebuildLayoutSpinnerAdapter()
            binding.spinnerCardLayout.setSelection(spinnerPos)

            if (binding.spinnerCardLayout.onItemSelectedListener == null) {
                binding.spinnerCardLayout.onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                            val currentPresetIdx = CardLayoutPreset.indexFor(
                                viewModel.uiState.value.settings.layoutColumns,
                                viewModel.uiState.value.settings.layoutRows
                            )
                            val currentPos = if (currentPresetIdx >= 0) currentPresetIdx + 1 else 0

                            // If selection hasn't changed from ViewModel state, ignore (system callback / rotation)
                            if (pos == currentPos) return

                            if (pos == 0) {
                                // User explicitly switched from a preset to "Custom…"
                                showCustomLayoutDialog()
                            } else {
                                // Preset selected — offset by 1 for the custom item
                                viewModel.updateCardLayout(pos - 1)
                            }
                        }

                        override fun onNothingSelected(p: AdapterView<*>?) {}
                    }
            }
        } else if (binding.spinnerCardLayout.selectedItemPosition != spinnerPos) {
            binding.spinnerCardLayout.setSelection(spinnerPos)
        }

        // Show/hide edit button for custom layouts
        binding.btnEditCustomLayout.visibility = if (layoutIdx < 0) View.VISIBLE else View.GONE

        // Spacing steppers
        binding.stepperHSpacing.minValue = 0
        binding.stepperHSpacing.maxValue = 60
        if (binding.stepperHSpacing.value != state.settings.horizontalSpacingDp.toInt())
            binding.stepperHSpacing.value =
                state.settings.horizontalSpacingDp.toInt().coerceIn(0, 60)
        binding.tvHSpacingLabel.text = getString(R.string.label_horizontal_spacing)

        binding.stepperVSpacing.minValue = 0
        binding.stepperVSpacing.maxValue = 60
        if (binding.stepperVSpacing.value != state.settings.verticalSpacingDp.toInt())
            binding.stepperVSpacing.value = state.settings.verticalSpacingDp.toInt().coerceIn(0, 60)
        binding.tvVSpacingLabel.text = getString(R.string.label_vertical_spacing)
        // Page size
        val psIdx = PageSize.entries.indexOf(state.settings.pageSize).coerceAtLeast(0)
        if (binding.spinnerPageSize.selectedItemPosition != psIdx)
            binding.spinnerPageSize.setSelection(psIdx)

        binding.checkboxPageNumbers.setOnCheckedChangeListener(null)
        binding.checkboxPageNumbers.isChecked = state.settings.showPageNumbers
        binding.checkboxPageNumbers.setOnCheckedChangeListener { _, checked ->
            viewModel.updateShowPageNumbers(checked)
        }

        // Quality
        val qIdx = ExportQuality.entries.indexOf(state.settings.quality).coerceAtLeast(0)
        if (binding.spinnerQuality.selectedItemPosition != qIdx)
            binding.spinnerQuality.setSelection(qIdx)

        // Quality spinner only shown when template has raster images or QR codes.
        // SVG and text always render at FULL quality automatically.
        val hasImageOrQr = state.templates.getOrNull(state.selectedTemplateIndex)?.let { t ->
            val elements = t.elements + (t.backElements ?: emptyList())
            // Check card background image (front + back)
            val hasBgImage = !t.card.backgroundImagePath.isNullOrEmpty() ||
                    !t.backCard?.backgroundImagePath.isNullOrEmpty()
            hasBgImage || elements.any { el ->
                when (el) {
                    is dev.anonymous.cardsdesignerpro.app.data.model.TemplateElement.QrElement -> true
                    is dev.anonymous.cardsdesignerpro.app.data.model.TemplateElement.ImageElement -> {
                        !el.imagePath.lowercase().endsWith(".svg")
                    }
                    else -> false
                }
            }
        } ?: false
        val qualityVisibility = if (hasImageOrQr) View.VISIBLE else View.GONE
        binding.tvQualityLabel.visibility = qualityVisibility
        binding.spinnerQuality.visibility = qualityVisibility

        val showDual = state.hasBackSide && !state.settings.exportFrontOnly
        val parse = state.combinedParseResult

        // Stats
        val layout = state.layout

        binding.tvCardsPerPageLabel.text = getString(
            if (showDual) R.string.label_cards_per_page_front else R.string.label_cards_per_page
        )
        binding.tvTotalPagesLabel.text = getString(
            if (showDual) R.string.label_total_pages_front else R.string.label_total_pages
        )

        if (layout != null) {
            binding.tvCardsPerPage.text = layout.cardsPerPage.toString()
            if (parse != null && parse.count > 0) {
                val frontPages = layout.pageCount(parse.count)
                binding.tvTotalCards.text = parse.count.toString()
                binding.tvTotalPages.text = frontPages.toString()
                binding.tvTotalDualPages.text = (frontPages * 2).toString()
            } else {
                binding.tvTotalCards.text = "—"
                binding.tvTotalPages.text = "—"
                binding.tvTotalDualPages.text = "—"
            }
        } else {
            binding.tvCardsPerPage.text = "—"
            binding.tvTotalCards.text = "—"
            binding.tvTotalPages.text = "—"
            binding.tvTotalDualPages.text = "—"
        }
        binding.layoutTotalDualPages.visibility = if (showDual) View.VISIBLE else View.GONE
        binding.tvFrontSideTitle.visibility = if (showDual) View.VISIBLE else View.GONE

        // Apply selected quality to previews so they match the export output
        binding.pagePreview.setQuality(state.settings.quality)
        binding.pagePreviewBack.setQuality(state.settings.quality)

        // Front preview (not mirrored)
        binding.pagePreview.bind(
            template = state.templates.getOrNull(state.selectedTemplateIndex),
            layout = layout,
            showPageNumberPreview = state.settings.showPageNumbers
        )

        // Back preview — mirrored + flipEdge
        binding.layoutBackSidePreview.visibility = if (showDual) View.VISIBLE else View.GONE
        if (showDual && state.backLayout != null) {
            val backT = state.templates.getOrNull(state.selectedTemplateIndex)
                ?.let { t -> t.backElements?.let { t.copy(elements = it) } }
            binding.pagePreviewBack.bind(
                template = backT,
                layout = state.backLayout,
                isMirrored = true,
                flipEdge = state.settings.flipEdge,
                showPageNumberPreview = state.settings.showPageNumbers
            )
        }
        updateFlipHint(state.settings.flipEdge)

        // Dual controls in bottom sheet
        binding.checkboxFrontOnly.visibility = if (state.hasBackSide) View.VISIBLE else View.GONE
        binding.checkboxFrontOnly.setOnCheckedChangeListener(null)
        binding.checkboxFrontOnly.isChecked = state.settings.exportFrontOnly
        binding.checkboxFrontOnly.setOnCheckedChangeListener { _, c ->
            viewModel.updateExportFrontOnly(
                c
            )
        }

        binding.tvFlipEdgeLabel.visibility = if (showDual) View.VISIBLE else View.GONE
        binding.toggleFlipEdge.visibility = if (showDual) View.VISIBLE else View.GONE
        val flipId =
            if (state.settings.flipEdge == FlipEdge.LONG_EDGE) R.id.btn_flip_long else R.id.btn_flip_short
        if (binding.toggleFlipEdge.checkedButtonId != flipId) binding.toggleFlipEdge.check(flipId)

        // Export location & directory button
        if (state.selectedDirectoryUri != null) {
            binding.tvSelectedDirectory.text = state.selectedDirectoryName
            binding.tvSelectedDirectory.visibility = View.VISIBLE
            binding.btnChooseDirectory.text = getString(R.string.btn_change_directory)
        } else {
            binding.tvSelectedDirectory.visibility = View.VISIBLE
            binding.tvSelectedDirectory.text = getString(R.string.label_not_selected)
            binding.btnChooseDirectory.text = getString(R.string.btn_choose_directory)
        }

        val hasTemplate = state.templates.isNotEmpty() && viewModel.selectedTemplate != null
        val hasData = state.combinedParseResult != null && state.combinedParseResult.count > 0
        val hasDir = state.selectedDirectoryUri != null
        val showExport = hasTemplate && hasData && hasDir

        // Export buttons
        if (showExport) {
            binding.btnExport.visibility = View.VISIBLE
            if (showDual) {
                binding.btnExportSeparate.visibility = View.VISIBLE
                binding.btnExport.setText(R.string.btn_export_dual)
            } else {
                binding.btnExportSeparate.visibility = View.GONE
                binding.btnExport.setText(R.string.btn_export)
            }
        } else {
            binding.btnExport.visibility = View.GONE
            binding.btnExportSeparate.visibility = View.GONE
        }

        val exporting = state.isExporting
        binding.progressExport.visibility = if (exporting) View.VISIBLE else View.GONE
        binding.progressExport.progress = (state.exportProgress * 100).toInt()
        binding.btnExport.isEnabled = !exporting
        binding.btnExportSeparate.isEnabled = !exporting

        // Events
        state.event?.let { ev ->
            when (ev) {
                is ExportEvent.ExportSuccess -> {
                    showSingleSuccessDialog(ev.outputUri)
                }
                is ExportEvent.ExportSuccessDual -> {
                    showSeparateSuccessDialog(ev.frontUri, ev.backUri)
                }

                is ExportEvent.ExportFailed ->
                    Snackbar.make(
                        binding.root,
                        R.string.export_failed_message,
                        Snackbar.LENGTH_SHORT
                    ).show()

                is ExportEvent.Idle -> {}
            }
            viewModel.consumeEvent()
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    /** Single file (front-only or interleaved dual): Preview + Open externally */
    private fun showSingleSuccessDialog(uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_success_title)
            .setMessage(R.string.export_success_message)
            .setNegativeButton(R.string.btn_close, null)
            .setNeutralButton(R.string.btn_preview_pdf) { _, _ -> openExternally(uri) }
            .setPositiveButton(R.string.btn_open_pdf) { _, _ -> openInViewer(uri) }
            .show()
    }

    /** Separate files: Open front | Open back | Cancel */
    private fun showSeparateSuccessDialog(frontUri: Uri, backUri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_success_title)
            .setMessage(R.string.export_separate_success_message)
            .setNegativeButton(R.string.btn_close, null)
            .setNeutralButton(R.string.btn_open_back_pdf) { _, _ -> openInViewer(backUri) }
            .setPositiveButton(R.string.btn_open_front_pdf) { _, _ -> openInViewer(frontUri) }
            .show()
    }

    // ── Navigation helpers ────────────────────────────────────────────────────

    private fun openInViewer(uri: Uri) {
        startActivity(Intent(this, PdfViewerActivity::class.java).apply {
            putExtra(PdfViewerActivity.EXTRA_PDF_URI, uri.toString())
        })
    }

    private fun openExternally(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        runCatching { startActivity(intent) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun queryFileName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment

        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col < 0) null else c.getString(col)
            }
        }.getOrNull()
    }

    private fun timestamp() =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    /**
     * Handles incoming [Intent.ACTION_VIEW] (open from file manager) and
     * [Intent.ACTION_SEND] / [Intent.ACTION_SEND_MULTIPLE] (share from another app) 
     * intents by automatically loading the CSV/Excel file into the export screen.
     *
     * Consumes the intent action and uses a ViewModel flag to prevent re-processing.
     */
    @Suppress("DEPRECATION")
    private fun handleIncomingFileIntent(intent: Intent) {
        val action = intent.action ?: return
        if (viewModel.isIntentProcessed) return
        if (viewModel.uiState.value.isUnusedCardsExport) {
            viewModel.isIntentProcessed = true
            intent.action = null
            return
        }

        val incomingType = intent.type
        val uris = when (action) {
            Intent.ACTION_VIEW -> intent.data?.let { listOf(it) }

            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                uri?.let { listOf(it) }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)

                if (!list.isNullOrEmpty()) {
                    list
                } else {
                    val single = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    single?.let { listOf(it) }
                }
            }

            else -> null
        } ?: return

        // 1. Advanced MIME/Extension validation
        val validUris = uris.filter { isSupported(it, incomingType) }
        if (validUris.isEmpty()) return

        // 2. Persistent Permission with masked flags
        val takeFlags =
            intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        validUris.forEach { uri ->
            if (uri.scheme == "content") {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                }
            }
        }

        val names = validUris.map { queryFileName(it) ?: it.lastPathSegment ?: "file" }
        viewModel.addFiles(validUris, names)

        // 3. Mark as processed to prevent repeats on recreation or rotation
        viewModel.isIntentProcessed = true
        intent.action = null
    }

    /**
     * Verifies if a URI pointing to a data file is supported, 
     * checking both MIME type and file extension as a fallback.
     */
    private fun isSupported(uri: Uri, type: String?): Boolean {
        val validMimeTypes = setOf(
            "text/csv", "text/comma-separated-values",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/pdf"
        )
        if (type != null && type != "*/*") {
            if (validMimeTypes.contains(type)) return true
        }

        val name = queryFileName(uri) ?: return false
        return name.endsWith(".csv", true) ||
                name.endsWith(".xlsx", true) ||
                name.endsWith(".pdf", true)
    }
}

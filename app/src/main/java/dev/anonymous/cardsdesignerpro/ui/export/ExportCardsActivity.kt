package dev.anonymous.cardsdesignerpro.ui.export

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.data.model.CardLayoutPreset
import dev.anonymous.cardsdesignerpro.data.model.ExportQuality
import dev.anonymous.cardsdesignerpro.data.model.FlipEdge
import dev.anonymous.cardsdesignerpro.data.model.PageSize
import dev.anonymous.cardsdesignerpro.databinding.ActivityExportCardsBinding
import dev.anonymous.cardsdesignerpro.ui.viewer.PdfViewerActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExportCardsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExportCardsBinding
    private val viewModel: ExportCardsViewModel by viewModels()
    private lateinit var fileAdapter: SelectedFileAdapter
    private lateinit var shortFileAdapter: SelectedFileAdapter

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

    /** Single PDF — used for single-face or interleaved-dual export */
    private val singlePdfSaver = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val state = viewModel.uiState.value
        if (state.hasBackSide && !state.settings.exportFrontOnly)
            viewModel.exportDual(uri)
        else
            viewModel.export(uri)
    }

    /** Front PDF saver — step 1 of separate export */
    private var pendingFrontUri: Uri? = null
    private val frontPdfSaver = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        pendingFrontUri = uri
        val name = viewModel.selectedTemplate?.name ?: "cards"
        backPdfSaver.launch("${name}_${getString(R.string.back_side_filename)}_${timestamp()}.pdf")
    }

    /** Back PDF saver — step 2 of separate export */
    private val backPdfSaver = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        val frontUri = pendingFrontUri ?: return@registerForActivityResult
        pendingFrontUri = null
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        viewModel.exportSeparate(frontUri, uri)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExportCardsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupBottomSheetDrag()
        setupFileList()
        setupFilePicker()
        setupLayoutSpinner()
        setupSpacingSliders()
        setupPageSizeSpinner()
        setupQualitySpinner()
        setupFlipEdgeToggle()
        setupFrontOnlyCheckbox()
        setupExportButtons()
        observeViewModel()
        
        checkIntentForDualPreview(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkIntentForDualPreview(intent)
    }

    private fun checkIntentForDualPreview(intent: Intent) {
        if (intent.getBooleanExtra("show_dual_preview", false)) {
            intent.removeExtra("show_dual_preview") // Prevent dialog from showing again on rotation
            val frontStr = intent.getStringExtra("front_uri")
            val backStr = intent.getStringExtra("back_uri")
            if (frontStr != null && backStr != null) {
                showSeparateSuccessDialog(Uri.parse(frontStr), Uri.parse(backStr))
            }
        }
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupToolbar() = binding.toolbar.setNavigationOnClickListener { finish() }

    private fun setupBottomSheetDrag() {
        var initialDragY = 0f;
        var initialHeight = 0
        binding.dragHandleArea.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialDragY = event.rawY; initialHeight = binding.bottomSheetHost.height; true
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    val minH =
                        binding.dragHandleArea.height + (48 * resources.displayMetrics.density).toInt()
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
        fileAdapter = SelectedFileAdapter { uri -> viewModel.removeFile(uri) }
        shortFileAdapter = SelectedFileAdapter { uri -> viewModel.removeFile(uri, isShort = true) }
        
        val touchHelper = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0
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

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}
            
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

        val touchHelperShort = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0
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

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}
            
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

    private fun setupFilePicker() {
        binding.btnChooseFile.setOnClickListener {
            multiFilePicker.launch(
                arrayOf(
                    "text/csv", "text/comma-separated-values",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "*/*"
                )
            )
        }
        binding.btnChooseShortFile.setOnClickListener {
            multiFilePickerShort.launch(
                arrayOf(
                    "text/csv", "text/comma-separated-values",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "*/*"
                )
            )
        }
    }

    private fun setupLayoutSpinner() {
        val presets = CardLayoutPreset.ALL
        val labels = presets.map { preset ->
            val suffix = if (preset.isRecommended) getString(R.string.label_recommended_suffix) else ""
            getString(
                R.string.layout_preset_format,
                preset.totalCards,
                preset.columns,
                preset.rows
            ) + suffix
        }
        binding.spinnerCardLayout.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerCardLayout.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    viewModel.updateCardLayout(pos)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
    }

    private fun setupSpacingSliders() {
        binding.sliderHSpacing.addOnChangeListener(Slider.OnChangeListener { _, v, fromUser ->
            if (fromUser) viewModel.updateHorizontalSpacing(v)
        })
        binding.sliderVSpacing.addOnChangeListener(Slider.OnChangeListener { _, v, fromUser ->
            if (fromUser) viewModel.updateVerticalSpacing(v)
        })
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

    /** Maps each [ExportQuality] to its string resource. */
    private fun ExportQuality.labelRes(): Int = when (this) {
        ExportQuality.FULL   -> R.string.quality_full
        ExportQuality.HIGH   -> R.string.quality_high
        ExportQuality.GOOD   -> R.string.quality_good
        ExportQuality.MEDIUM -> R.string.quality_medium
        ExportQuality.LOW    -> R.string.quality_low
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
        binding.btnExport.setOnClickListener {
            val parse = viewModel.uiState.value.combinedParseResult
            if (parse == null || parse.count == 0) {
                Snackbar.make(binding.root, R.string.error_no_valid_file, Snackbar.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            val state = viewModel.uiState.value
            if (state.isShortNumbersEnabled) {
                val shortParse = state.combinedShortParseResult
                if (shortParse != null && parse.count > 0 && shortParse.count > 0 && parse.count != shortParse.count) {
                    Snackbar.make(binding.root, getString(R.string.short_numbers_warning_format, parse.count, shortParse.count), Snackbar.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }
            val name = viewModel.selectedTemplate?.name ?: "cards"
            val fname = if (state.hasBackSide && !state.settings.exportFrontOnly)
                "${name}_${getString(R.string.dual_filename)}_${timestamp()}.pdf"
            else
                "${name}_${getString(R.string.front_filename)}_${timestamp()}.pdf"
            singlePdfSaver.launch(fname)
        }

        binding.btnExportSeparate.setOnClickListener {
            val parse = viewModel.uiState.value.combinedParseResult
            if (parse == null || parse.count == 0) {
                Snackbar.make(binding.root, R.string.error_no_valid_file, Snackbar.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            val state = viewModel.uiState.value
            if (state.isShortNumbersEnabled) {
                val shortParse = state.combinedShortParseResult
                if (shortParse != null && parse.count > 0 && shortParse.count > 0 && parse.count != shortParse.count) {
                    Snackbar.make(binding.root, getString(R.string.short_numbers_warning_format, parse.count, shortParse.count), Snackbar.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }
            val name = viewModel.selectedTemplate?.name ?: "cards"
            frontPdfSaver.launch("${name}_${getString(R.string.front_filename)}_${timestamp()}.pdf")
        }
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { renderState(it) }
            }
        }
    }

    private fun renderState(state: ExportUiState) {
        // File list
        fileAdapter.submitList(state.selectedFiles.toList())
        shortFileAdapter.submitList(state.selectedShortFiles.toList())

        // Setup Short Numbers UI based on template flag
        if (state.isShortNumbersEnabled) {
            binding.layoutShortFilePicker.visibility = View.VISIBLE
            val shortParse = state.combinedShortParseResult
            val normalParse = state.combinedParseResult
            
            if (shortParse != null && normalParse != null && shortParse.count > 0 && normalParse.count > 0 && shortParse.count != normalParse.count) {
                binding.tvShortRecordsWarning.visibility = View.VISIBLE
                binding.tvShortRecordsWarning.text = getString(R.string.short_numbers_warning_format, normalParse.count, shortParse.count)
            } else {
                binding.tvShortRecordsWarning.visibility = View.GONE
            }
        } else {
            binding.layoutShortFilePicker.visibility = View.GONE
        }

        // Template spinner
        val templates = state.templates
        if (binding.spinnerTemplate.adapter == null ||
            (binding.spinnerTemplate.adapter as? ArrayAdapter<*>)?.count != templates.size
        ) {
            binding.spinnerTemplate.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item, templates.map { it.name }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            binding.spinnerTemplate.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                        viewModel.selectTemplate(pos)

                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
        }
        if (binding.spinnerTemplate.selectedItemPosition != state.selectedTemplateIndex)
            binding.spinnerTemplate.setSelection(state.selectedTemplateIndex)

        // Card layout spinner
        val layoutIdx = state.settings.selectedLayoutIndex
            .coerceIn(0, CardLayoutPreset.ALL.lastIndex)
        if (binding.spinnerCardLayout.selectedItemPosition != layoutIdx)
            binding.spinnerCardLayout.setSelection(layoutIdx)

        // Spacing sliders
        if (binding.sliderHSpacing.value != state.settings.horizontalSpacingDp)
            binding.sliderHSpacing.value = state.settings.horizontalSpacingDp.coerceIn(0f, 60f)
        if (binding.sliderVSpacing.value != state.settings.verticalSpacingDp)
            binding.sliderVSpacing.value = state.settings.verticalSpacingDp.coerceIn(0f, 60f)

        // Page size
        val psIdx = PageSize.entries.indexOf(state.settings.pageSize).coerceAtLeast(0)
        if (binding.spinnerPageSize.selectedItemPosition != psIdx)
            binding.spinnerPageSize.setSelection(psIdx)

        // Quality
        val qIdx = ExportQuality.entries.indexOf(state.settings.quality).coerceAtLeast(0)
        if (binding.spinnerQuality.selectedItemPosition != qIdx)
            binding.spinnerQuality.setSelection(qIdx)

        // Hide quality spinner if no images or QRs
        val selectedTemplate = state.templates.getOrNull(state.selectedTemplateIndex)
        val hasImageOrQr = selectedTemplate?.let { t ->
            val elements = t.elements + (t.backElements ?: emptyList())
            elements.any { el ->
                when (el) {
                    is dev.anonymous.cardsdesignerpro.data.model.TemplateElement.QrElement -> true
                    is dev.anonymous.cardsdesignerpro.data.model.TemplateElement.ImageElement -> {
                        val path = el.imagePath
                        !path.startsWith("pack:") && !path.lowercase().endsWith(".svg")
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
        binding.pagePreview.bind(state.templates.getOrNull(state.selectedTemplateIndex), layout)

        // Back preview — mirrored + flipEdge
        binding.layoutBackSidePreview.visibility = if (showDual) View.VISIBLE else View.GONE
        if (showDual && state.backLayout != null) {
            val backT = state.templates.getOrNull(state.selectedTemplateIndex)
                ?.let { t -> t.backElements?.let { t.copy(elements = it) } }
            binding.pagePreviewBack.bind(
                template = backT,
                layout = state.backLayout,
                isMirrored = true,
                flipEdge = state.settings.flipEdge
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

        // Export buttons
        binding.btnExportSeparate.visibility = if (showDual) View.VISIBLE else View.GONE
        binding.btnExport.setText(if (showDual) R.string.btn_export_dual else R.string.btn_export)

        val exporting = state.isExporting
        binding.progressExport.visibility = if (exporting) View.VISIBLE else View.GONE
        binding.progressExport.progress = (state.exportProgress * 100).toInt()
        binding.btnExport.isEnabled = !exporting
        binding.btnExportSeparate.isEnabled = !exporting

        // Events
        state.event?.let { ev ->
            when (ev) {
                is ExportEvent.ExportSuccess -> showSingleSuccessDialog(ev.outputUri)
                is ExportEvent.ExportSuccessDual -> showSeparateSuccessDialog(
                    ev.frontUri,
                    ev.backUri
                )

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

    private fun queryFileName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col < 0) null else c.getString(col)
        }

    private fun timestamp() =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
}

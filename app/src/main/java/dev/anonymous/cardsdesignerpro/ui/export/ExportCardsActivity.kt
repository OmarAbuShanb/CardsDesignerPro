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
        setupSliders()
        setupPageSizeSpinner()
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
        binding.rvSelectedFiles.apply {
            adapter = fileAdapter
            layoutManager = LinearLayoutManager(this@ExportCardsActivity)
            isNestedScrollingEnabled = false
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
    }

    private fun setupSliders() {
        binding.sliderCardWidth.addOnChangeListener(Slider.OnChangeListener { _, v, fromUser ->
            if (fromUser) viewModel.updateCardWidthFraction(v)
        })
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

        // Sliders
        if (binding.sliderCardWidth.value != state.settings.cardWidthFraction)
            binding.sliderCardWidth.value = state.settings.cardWidthFraction.coerceIn(0.1f, 0.9f)
        if (binding.sliderHSpacing.value != state.settings.horizontalSpacingDp)
            binding.sliderHSpacing.value = state.settings.horizontalSpacingDp.coerceIn(0f, 60f)
        if (binding.sliderVSpacing.value != state.settings.verticalSpacingDp)
            binding.sliderVSpacing.value = state.settings.verticalSpacingDp.coerceIn(0f, 60f)

        // Page size
        val psIdx = PageSize.entries.indexOf(state.settings.pageSize).coerceAtLeast(0)
        if (binding.spinnerPageSize.selectedItemPosition != psIdx)
            binding.spinnerPageSize.setSelection(psIdx)

        val showDual = state.hasBackSide && !state.settings.exportFrontOnly
        val parse = state.combinedParseResult

        // Stats
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

package dev.anonymous.cardsdesignerpro.app.ui.main

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.anonymous.cardsdesignerpro.app.R
import dev.anonymous.cardsdesignerpro.app.data.model.Template
import dev.anonymous.cardsdesignerpro.app.databinding.ActivityMainBinding
import dev.anonymous.cardsdesignerpro.app.ui.common.TemplateNameDialogFragment
import dev.anonymous.cardsdesignerpro.app.ui.editor.EditorActivity
import dev.anonymous.cardsdesignerpro.app.ui.export.ExportCardsActivity
import kotlinx.coroutines.launch
import androidx.core.net.toUri

private const val PLAY_STORE_PACKAGE_NAME = "com.android.vending"
private const val PLAY_STORE_DEVELOPER_URL =
    "https://play.google.com/store/apps/dev?id=8883891498272507244&hl=ar"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: TemplateAdapter

    private var pendingExportTemplateIds: List<String>? = null

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val templateId = data.getStringExtra(EditorActivity.EXTRA_TEMPLATE_ID)
                ?: return@registerForActivityResult
            val returnedVersion = data.getIntExtra("extra_template_version", -1)

            val currentList = viewModel.templates.value
            val currentVersion = currentList?.find { it.id == templateId }?.version ?: -1

            if (returnedVersion != currentVersion) {
                viewModel.reloadSingleTemplate(templateId)
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.peekImport(it) }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { dest ->
            pendingExportTemplateIds?.let { ids ->
                viewModel.exportTemplates(
                    ids,
                    dest
                )
            }
        }
        pendingExportTemplateIds = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called BEFORE super.onCreate() to properly intercept the splash
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupDialogResults()
        setupRecyclerView()
        setupButtons()
        observeViewModel()
    }

    private fun setupDialogResults() {
        supportFragmentManager.setFragmentResultListener(
            ExportTemplatesDialogFragment.REQUEST_KEY,
            this
        ) { _, bundle ->
            val selectedIds =
                bundle.getStringArrayList(ExportTemplatesDialogFragment.RESULT_SELECTED_IDS)
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
            if (selectedIds.isEmpty()) return@setFragmentResultListener
            pendingExportTemplateIds = selectedIds
            exportLauncher.launch("CardsDesignerProTemplates.templates")
        }

        supportFragmentManager.setFragmentResultListener(
            ImportTemplatesDialogFragment.REQUEST_KEY,
            this
        ) { _, bundle ->
            val uriString = bundle.getString(ImportTemplatesDialogFragment.RESULT_URI)
                ?: return@setFragmentResultListener
            val selectedIds =
                bundle.getStringArrayList(ImportTemplatesDialogFragment.RESULT_SELECTED_IDS)
                    ?.filter { it.isNotBlank() }
                    ?.toSet()
                    .orEmpty()
            if (selectedIds.isEmpty()) return@setFragmentResultListener
            viewModel.confirmImport(uriString.toUri(), selectedIds)
        }
    }

    private fun setupRecyclerView() {
        adapter = TemplateAdapter(
            onOpen = { template -> openEditor(template.id) },
            onRename = { template -> showRenameDialog(template) },
            onDuplicate = { template -> viewModel.duplicateTemplate(template.id) },
            onDelete = { template -> showDeleteDialog(template) }
        )
        binding.rvTemplates.layoutManager = LinearLayoutManager(this)
        binding.rvTemplates.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnExportCards.setOnClickListener {
            startActivity(Intent(this, ExportCardsActivity::class.java))
        }
        binding.btnNewTemplate.setOnClickListener {
            showNewTemplateDialog()
        }
        binding.btnEditDefault.setOnClickListener {
            showNewDefaultTemplateDialog()
        }
        binding.btnExportAllTemplates.setOnClickListener {
            showExportDialog()
        }
        binding.btnImportTemplate.setOnClickListener {
            // Need */* or application/octet-stream since we use a custom extension
            importLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.templates.collect { templates ->
                        if (templates == null) {
                            binding.rvTemplates.visibility = View.GONE
                            binding.tvEmpty.visibility = View.GONE
                        } else {
                            val isFirstLoad =
                                adapter.currentList.isEmpty() && templates.isNotEmpty()
                            adapter.submitList(templates) {
                                if (isFirstLoad) binding.rvTemplates.scheduleLayoutAnimation()
                            }
                            binding.tvEmpty.visibility =
                                if (templates.isEmpty()) View.VISIBLE else View.GONE
                            binding.rvTemplates.visibility =
                                if (templates.isEmpty()) View.GONE else View.VISIBLE
                        }
                    }
                }
                launch {
                    viewModel.event.collect { event ->
                        event ?: return@collect
                        when (event) {
                            is MainEvent.ExportSuccess ->
                                snack(getString(R.string.export_template_success))

                            is MainEvent.ExportFailed ->
                                snack(getString(R.string.export_template_failed))

                            is MainEvent.ShowImportDialog ->
                                showImportDialog(event.uri, event.templates)

                            is MainEvent.ImportSuccess ->
                                snack(getString(R.string.import_templates_success, event.count))

                            is MainEvent.ImportFailed ->
                                snack(getString(R.string.import_failed))
                        }
                        viewModel.consumeEvent()
                    }
                }
                launch {
                    viewModel.isLoading.collect { loading ->
                        if (loading) showProgressDialog() else hideProgressDialog()
                    }
                }
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showNewTemplateDialog() {
        val reqKey = "new_template"
        supportFragmentManager.setFragmentResultListener(reqKey, this) { _, bundle ->
            val name = bundle.getString("name")
            if (!name.isNullOrEmpty()) {
                val id = viewModel.createTemplate(name)
                openEditor(id)
            }
        }
        if (supportFragmentManager.findFragmentByTag(TemplateNameDialogFragment.TAG) == null) {
            TemplateNameDialogFragment.newInstance(
                R.string.dialog_new_template_title,
                R.string.btn_next,
                null,
                reqKey
            ).show(supportFragmentManager, TemplateNameDialogFragment.TAG)
        }
    }

    private fun showRenameDialog(template: Template) {
        val reqKey = "rename_template_${template.id}"
        supportFragmentManager.setFragmentResultListener(reqKey, this) { _, bundle ->
            val name = bundle.getString("name")
            if (!name.isNullOrEmpty()) viewModel.renameTemplate(template.id, name)
        }
        if (supportFragmentManager.findFragmentByTag(TemplateNameDialogFragment.TAG) == null) {
            TemplateNameDialogFragment.newInstance(
                R.string.dialog_rename_template_title,
                R.string.btn_save,
                template.name,
                reqKey
            ).show(supportFragmentManager, TemplateNameDialogFragment.TAG)
        }
    }

    private fun showNewDefaultTemplateDialog() {
        if (supportFragmentManager.findFragmentByTag(DefaultTemplatesBottomSheet.TAG) == null) {
            DefaultTemplatesBottomSheet().show(
                supportFragmentManager,
                DefaultTemplatesBottomSheet.TAG
            )
        }
    }

    private fun showDeleteDialog(template: Template) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_template_title)
            .setMessage(getString(R.string.delete_template_message, template.name))
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                viewModel.deleteTemplate(template.id)
            }
            .show()
    }

    // ── Batch Export / Import Dialogs ─────────────────────────────────────────

    private fun showExportDialog() {
        val allTemplates = viewModel.templates.value
        if (allTemplates.isNullOrEmpty()) {
            snack(getString(R.string.no_templates_to_export))
            return
        }

        val items = allTemplates.map { template ->
            SelectionItem(
                id = template.id,
                name = template.name
            )
        }

        showDialogOnce(ExportTemplatesDialogFragment.TAG) {
            ExportTemplatesDialogFragment.newInstance(items)
        }
    }

    private fun showImportDialog(uri: Uri, zippedTemplates: List<Template>) {
        val currentTemplates = (viewModel.templates.value ?: emptyList()).associateBy { it.id }
        val items = zippedTemplates.map { zipped ->
            val current = currentTemplates[zipped.id]
            val status = when {
                current == null -> null // New, the user requested no text
                zipped.version > current.version -> getString(R.string.status_newer_version)
                zipped.version < current.version -> getString(R.string.status_older_version)
                else -> getString(R.string.status_same_version)
            }
            SelectionItem(
                id = zipped.id,
                name = zipped.name,
                statusText = status
            )
        }

        showDialogOnce(ImportTemplatesDialogFragment.TAG) {
            ImportTemplatesDialogFragment.newInstance(
                uri = uri.toString(),
                items = items
            )
        }
    }

    private fun showDialogOnce(tag: String, createDialog: () -> DialogFragment) {
        if (supportFragmentManager.isStateSaved) return
        supportFragmentManager.executePendingTransactions()
        if (supportFragmentManager.findFragmentByTag(tag) != null) {
            return
        }
        createDialog().show(supportFragmentManager, tag)
        supportFragmentManager.executePendingTransactions()
    }

    private var progressDialog: AlertDialog? = null

    private fun showProgressDialog() {
        if (progressDialog == null) {
            val view = layoutInflater.inflate(R.layout.dialog_loading, null)
            progressDialog = MaterialAlertDialogBuilder(this)
                .setView(view)
                .setCancelable(false)
                .create()
        }
        progressDialog?.show()
    }

    private fun hideProgressDialog() {
        progressDialog?.dismiss()
    }

    // ── Menu ──────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_exported_files -> {
                startActivity(
                    Intent(this, dev.anonymous.cardsdesignerpro.app.ui.exportedfiles.ExportedFilesActivity::class.java)
                )
                true
            }
            R.id.action_unused_cards_extractor -> {
                startActivity(
                    Intent(this, dev.anonymous.cardsdesignerpro.app.ui.unusedcards.UnusedCardsActivity::class.java)
                )
                true
            }
            R.id.action_contact_dev -> {
                contactDeveloper()
                true
            }
            R.id.action_my_apps_on_google_play -> {
                openMyAppsOnGooglePlay()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openMyAppsOnGooglePlay() {
        val developerPageUri = PLAY_STORE_DEVELOPER_URL.toUri()
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    developerPageUri
                ).apply {
                    setPackage(PLAY_STORE_PACKAGE_NAME)
                }
            )
        } catch (_: Exception) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    developerPageUri
                )
            )
        }
    }

    private fun contactDeveloper() {
        val phoneNumber = "+970597152714"
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val uri = "whatsapp://send?phone=+970597152714".toUri()
            intent.data = uri
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                val webUri = "https://wa.me/$phoneNumber".toUri()
                startActivity(Intent(Intent.ACTION_VIEW, webUri))
            }
        } catch (e: Exception) {
            Log.e("Contact", "Error opening WhatsApp: ${e.message}")
            Toast.makeText(this, getString(R.string.whatsApp_failed_to_open), Toast.LENGTH_SHORT).show()
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    fun openEditor(templateId: String) {
        editorLauncher.launch(
            Intent(this, EditorActivity::class.java)
                .putExtra(EditorActivity.EXTRA_TEMPLATE_ID, templateId)
        )
    }

    fun snack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
}

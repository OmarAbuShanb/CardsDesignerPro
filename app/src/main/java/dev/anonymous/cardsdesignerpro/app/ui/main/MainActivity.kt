package dev.anonymous.cardsdesignerpro.app.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import dev.anonymous.cardsdesignerpro.app.data.license.LicenseManager
import dev.anonymous.cardsdesignerpro.app.data.license.LicenseStatus
import dev.anonymous.cardsdesignerpro.app.data.license.PremiumFeature
import dev.anonymous.cardsdesignerpro.app.data.model.Template
import dev.anonymous.cardsdesignerpro.app.databinding.ActivityMainBinding
import dev.anonymous.cardsdesignerpro.app.ui.common.TemplateNameDialogFragment
import dev.anonymous.cardsdesignerpro.app.ui.editor.EditorActivity
import dev.anonymous.cardsdesignerpro.app.ui.export.ExportCardsActivity
import dev.anonymous.cardsdesignerpro.app.ui.license.LicenseDialogs
import kotlinx.coroutines.launch
import android.widget.ProgressBar
import android.widget.TextView

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

    private lateinit var licenseManager: LicenseManager

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called BEFORE super.onCreate() to properly intercept the splash
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        licenseManager = LicenseManager.getInstance(this)

        setupDialogResults()
        setupRecyclerView()
        setupButtons()
        setupActivationButton()
        observeViewModel()
        observeLicenseState()
        setupBackPressBlock()

        // Mandatory server sync — blocks the app until server confirms trial
        if (!licenseManager.hasCompletedServerSync && !licenseManager.isActivated) {
            showServerSyncDialog()
        } else {
            // Already synced — do a background refresh (non-blocking)
            licenseManager.syncTrialWithServer(onSuccess = {}, onFailure = {})
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-show blocking dialog if sync was never completed and Activity was recreated
        // (e.g., user pressed Home then returned, or dialog was lost due to edge case)
        if (!licenseManager.hasCompletedServerSync && !licenseManager.isActivated) {
            if (serverSyncDialog == null || !serverSyncDialog!!.isShowing) {
                showServerSyncDialog()
            }
        }
    }

    override fun onDestroy() {
        // Dismiss dialog to prevent window leak on config change
        serverSyncDialog?.dismiss()
        serverSyncDialog = null
        super.onDestroy()
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
            viewModel.confirmImport(Uri.parse(uriString), selectedIds)
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
            if (!licenseManager.canAccess(PremiumFeature.EXPORT_TEMPLATES_BACKUP)) {
                LicenseDialogs.showPremiumFeatureDialog(this, R.string.license_premium_export_message) { showActivationDialog() }
                return@setOnClickListener
            }
            showExportDialog()
        }
        binding.btnImportTemplate.setOnClickListener {
            if (!licenseManager.canAccess(PremiumFeature.IMPORT_TEMPLATES_BACKUP)) {
                LicenseDialogs.showPremiumFeatureDialog(this, R.string.license_premium_import_message) { showActivationDialog() }
                return@setOnClickListener
            }
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

    // ── License ────────────────────────────────────────────────────────────────

    private var serverSyncDialog: AlertDialog? = null

    /**
     * Blocks the system back button while the mandatory sync dialog is showing.
     * This prevents users from dismissing the dialog via the back gesture.
     */
    private fun setupBackPressBlock() {
        onBackPressedDispatcher.addCallback(this) {
            if (serverSyncDialog?.isShowing == true) {
                // Block — do nothing, don't let them escape the sync dialog
                return@addCallback
            }
            // Normal back behavior
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupActivationButton() {
        binding.btnActivate.setOnClickListener { showActivationDialog() }
    }

    /**
     * Shows a non-dismissable dialog that blocks the app until the server
     * confirms the trial registration. Prevents clear-data + offline exploit.
     */
    private fun showServerSyncDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_server_sync, null)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progress_sync)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tv_sync_status)
        val tvError = dialogView.findViewById<TextView>(R.id.tv_sync_error)
        val btnRetry = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_retry)
        val btnHaveCode = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_have_code)

        serverSyncDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.license_sync_title)
            .setView(dialogView)
            .setCancelable(false)
            .create()
            .also { it.setCanceledOnTouchOutside(false) }

        fun doSync() {
            progressBar.visibility = View.VISIBLE
            tvStatus.text = getString(R.string.license_sync_loading)
            tvError.visibility = View.GONE
            btnRetry.visibility = View.GONE
            btnHaveCode.visibility = View.GONE

            licenseManager.syncTrialWithServer(
                onSuccess = {
                    serverSyncDialog?.dismiss()
                    serverSyncDialog = null
                },
                onFailure = { errorMsg ->
                    progressBar.visibility = View.GONE
                    tvStatus.text = getString(R.string.license_sync_error, errorMsg)
                    tvError.visibility = View.GONE
                    btnRetry.visibility = View.VISIBLE
                    btnHaveCode.visibility = View.VISIBLE
                }
            )
        }

        btnRetry.setOnClickListener { doSync() }

        btnHaveCode.setOnClickListener {
            LicenseDialogs.showActivationDialog(this) {
                // Activation succeeded — dismiss the sync blocker
                serverSyncDialog?.dismiss()
                serverSyncDialog = null
            }
        }

        serverSyncDialog?.show()
        doSync()
    }

    private fun observeLicenseState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                licenseManager.licenseState.collect { state ->
                    if (state.isActivated) {
                        binding.btnActivate.visibility = View.GONE
                    } else {
                        binding.btnActivate.visibility = View.VISIBLE
                        binding.btnActivate.text = when (state.status) {
                            LicenseStatus.TRIAL_EXPIRED ->
                                getString(R.string.license_trial_expired_btn)
                            LicenseStatus.EXPORT_LIMIT_REACHED ->
                                getString(R.string.license_export_limit_btn)
                            else ->
                                getString(R.string.license_trial_active_btn)
                        }
                    }
                }
            }
        }
    }

    private fun showActivationDialog() {
        LicenseDialogs.showActivationDialog(this) {
            // Activation succeeded — UI will auto-update via StateFlow
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

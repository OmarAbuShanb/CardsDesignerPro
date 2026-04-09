package dev.anonymous.cardsdesignerpro.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.data.model.Template
import dev.anonymous.cardsdesignerpro.databinding.ActivityMainBinding
import dev.anonymous.cardsdesignerpro.databinding.DialogTemplateNameBinding
import dev.anonymous.cardsdesignerpro.ui.editor.EditorActivity
import dev.anonymous.cardsdesignerpro.ui.export.ExportCardsActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: TemplateAdapter

    // SAF launchers
    private var pendingExportTemplateId: String? = null

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importTemplate(it) }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let { pendingExportTemplateId?.let { id -> viewModel.exportTemplate(id, it) } }
        pendingExportTemplateId = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)



        setupRecyclerView()
        setupButtons()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = TemplateAdapter(
            onOpen = { template -> openEditor(template.id) },
            onRename = { template -> showRenameDialog(template) },
            onDuplicate = { template -> viewModel.duplicateTemplate(template.id) },
            onExport = { template ->
                pendingExportTemplateId = template.id
                exportLauncher.launch("${template.name}.zip")
            },
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
        binding.btnImportTemplate.setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.templates.collect { templates ->
                        val isFirstLoad = adapter.currentList.isEmpty() && templates.isNotEmpty()
                        adapter.submitList(templates) {
                            if (isFirstLoad) binding.rvTemplates.scheduleLayoutAnimation()
                        }
                        binding.tvEmpty.visibility = if (templates.isEmpty()) View.VISIBLE else View.GONE
                        binding.rvTemplates.visibility = if (templates.isEmpty()) View.GONE else View.VISIBLE
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
                            is MainEvent.ImportSuccess ->
                                snack(getString(R.string.import_success, event.name))
                            is MainEvent.ImportFailed ->
                                snack(getString(R.string.import_failed))
                        }
                        viewModel.consumeEvent()
                    }
                }
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showNewTemplateDialog() {
        val dialogBinding = DialogTemplateNameBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_new_template_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_next) { _, _ ->
                val name = dialogBinding.etName.text?.toString()?.trim()
                if (!name.isNullOrEmpty()) {
                    val id = viewModel.createTemplate(name)
                    openEditor(id)
                }
            }
            .show()
            .also { dialog ->
                // Enable Done key on keyboard
                dialogBinding.etName.setOnEditorActionListener { _, _, _ ->
                    val name = dialogBinding.etName.text?.toString()?.trim()
                    if (!name.isNullOrEmpty()) {
                        val id = viewModel.createTemplate(name)
                        dialog.dismiss()
                        openEditor(id)
                    }
                    true
                }
            }
    }

    private fun showRenameDialog(template: Template) {
        val dialogBinding = DialogTemplateNameBinding.inflate(layoutInflater)
        dialogBinding.etName.setText(template.name)
        dialogBinding.etName.selectAll()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_rename_template_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val name = dialogBinding.etName.text?.toString()?.trim()
                if (!name.isNullOrEmpty()) viewModel.renameTemplate(template.id, name)
            }
            .show()
    }

    private fun showNewDefaultTemplateDialog() {
        val dialogBinding = DialogTemplateNameBinding.inflate(layoutInflater)
        dialogBinding.etName.setText("قالب شبكة افتراضي")
        dialogBinding.etName.selectAll()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_edit_default_template)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val name = dialogBinding.etName.text?.toString()?.trim()
                if (!name.isNullOrEmpty()) {
                    viewModel.extractDefaultTemplate(name) { newId ->
                        if (newId != null) openEditor(newId)
                        else snack("فشل استخراج القالب الافتراضي")
                    }
                }
            }
            .show()
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

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun openEditor(templateId: String) {
        startActivity(
            Intent(this, EditorActivity::class.java)
                .putExtra(EditorActivity.EXTRA_TEMPLATE_ID, templateId)
        )
    }

    private fun snack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
}

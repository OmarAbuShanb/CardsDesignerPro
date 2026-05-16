package dev.anonymous.cardsdesignerpro.app.ui.main

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.anonymous.cardsdesignerpro.app.data.model.CardStyle
import dev.anonymous.cardsdesignerpro.app.data.model.Template
import dev.anonymous.cardsdesignerpro.app.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.app.data.repository.TemplateRepository
import dev.anonymous.cardsdesignerpro.app.data.serializer.TemplateZipManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = TemplateRepository(application)

    private val _templates = MutableStateFlow<List<Template>?>(null)
    val templates: StateFlow<List<Template>?> = _templates.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _event = MutableStateFlow<MainEvent?>(null)
    val event: StateFlow<MainEvent?> = _event.asStateFlow()

    init {
        loadTemplates()
        viewModelScope.launch { getDefaultTemplates() }
    }

    fun loadTemplates() {
        viewModelScope.launch {
            _templates.value = repo.getAll()
        }
    }

    fun reloadSingleTemplate(id: String) {
        viewModelScope.launch {
            val updated = repo.getById(id) ?: return@launch
            val currentList = _templates.value?.toMutableList() ?: mutableListOf()
            val index = currentList.indexOfFirst { it.id == id }
            if (index != -1) {
                // Only replace if version actually changed
                if (currentList[index].version != updated.version) {
                    currentList[index] = updated
                    _templates.value = currentList
                }
            } else {
                // If it's completely new, add it to the top
                _templates.value = listOf(updated) + currentList
            }
        }
    }

    fun createTemplate(name: String): String {
        val id = UUID.randomUUID().toString()
        val template = Template(
            id = id,
            name = name,
            card = CardStyle(),
            elements = listOf(TemplateElement.CardBackground())
        )
        viewModelScope.launch {
            repo.save(template)
            loadTemplates()
        }
        return id
    }

    fun renameTemplate(id: String, newName: String) {
        viewModelScope.launch {
            repo.rename(id, newName)
            loadTemplates()
        }
    }

    fun deleteTemplate(id: String) {
        viewModelScope.launch {
            repo.delete(id)
            loadTemplates()
        }
    }

    fun duplicateTemplate(id: String) {
        viewModelScope.launch {
            repo.duplicate(id)
            loadTemplates()
        }
    }

    fun exportTemplates(ids: List<String>, outputUri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            val templatesToExport = ids.mapNotNull { repo.getById(it) }
            val success = TemplateZipManager.exportTemplatesToZip(getApplication(), templatesToExport, outputUri)
            _event.value = if (success) MainEvent.ExportSuccess else MainEvent.ExportFailed
            _isLoading.value = false
        }
    }

    fun peekImport(inputUri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            val templates = TemplateZipManager.peekTemplatesFromZip(getApplication(), inputUri)
            if (templates != null && templates.isNotEmpty()) {
                _event.value = MainEvent.ShowImportDialog(inputUri, templates)
            } else {
                _event.value = MainEvent.ImportFailed
            }
            _isLoading.value = false
        }
    }

    fun confirmImport(inputUri: Uri, selectedIds: Set<String>) {
        viewModelScope.launch {
            _isLoading.value = true
            val imported = TemplateZipManager.importTemplatesFromZip(getApplication(), inputUri, selectedIds)
            if (imported != null && imported.isNotEmpty()) {
                imported.forEach { repo.importTemplate(it) }
                loadTemplates()
                _event.value = MainEvent.ImportSuccess(imported.size)
            } else {
                _event.value = MainEvent.ImportFailed
            }
            _isLoading.value = false
        }
    }

    private var defaultTemplatesCache: List<Pair<String, Template>>? = null

    suspend fun getDefaultTemplates(): List<Pair<String, Template>> {
        if (defaultTemplatesCache != null) return defaultTemplatesCache!!
        val templates = repo.getDefaultTemplates()
        defaultTemplatesCache = templates
        return templates
    }

    fun extractDefaultTemplate(sourceDir: String, name: String, onExtracted: (String?) -> Unit) {
        viewModelScope.launch {
            val extracted = repo.extractDefaultTemplate(sourceDir, name)
            if (extracted != null) {
                loadTemplates()
                onExtracted(extracted.id)
            } else {
                onExtracted(null)
            }
        }
    }

    fun consumeEvent() { _event.value = null }
}

sealed class MainEvent {
    object ExportSuccess : MainEvent()
    object ExportFailed : MainEvent()
    data class ShowImportDialog(val uri: Uri, val templates: List<Template>) : MainEvent()
    data class ImportSuccess(val count: Int) : MainEvent()
    object ImportFailed : MainEvent()
}

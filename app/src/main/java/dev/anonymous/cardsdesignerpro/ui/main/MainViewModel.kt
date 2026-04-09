package dev.anonymous.cardsdesignerpro.ui.main

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.anonymous.cardsdesignerpro.data.model.CardStyle
import dev.anonymous.cardsdesignerpro.data.model.Template
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.data.repository.TemplateRepository
import dev.anonymous.cardsdesignerpro.data.serializer.TemplateZipManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = TemplateRepository(application)

    private val _templates = MutableStateFlow<List<Template>>(emptyList())
    val templates: StateFlow<List<Template>> = _templates.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _event = MutableStateFlow<MainEvent?>(null)
    val event: StateFlow<MainEvent?> = _event.asStateFlow()

    init {
        loadTemplates()
    }

    fun loadTemplates() {
        viewModelScope.launch {
            _isLoading.value = true
            _templates.value = repo.getAll()
            _isLoading.value = false
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

    fun exportTemplate(id: String, outputUri: Uri) {
        viewModelScope.launch {
            val template = repo.getById(id) ?: return@launch
            val success = TemplateZipManager.exportToZip(getApplication(), template, outputUri)
            _event.value = if (success) MainEvent.ExportSuccess else MainEvent.ExportFailed
        }
    }

    fun importTemplate(inputUri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            val template = TemplateZipManager.importFromZip(getApplication(), inputUri)
            if (template != null) {
                repo.importTemplate(template)
                loadTemplates()
                _event.value = MainEvent.ImportSuccess(template.name)
            } else {
                _event.value = MainEvent.ImportFailed
            }
            _isLoading.value = false
        }
    }

    fun extractDefaultTemplate(name: String, onExtracted: (String?) -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            val extracted = repo.extractDefaultTemplate(name)
            if (extracted != null) {
                loadTemplates()
                onExtracted(extracted.id)
            } else {
                onExtracted(null)
            }
            _isLoading.value = false
        }
    }

    fun consumeEvent() { _event.value = null }
}

sealed class MainEvent {
    object ExportSuccess : MainEvent()
    object ExportFailed : MainEvent()
    data class ImportSuccess(val name: String) : MainEvent()
    object ImportFailed : MainEvent()
}

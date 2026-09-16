package dev.anonymous.cardsdesignerpro.app.ui.exportedfiles

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.net.toUri
import androidx.core.content.edit

data class PdfFileInfo(
    val uri: Uri,
    val name: String,
    val size: Long,
    val lastModified: Long
)

data class ExportedFilesUiState(
    val directoryUri: Uri? = null,
    val directoryName: String? = null,
    val files: List<PdfFileInfo> = emptyList(),
    val isLoading: Boolean = false,
    val isServerRunning: Boolean = false,
    val serverAddress: String? = null,
)

class ExportedFilesViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("export_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(ExportedFilesUiState())
    val uiState: StateFlow<ExportedFilesUiState> = _uiState.asStateFlow()

    private var server: LocalShareServer? = null

    init {
        val savedUri = prefs.getString("selected_dir_uri", null)
        val savedName = prefs.getString("selected_dir_name", null)
        if (savedUri != null && savedName != null) {
            _uiState.update {
                it.copy(
                    directoryUri = savedUri.toUri(),
                    directoryName = savedName
                )
            }
            loadFiles()
        }
    }

    fun setDirectory(uri: Uri, name: String) {
        prefs.edit {
            putString("selected_dir_uri", uri.toString())
                .putString("selected_dir_name", name)
        }

        _uiState.update {
            it.copy(directoryUri = uri, directoryName = name)
        }
        loadFiles()
    }

    fun loadFiles() {
        val dirUri = _uiState.value.directoryUri ?: return

        viewModelScope.launch {
            // Only show loading indicator on first load (avoid flash on resume)
            val showLoading = _uiState.value.files.isEmpty()
            if (showLoading) _uiState.update { it.copy(isLoading = true) }

            val files = withContext(Dispatchers.IO) {
                try {
                    val context = getApplication<Application>()
                    val treeDoc = DocumentFile.fromTreeUri(context, dirUri) ?: return@withContext emptyList()
                    treeDoc.listFiles()
                        .filter { it.isFile && it.name?.endsWith(".pdf", ignoreCase = true) == true }
                        .map { doc ->
                            PdfFileInfo(
                                uri = doc.uri,
                                name = doc.name ?: "unknown.pdf",
                                size = doc.length(),
                                lastModified = doc.lastModified()
                            )
                        }
                        .sortedByDescending { it.lastModified }
                } catch (_: Exception) {
                    emptyList()
                }
            }

            _uiState.update { it.copy(files = files, isLoading = false) }
            server?.updateFiles(files)
        }
    }

    fun startServer() {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val port = 8080
                val localServer = LocalShareServer(context, port)
                localServer.updateFiles(_uiState.value.files)
                localServer.start()
                server = localServer

                val ip = withContext(Dispatchers.IO) {
                    NetworkUtils.getLocalIpAddress()
                }
                val address = if (ip != null) "http://$ip:$port" else "http://localhost:$port"

                _uiState.update {
                    it.copy(
                        isServerRunning = true,
                        serverAddress = address
                    )
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(isServerRunning = false, serverAddress = null)
                }
            }
        }
    }

    fun stopServer() {
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
        _uiState.update {
            it.copy(isServerRunning = false, serverAddress = null)
        }
    }



    override fun onCleared() {
        super.onCleared()
        stopServer()
    }
}

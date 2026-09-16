package dev.anonymous.cardsdesignerpro.app.ui.exportedfiles

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import dev.anonymous.cardsdesignerpro.app.R
import dev.anonymous.cardsdesignerpro.app.databinding.ActivityExportedFilesBinding
import dev.anonymous.cardsdesignerpro.app.ui.viewer.PdfViewerActivity
import kotlinx.coroutines.launch

class ExportedFilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExportedFilesBinding
    private val viewModel: ExportedFilesViewModel by viewModels()
    private lateinit var adapter: ExportedFileAdapter
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)

            val docFile = DocumentFile.fromTreeUri(this, uri)
            val folderName = docFile?.name ?: uri.lastPathSegment ?: "Unknown"
            viewModel.setDirectory(uri, folderName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExportedFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupAdapter()
        setupFolderPicker()
        setupLocalShare()
        observeState()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupAdapter() {
        adapter = ExportedFileAdapter { fileInfo ->
            startActivity(Intent(this, PdfViewerActivity::class.java).apply {
                putExtra(PdfViewerActivity.EXTRA_PDF_URI, fileInfo.uri.toString())
            })
        }
        binding.rvExportedFiles.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.rvExportedFiles.adapter = adapter
    }

    private fun setupFolderPicker() {
        binding.btnChooseFolder.setOnClickListener {
            folderPicker.launch(viewModel.uiState.value.directoryUri)
        }
    }

    private fun setupLocalShare() {
        setupLocalShareListener()

        binding.btnCopyAddress.setOnClickListener {
            val address = viewModel.uiState.value.serverAddress ?: return@setOnClickListener
            copyToClipboard(getString(R.string.label_server_address), address)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Snackbar.make(binding.root, R.string.copied_to_clipboard, Snackbar.LENGTH_SHORT).show()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // Directory info
                    binding.tvFolderName.text = state.directoryName
                        ?: getString(R.string.label_not_selected)
                    binding.btnChooseFolder.text = getString(
                        if (state.directoryUri != null) R.string.btn_change_directory
                        else R.string.btn_choose_directory
                    )

                    // File list
                    adapter.submitList(state.files)
                    binding.rvExportedFiles.isVisible = state.files.isNotEmpty()
                    binding.layoutEmptyState.isVisible =
                        state.files.isEmpty() && !state.isLoading && state.directoryUri != null
                    binding.progressLoading.isVisible = state.isLoading

                    // Server status — update switch without triggering listener
                    val switch = binding.switchLocalShare
                    switch.isEnabled = true
                    if (switch.isChecked != state.isServerRunning) {
                        switch.setOnCheckedChangeListener(null)
                        switch.isChecked = state.isServerRunning
                        setupLocalShareListener()
                    }
                    // animateLayoutChanges on the parent handles the expand/collapse
                    binding.layoutServerInfo.isVisible = state.isServerRunning
                    if (state.isServerRunning) {
                        binding.tvServerAddress.text = state.serverAddress
                    }
                }
            }
        }
    }

    private fun handleSwitchToggle(isChecked: Boolean) {
        if (isChecked) {
            if (viewModel.uiState.value.directoryUri == null) {
                binding.switchLocalShare.isChecked = false
                Snackbar.make(binding.root, R.string.no_folder_selected_share, Snackbar.LENGTH_SHORT).show()
                return
            }
            val ip = NetworkUtils.getLocalIpAddress()
            if (ip == null) {
                binding.switchLocalShare.isChecked = false
                Snackbar.make(binding.root, R.string.no_wifi_connection, Snackbar.LENGTH_SHORT).show()
                return
            }
            binding.switchLocalShare.isEnabled = false
            viewModel.startServer()
            Snackbar.make(binding.root, R.string.server_started, Snackbar.LENGTH_SHORT).show()
        } else {
            if (viewModel.uiState.value.isServerRunning) {
                binding.switchLocalShare.isEnabled = false
                viewModel.stopServer()
                Snackbar.make(binding.root, R.string.server_stopped, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupLocalShareListener() {
        binding.switchLocalShare.setOnCheckedChangeListener { _, isChecked ->
            handleSwitchToggle(isChecked)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadFiles()
        registerNetworkCallback()
    }

    override fun onStop() {
        super.onStop()
        unregisterNetworkCallback()
        if (viewModel.uiState.value.isServerRunning) {
            viewModel.stopServer()
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                runOnUiThread {
                    if (viewModel.uiState.value.isServerRunning) {
                        viewModel.stopServer()
                        Snackbar.make(binding.root, R.string.no_wifi_connection, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        networkCallback = null
    }
}

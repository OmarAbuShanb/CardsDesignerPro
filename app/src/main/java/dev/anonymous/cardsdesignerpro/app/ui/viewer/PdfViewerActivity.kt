package dev.anonymous.cardsdesignerpro.app.ui.viewer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.pdf.viewer.fragment.PdfViewerFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.anonymous.cardsdesignerpro.app.databinding.ActivityPdfViewerBinding

class PdfViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PDF_URI = "extra_pdf_uri"
    }

    private lateinit var binding: ActivityPdfViewerBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val uri = when (intent.action) {
            android.content.Intent.ACTION_VIEW -> intent.data
            android.content.Intent.ACTION_SEND -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
            }
            else -> intent.getStringExtra(EXTRA_PDF_URI)?.toUri()
        } 

        if (uri == null || !isPdf(uri, intent.type)) {
            finish()
            return
        }

        // Persistent Permission with masked flags (safe to call even if already granted)
        if (uri.scheme == "content") {
            val takeFlags = intent.flags and (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            runCatching {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            }
        }

        val supportsJetpackPdf = if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.os.ext.SdkExtensions.getExtensionVersion(android.os.Build.VERSION_CODES.S) >= 19
        } else {
            false
        }

        if (supportsJetpackPdf) {
            // Android 12+ (API 31+) with required S Extension 19: Use Google's official Jetpack PdfViewer
            binding.pdfFragmentModern.visibility = android.view.View.VISIBLE

            val fragmentManager = supportFragmentManager
            val pdfViewerFragment =
                fragmentManager.findFragmentById(dev.anonymous.cardsdesignerpro.app.R.id.pdf_fragment_modern) as? PdfViewerFragment

            if (pdfViewerFragment != null) {
                // Must suppress lint here since we already manually verified the S Extension version above
                @android.annotation.SuppressLint("NewApi")
                pdfViewerFragment.documentUri = uri

                // Remove the annotation/edit FAB so the fragment can't re-show it on tap
                pdfViewerFragment.viewLifecycleOwnerLiveData.observe(this) {
                    val fab = pdfViewerFragment.view?.findViewById<android.view.View>(
                        resources.getIdentifier("edit_fab", "id", packageName)
                    )
                    (fab?.parent as? android.view.ViewGroup)?.removeView(fab)
                }
            } else {
                MaterialAlertDialogBuilder(this@PdfViewerActivity)
                    .setTitle("خطأ في التحميل")
                    .setMessage("فشل في تهيئة العارض الحديث.")
                    .setPositiveButton("موافق") { _, _ -> finish() }
                    .setOnDismissListener { finish() }
                    .show()
            }
        } else {
            // Android < 12 (API 26-30) or missing S Extensions: Use lightweight aFreakyElf PdfRendererView
            binding.tvQualityWarning.visibility = android.view.View.VISIBLE
            binding.pdfViewLegacy.visibility = android.view.View.VISIBLE
            binding.pdfViewLegacy.initWithUri(uri)
            binding.pdfViewLegacy.setMaxZoomScale(5f)
        }
    }

    private fun isPdf(uri: android.net.Uri, type: String?): Boolean {
        if (type != null && type != "*/*" && type == "application/pdf") return true
        val name = queryFileName(uri) ?: return false
        return name.endsWith(".pdf", ignoreCase = true)
    }

    private fun queryFileName(uri: android.net.Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (col < 0) null else c.getString(col)
        }
}

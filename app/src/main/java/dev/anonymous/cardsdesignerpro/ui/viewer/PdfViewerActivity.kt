package dev.anonymous.cardsdesignerpro.ui.viewer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import dev.anonymous.cardsdesignerpro.databinding.ActivityPdfViewerBinding

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

        val uri = intent.data
            ?: intent.getStringExtra(EXTRA_PDF_URI)?.toUri()
            ?: run {
                finish()
                return
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
                fragmentManager.findFragmentById(dev.anonymous.cardsdesignerpro.R.id.pdf_fragment_modern) as? androidx.pdf.viewer.fragment.PdfViewerFragment

            if (pdfViewerFragment != null) {
                // Must suppress lint here since we already manually verified the S Extension version above
                @android.annotation.SuppressLint("NewApi")
                pdfViewerFragment.documentUri = uri
            } else {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@PdfViewerActivity)
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
}

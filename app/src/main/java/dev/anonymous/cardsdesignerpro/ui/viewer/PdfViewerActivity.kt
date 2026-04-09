package dev.anonymous.cardsdesignerpro.ui.viewer

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.anonymous.cardsdesignerpro.databinding.ActivityPdfViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        val uri = intent.data ?: intent.getStringExtra(EXTRA_PDF_URI)?.let { Uri.parse(it) } ?: run { finish(); return }

        // Fix: PDFView.fromStream() is ASYNC — the stream is closed by use{} before
        // the decoder reads it → "Stream Closed" IOException.
        // Solution: read ALL bytes eagerly on IO thread first, then hand a ByteArray
        // to fromBytes() which is safe to use asynchronously.
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null) {
                withContext(Dispatchers.Main) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@PdfViewerActivity)
                        .setTitle("تنبيه أمان الأندرويد")
                        .setMessage("اكتمل حفظ الملف بنجاح وتم وضعه في المجلد الذي اخترته.\n\nبسبب قيود نظام أندرويد الأمنية للملفات الخارجية، لا يمكننا فتحه لك من هنا بعد إغلاق التطبيق. يرجى التوجه لمدير الملفات (أو مجلد التنزيلات) وفتحه من هناك.")
                        .setPositiveButton("موافق") { _, _ -> finish() }
                        .setOnDismissListener { finish() }
                        .show()
                }
                return@launch
            }

            binding.pdfView
                .fromBytes(bytes)
                .enableSwipe(true)
                .swipeHorizontal(false)
                .enableDoubletap(true)
                .defaultPage(0)
                .spacing(12)
                .load()
                
            binding.pdfView.maxZoom = 6.0f
            binding.pdfView.midZoom = 3.0f
        }
    }
}

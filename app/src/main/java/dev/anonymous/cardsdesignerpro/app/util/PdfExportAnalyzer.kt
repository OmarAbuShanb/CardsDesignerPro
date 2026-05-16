package dev.anonymous.cardsdesignerpro.app.util

import android.util.Log
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject

/**
 * Debug-only PDF inspection utility.
 *
 * Logs resource counts after export to verify that the refactored pipeline
 * correctly reuses Form XObjects, caches images, and avoids per-card bitmap duplication.
 *
 * Guarded by [BuildConfig.DEBUG] at the call site.
 */
object PdfExportAnalyzer {

    private const val TAG = "PdfExportAnalyzer"

    /**
     * Analyzes a [PDDocument] and logs a summary of its resources.
     * Call before `doc.save()` while the document is still in memory.
     */
    fun analyze(doc: PDDocument) {
        val pageCount = doc.numberOfPages
        var totalImageXObjects = 0
        var totalFormXObjects = 0
        var totalFonts = 0
        var maxImageWidth = 0
        var maxImageHeight = 0
        val fontNames = mutableSetOf<String>()
        val imageKeys = mutableSetOf<String>()  // Track unique image resource names
        val formKeys = mutableSetOf<String>()

        for (i in 0 until pageCount) {
            val page = doc.getPage(i)
            val resources = page.resources ?: continue

            // Count image XObjects
            resources.xObjectNames?.forEach { name ->
                runCatching {
                    val xObj = resources.getXObject(name)
                    when (xObj) {
                        is PDImageXObject -> {
                            totalImageXObjects++
                            imageKeys.add(name.name)
                            if (xObj.width > maxImageWidth) maxImageWidth = xObj.width
                            if (xObj.height > maxImageHeight) maxImageHeight = xObj.height
                        }
                        is PDFormXObject -> {
                            totalFormXObjects++
                            formKeys.add(name.name)
                        }
                    }
                }
            }

            // Count fonts
            resources.fontNames?.forEach { name ->
                runCatching {
                    val font = resources.getFont(name)
                    totalFonts++
                    fontNames.add("${name.name}=${font?.name ?: "unknown"}")
                }
            }
        }

        val uniqueImages = imageKeys.size
        val uniqueForms = formKeys.size
        val uniqueFontCount = fontNames.size

        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "  PDF Export Analysis")
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "  Pages:             $pageCount")
        Log.i(TAG, "  ─── XObjects ───")
        Log.i(TAG, "  Image XObjects:    $totalImageXObjects (unique refs: $uniqueImages)")
        Log.i(TAG, "  Form XObjects:     $totalFormXObjects (unique refs: $uniqueForms)")
        Log.i(TAG, "  Largest image:     ${maxImageWidth}x${maxImageHeight}")
        Log.i(TAG, "  ─── Fonts ───")
        Log.i(TAG, "  Font refs:         $totalFonts (unique: $uniqueFontCount)")
        fontNames.forEach { Log.i(TAG, "    • $it") }
        Log.i(TAG, "  ─── Duplicate Check ───")
        if (totalImageXObjects > uniqueImages) {
            Log.w(TAG, "  ⚠ ${totalImageXObjects - uniqueImages} duplicate image refs detected")
        } else {
            Log.i(TAG, "  ✓ No duplicate image XObjects")
        }
        if (totalFormXObjects > 0) {
            Log.i(TAG, "  ✓ Static template uses Form XObjects (reused)")
        } else {
            Log.w(TAG, "  ⚠ No Form XObjects — using bitmap fallback for static template")
        }
        Log.i(TAG, "═══════════════════════════════════════════════════")
    }
}

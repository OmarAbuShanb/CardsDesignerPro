package dev.anonymous.cardsdesignerpro.data.serializer

import android.content.Context
import android.net.Uri
import dev.anonymous.cardsdesignerpro.data.model.Template
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Handles ZIP-based export and import of templates, including their asset images.
 *
 * ZIP structure:
 * ```
 * template.json        <- serialized Template (version=1)
 * assets/              <- folder with referenced local image files
 *   abc123.jpg
 *   ...
 * ```
 */
object TemplateZipManager {

    private const val JSON_ENTRY = "template.json"
    private const val IMAGES_DIR = "images/"

    /**
     * Exports [template] to a ZIP file written to [outputUri] via SAF.
     * All local image paths referenced in the template are bundled under `assets/`.
     * Returns true on success.
     */
    suspend fun exportToZip(
        context: Context,
        template: Template,
        outputUri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val json = AppJson.encode(template)
            val localPaths = collectLocalPaths(template)

            context.contentResolver.openOutputStream(outputUri)?.use { rawOut ->
                ZipOutputStream(rawOut.buffered()).use { zip ->
                    // Write template JSON
                    zip.putNextEntry(ZipEntry(JSON_ENTRY))
                    zip.write(json.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    // Write referenced images
                    for (path in localPaths) {
                        val file = File(path)
                        if (file.exists()) {
                            zip.putNextEntry(ZipEntry("$IMAGES_DIR${file.name}"))
                            FileInputStream(file).use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }
            true
        }.getOrElse { e ->
            e.printStackTrace()
            false
        }
    }

    /**
     * Imports a template from a ZIP at [inputUri].
     * Images are extracted into `filesDir/templates/<id>/images/`.
     * Returns the imported [Template] or null on failure.
     */
    suspend fun importFromZip(
        context: Context,
        inputUri: Uri
    ): Template? = withContext(Dispatchers.IO) {
        runCatching {
            var rawJson: String? = null
            val imageBytes = mutableMapOf<String, ByteArray>() // name -> bytes

            context.contentResolver.openInputStream(inputUri)?.use { rawIn ->
                ZipInputStream(rawIn.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == JSON_ENTRY -> rawJson = zip.readBytes().toString(Charsets.UTF_8)
                            entry.name.startsWith(IMAGES_DIR) && !entry.isDirectory -> {
                                val name = entry.name.removePrefix(IMAGES_DIR)
                                if (name.isNotEmpty()) imageBytes[name] = zip.readBytes()
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            val json = rawJson ?: return@runCatching null
            var template = AppJson.decode(json)

            // Save extracted images and fix paths
            if (imageBytes.isNotEmpty()) {
                val imageDir = File(context.filesDir, "templates/${template.id}/images")
                imageDir.mkdirs()
                imageBytes.forEach { (name, bytes) ->
                    File(imageDir, name).writeBytes(bytes)
                }
                template = rewriteImagePaths(template, imageDir.absolutePath)
            }

            template
        }.getOrElse { e ->
            e.printStackTrace()
            null
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun collectLocalPaths(template: Template): Set<String> {
        val paths = mutableSetOf<String>()
        template.card.backgroundImagePath?.let { if (!it.startsWith("pack:")) paths.add(it) }
        fun collect(elements: List<dev.anonymous.cardsdesignerpro.data.model.TemplateElement>) {
            elements.forEach { el ->
                when (el) {
                    is dev.anonymous.cardsdesignerpro.data.model.TemplateElement.ImageElement ->
                        if (!el.imagePath.startsWith("pack:")) paths.add(el.imagePath)
                    is dev.anonymous.cardsdesignerpro.data.model.TemplateElement.QrElement ->
                        el.logoPath?.let { if (!it.startsWith("pack:")) paths.add(it) }
                    is dev.anonymous.cardsdesignerpro.data.model.TemplateElement.BackgroundDecorationElement ->
                        el.customImagePath?.let { if (!it.startsWith("pack:")) paths.add(it) }
                    else -> Unit
                }
            }
        }
        collect(template.elements)
        template.backElements?.let { collect(it) }
        return paths
    }

    private fun rewriteImagePaths(template: Template, imageDir: String): Template {
        fun rewrite(path: String?): String? =
            if (path == null || path.startsWith("pack:")) path
            else "$imageDir/${java.io.File(path).name}"

        val newCard = template.card.copy(
            backgroundImagePath = rewrite(template.card.backgroundImagePath)
        )

        fun rewriteElements(elements: List<dev.anonymous.cardsdesignerpro.data.model.TemplateElement>) =
            elements.map { el ->
                when (el) {
                    is dev.anonymous.cardsdesignerpro.data.model.TemplateElement.ImageElement ->
                        el.copy(imagePath = rewrite(el.imagePath) ?: el.imagePath)
                    is dev.anonymous.cardsdesignerpro.data.model.TemplateElement.QrElement ->
                        el.copy(logoPath = rewrite(el.logoPath))
                    is dev.anonymous.cardsdesignerpro.data.model.TemplateElement.BackgroundDecorationElement ->
                        el.copy(customImagePath = rewrite(el.customImagePath))
                    else -> el
                }
            }

        return template.copy(
            card = newCard,
            elements = rewriteElements(template.elements),
            backElements = template.backElements?.let { rewriteElements(it) }
        )
    }
}

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
    private const val FONTS_DIR = "fonts/"

    /**
     * Exports a list of templates to a ZIP file.
     */
    suspend fun exportTemplatesToZip(
        context: Context,
        templates: List<Template>,
        outputUri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(outputUri)?.use { rawOut ->
                ZipOutputStream(rawOut.buffered()).use { zip ->
                    for (template in templates) {
                        val basePath = "${template.id}/"
                        val json = AppJson.encode(template)
                        zip.putNextEntry(ZipEntry(basePath + JSON_ENTRY))
                        zip.write(json.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()

                        val localPaths = collectLocalPaths(template)
                        for (path in localPaths) {
                            val file = File(path)
                            if (file.exists()) {
                                zip.putNextEntry(ZipEntry(basePath + IMAGES_DIR + file.name))
                                FileInputStream(file).use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }

                        // Export custom fonts
                        val fontsDir = File(context.filesDir, "templates/${template.id}/fonts")
                        if (fontsDir.exists()) {
                            fontsDir.listFiles()?.forEach { fontFile ->
                                if (fontFile.isFile) {
                                    zip.putNextEntry(ZipEntry(basePath + FONTS_DIR + fontFile.name))
                                    FileInputStream(fontFile).use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                            }
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

    /** Reads only the JSON templates from the ZIP to allow the user to select what to import. */
    suspend fun peekTemplatesFromZip(
        context: Context,
        inputUri: Uri
    ): List<Template>? = withContext(Dispatchers.IO) {
        runCatching {
            val list = mutableListOf<Template>()
            context.contentResolver.openInputStream(inputUri)?.use { rawIn ->
                ZipInputStream(rawIn.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name.endsWith(JSON_ENTRY)) {
                            val json = zip.readBytes().toString(Charsets.UTF_8)
                            runCatching { AppJson.decode(json) }.getOrNull()?.let { list.add(it) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            list
        }.getOrElse { e ->
            e.printStackTrace()
            null
        }
    }

    /** Imports only the selected templates, overwriting existing ones. */
    suspend fun importTemplatesFromZip(
        context: Context,
        inputUri: Uri,
        selectedIds: Set<String>
    ): List<Template>? = withContext(Dispatchers.IO) {
        if (selectedIds.isEmpty()) return@withContext emptyList()
        runCatching {
            // Group bytes by template id
            val imageBytes = mutableMapOf<String, MutableMap<String, ByteArray>>() // id -> name -> bytes
            val fontBytes = mutableMapOf<String, MutableMap<String, ByteArray>>()  // id -> name -> bytes
            val jsonStrings = mutableMapOf<String, String>()

            context.contentResolver.openInputStream(inputUri)?.use { rawIn ->
                ZipInputStream(rawIn.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val parts = entry.name.split("/")
                        if (parts.isNotEmpty()) {
                            val id = parts[0]
                            if (id in selectedIds) {
                                if (entry.name.endsWith(JSON_ENTRY)) {
                                    jsonStrings[id] = zip.readBytes().toString(Charsets.UTF_8)
                                } else if (entry.name.contains(IMAGES_DIR) && !entry.isDirectory) {
                                    val name = entry.name.substringAfter(IMAGES_DIR)
                                    if (name.isNotEmpty()) {
                                        if (imageBytes[id] == null) imageBytes[id] = mutableMapOf()
                                        imageBytes[id]!![name] = zip.readBytes()
                                    }
                                } else if (entry.name.contains(FONTS_DIR) && !entry.isDirectory) {
                                    val name = entry.name.substringAfter(FONTS_DIR)
                                    if (name.isNotEmpty()) {
                                        if (fontBytes[id] == null) fontBytes[id] = mutableMapOf()
                                        fontBytes[id]!![name] = zip.readBytes()
                                    }
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            val imported = mutableListOf<Template>()
            for ((id, json) in jsonStrings) {
                var template = AppJson.decode(json)
                val images = imageBytes[id]
                if (images != null && images.isNotEmpty()) {
                    val imageDir = File(context.filesDir, "templates/$id/images")
                    imageDir.mkdirs()
                    images.forEach { (name, bytes) ->
                        File(imageDir, name).writeBytes(bytes)
                    }
                    template = rewriteImagePaths(template, imageDir.absolutePath)
                }
                // Restore custom fonts
                val fonts = fontBytes[id]
                if (fonts != null && fonts.isNotEmpty()) {
                    val fontDir = File(context.filesDir, "templates/$id/fonts")
                    fontDir.mkdirs()
                    fonts.forEach { (name, bytes) ->
                        File(fontDir, name).writeBytes(bytes)
                    }
                }
                imported.add(template)
            }
            imported
        }.getOrElse { e ->
            e.printStackTrace()
            null
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun collectLocalPaths(template: Template): Set<String> {
        val paths = mutableSetOf<String>()
        template.card.backgroundImagePath?.let { if (!it.startsWith("pack:")) paths.add(it) }
        template.backCard?.backgroundImagePath?.let { if (!it.startsWith("pack:")) paths.add(it) }
        fun collect(elements: List<dev.anonymous.cardsdesignerpro.data.model.TemplateElement>) {
            elements.forEach { el ->
                when (el) {
                    is dev.anonymous.cardsdesignerpro.data.model.TemplateElement.ImageElement ->
                        if (!el.imagePath.startsWith("pack:")) paths.add(el.imagePath)
                    is dev.anonymous.cardsdesignerpro.data.model.TemplateElement.QrElement ->
                        el.logoPath?.let { if (!it.startsWith("pack:")) paths.add(it) }
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
        val newBackCard = template.backCard?.copy(
            backgroundImagePath = rewrite(template.backCard.backgroundImagePath)
        )

        fun rewriteElements(elements: List<dev.anonymous.cardsdesignerpro.data.model.TemplateElement>) =
            elements.map { el ->
                when (el) {
                    is dev.anonymous.cardsdesignerpro.data.model.TemplateElement.ImageElement ->
                        el.copy(imagePath = rewrite(el.imagePath) ?: el.imagePath)
                    is dev.anonymous.cardsdesignerpro.data.model.TemplateElement.QrElement ->
                        el.copy(logoPath = rewrite(el.logoPath))
                    else -> el
                }
            }

        return template.copy(
            card = newCard,
            backCard = newBackCard,
            elements = rewriteElements(template.elements),
            backElements = template.backElements?.let { rewriteElements(it) }
        )
    }
}

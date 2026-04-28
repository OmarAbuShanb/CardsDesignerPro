package dev.anonymous.cardsdesignerpro.data.repository

import android.content.Context
import dev.anonymous.cardsdesignerpro.data.model.Template
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.data.serializer.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persists templates as JSON files under `filesDir/templates/<id>/template.json`.
 * All operations run on the IO dispatcher. Thread-safe for sequential use.
 */
class TemplateRepository(private val context: Context) {

    private val rootDir: File get() = File(context.filesDir, "templates")

    private fun templateDir(id: String) = File(rootDir, id)
    private fun jsonFile(id: String) = File(templateDir(id), "template.json")
    private fun imageDir(id: String) = File(templateDir(id), "images")
    private fun fontDir(id: String) = File(templateDir(id), "fonts")

    // ── CRUD ─────────────────────────────────────────────────────────────────

    /** Loads all saved templates, sorted by last-modified time (newest first). */
    suspend fun getAll(): List<Template> = withContext(Dispatchers.IO) {
        rootDir.mkdirs()
        rootDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { dir ->
                runCatching { AppJson.decode(File(dir, "template.json").readText()) }
                    .getOrNull()
            }
            ?: emptyList()
    }

    /** Loads a single template by id, or null if not found. */
    suspend fun getById(id: String): Template? = withContext(Dispatchers.IO) {
        runCatching { AppJson.decode(jsonFile(id).readText()) }.getOrNull()
    }

    /** Saves (creates or overwrites) a template. */
    suspend fun save(template: Template) = withContext(Dispatchers.IO) {
        val dir = templateDir(template.id)
        dir.mkdirs()
        jsonFile(template.id).writeText(AppJson.encode(template))
    }

    /** Deletes a template and all of its assets, cleaning up orphan images. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        templateDir(id).deleteRecursively()
    }

    /** Renames a template in-place (name field only, id unchanged). */
    suspend fun rename(id: String, newName: String) = withContext(Dispatchers.IO) {
        val template = getById(id) ?: return@withContext
        save(template.copy(name = newName))
        // Touch the directory so sorting (lastModified) reflects the rename
        templateDir(id).setLastModified(System.currentTimeMillis())
    }

    /**
     * Creates a deep copy of the template with a new id.
     * Local image assets are copied to the new template directory.
     * Returns the duplicate.
     */
    suspend fun duplicate(id: String): Template? = withContext(Dispatchers.IO) {
        val original = getById(id) ?: return@withContext null
        val newId = System.currentTimeMillis().toString()
        val suffix = " (نسخة)"
        val newName = if (original.name.endsWith(suffix)) original.name else original.name + suffix

        val oldImageDir = imageDir(id)
        val newImageDir = imageDir(newId)

        // Copy image files and rewrite paths
        val pathMap = mutableMapOf<String, String>()
        if (oldImageDir.exists()) {
            newImageDir.mkdirs()
            oldImageDir.listFiles()?.forEach { file ->
                val newFile = File(newImageDir, file.name)
                file.copyTo(newFile, overwrite = true)
                pathMap[file.absolutePath] = newFile.absolutePath
            }
        }

        fun rewrite(path: String?): String? = path?.let { pathMap[it] ?: it }

        val newCard = original.card.copy(
            backgroundImagePath = rewrite(original.card.backgroundImagePath))
        val newBackCard = original.backCard?.copy(
            backgroundImagePath = rewrite(original.backCard.backgroundImagePath))

        fun rewriteElements(elements: List<TemplateElement>) = elements.map { el ->
            when (el) {
                is TemplateElement.ImageElement -> el.copy(
                    imagePath = rewrite(el.imagePath) ?: el.imagePath
                )
                is TemplateElement.QrElement -> el.copy(logoPath = rewrite(el.logoPath))
                else -> el
            }
        }

        val duplicate = original.copy(
            id = newId,
            name = newName,
            card = newCard,
            backCard = newBackCard,
            elements = rewriteElements(original.elements),
            backElements = original.backElements?.let { rewriteElements(it) }
        )
        save(duplicate)
        duplicate
    }

    /** Saves the template and then imports it (used after ZIP import). */
    suspend fun importTemplate(template: Template): Template = withContext(Dispatchers.IO) {
        save(template)
        template
    }

    /** Gets a list of all available default templates from assets. */
    suspend fun getDefaultTemplates(): List<Pair<String, Template>> = withContext(Dispatchers.IO) {
        val dirs = context.assets.list("default_templates") ?: return@withContext emptyList()
        val result = mutableListOf<Pair<String, Template>>()
        for (dir in dirs) {
            val jsonStr = runCatching {
                context.assets.open("default_templates/$dir/template.json").bufferedReader().use { it.readText() }
            }.getOrNull()
            if (jsonStr != null) {
                val template = runCatching { AppJson.decode(jsonStr) }.getOrNull()
                if (template != null) {
                    result.add(dir to template)
                }
            }
        }
        result
    }

    /** Extracts the default template from assets and saves it as a new template with the given name. */
    suspend fun extractDefaultTemplate(sourceDir: String, name: String): Template? = withContext(Dispatchers.IO) {
        val jsonStr = runCatching {
            context.assets.open("default_templates/$sourceDir/template.json").bufferedReader()
                .use { it.readText() }
        }.getOrNull() ?: return@withContext null

        val newId = System.currentTimeMillis().toString()
        val defaultTemplate =
            runCatching { AppJson.decode(jsonStr) }.getOrNull() ?: return@withContext null
            
        // Copy SVGs/images from assets to local storage
        val imageDir = getOrCreateImageDir(newId)
        val assetsImageDir = "default_templates/$sourceDir/images"
        runCatching {
            context.assets.list(assetsImageDir)?.forEach { fileName ->
                val outFile = File(imageDir, fileName)
                context.assets.open("$assetsImageDir/$fileName").use { inStream ->
                    outFile.outputStream().use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
            }
        }
        
        fun rewrite(path: String?): String? {
            if (path == null || path.startsWith("pack:")) return path
            if (path.startsWith("images/")) {
                return File(imageDir, path.removePrefix("images/")).absolutePath
            }
            return path
        }
        
        fun rewriteElements(elements: List<TemplateElement>) = elements.map { el ->
            when (el) {
                is TemplateElement.ImageElement -> el.copy(imagePath = rewrite(el.imagePath) ?: el.imagePath)
                is TemplateElement.QrElement -> el.copy(logoPath = rewrite(el.logoPath))
                else -> el
            }
        }
        
        val extractedCard = defaultTemplate.card.copy(
            backgroundImagePath = rewrite(defaultTemplate.card.backgroundImagePath))
        val extractedBackCard = defaultTemplate.backCard?.copy(
            backgroundImagePath = rewrite(defaultTemplate.backCard.backgroundImagePath))

        val extracted = defaultTemplate.copy(
            id = newId,
            name = name,
            card = extractedCard,
            backCard = extractedBackCard,
            elements = rewriteElements(defaultTemplate.elements),
            backElements = defaultTemplate.backElements?.let { rewriteElements(it) }
        )
        save(extracted)
        extracted
    }

    /**
     * Returns the image directory for a given template id.
     * Creates it if it doesn't exist.
     */
    fun getOrCreateImageDir(id: String): File = imageDir(id).also { it.mkdirs() }

    /** Returns the fonts directory for a given template, creating it if needed. */
    fun getOrCreateFontDir(id: String): File = fontDir(id).also { it.mkdirs() }

    /** Lists custom font files (.ttf / .otf) stored in this template's fonts dir. */
    fun getCustomFonts(id: String): List<File> {
        val dir = fontDir(id)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter {
            val ext = it.extension.lowercase()
            ext == "ttf" || ext == "otf"
        }?.sortedBy { it.name } ?: emptyList()
    }

    /**
     * Deletes image files inside a template's image directory that are no longer
     * referenced by any template element (orphan cleanup).
     */
    suspend fun cleanOrphanImages(template: Template) = withContext(Dispatchers.IO) {
        val dir = imageDir(template.id)
        if (!dir.exists()) return@withContext
        val referencedFiles = collectAllLocalPaths(template).map { File(it).name }.toSet()
        dir.listFiles()?.forEach { file ->
            if (file.name !in referencedFiles) file.delete()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun collectAllLocalPaths(template: Template): Set<String> {
        val paths = mutableSetOf<String>()
        template.card.backgroundImagePath?.let { if (!it.startsWith("pack:")) paths.add(it) }
        template.backCard?.backgroundImagePath?.let { if (!it.startsWith("pack:")) paths.add(it) }
        fun collectFromElements(elements: List<TemplateElement>) {
            elements.forEach { el ->
                when (el) {
                    is TemplateElement.ImageElement -> if (!el.imagePath.startsWith("pack:")) paths.add(el.imagePath)
                    is TemplateElement.QrElement -> el.logoPath?.let { if (!it.startsWith("pack:")) paths.add(it) }
                    else -> Unit
                }
            }
        }
        collectFromElements(template.elements)
        template.backElements?.let { collectFromElements(it) }
        return paths
    }
}

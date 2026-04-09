package dev.anonymous.cardsdesignerpro.ui.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.anonymous.cardsdesignerpro.data.model.CardSide
import dev.anonymous.cardsdesignerpro.data.model.Template
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.data.repository.TemplateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class EditorUiState(
    val template: Template,
    val selectedElementId: String? = null,
    val hasUnsavedChanges: Boolean = false,
    val hasOutOfBoundsElements: Boolean = false,
    /** Set to true momentarily when the active side switches — triggers full list refresh. */
    val sideSwitched: Boolean = false,
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = TemplateRepository(application)

    private val _uiState = MutableStateFlow(
        EditorUiState(
            template = Template(id = "", name = "", card = dev.anonymous.cardsdesignerpro.data.model.CardStyle())
        )
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var isInitialized = false

    // ── Initialization ────────────────────────────────────────────────────────

    fun init(templateId: String) {
        if (isInitialized) return
        isInitialized = true
        viewModelScope.launch {
            val template = repo.getById(templateId) ?: return@launch
            _uiState.value = EditorUiState(
                template = template,
                selectedElementId = currentSideElements(template).firstOrNull()?.id
            )
        }
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    fun save() {
        viewModelScope.launch {
            repo.save(currentTemplate)
            _uiState.value = uiState.value.copy(hasUnsavedChanges = false)
        }
    }

    fun saveIfNeeded() {
        if (uiState.value.hasUnsavedChanges) save()
    }

    fun discardAndCleanup() {
        viewModelScope.launch {
            repo.cleanOrphanImages(currentTemplate)
        }
    }

    // ── Dual-side control ─────────────────────────────────────────────────────

    /**
     * Enables or disables the back side.
     * When enabling for the first time, initialises backElements with a CardBackground.
     */
    fun toggleBackSide() {
        val template = currentTemplate
        val nowEnabled = !template.isBackSideEnabled
        mutateTemplate { t ->
            t.copy(
                isBackSideEnabled = nowEnabled,
                backElements = if (nowEnabled && t.backElements == null) {
                    listOf(TemplateElement.CardBackground())
                } else {
                    t.backElements
                },
                // If disabling, switch back to FRONT
                activeSide = if (nowEnabled) t.activeSide else CardSide.FRONT
            )
        }
        // If we just disabled the back, clear any back-side selection
        if (!nowEnabled && uiState.value.template.activeSide == CardSide.BACK) {
            update(uiState.value.copy(selectedElementId = null, sideSwitched = true))
        }
    }

    /** Switches the active editing face and fires a side-switch signal. */
    fun setActiveSide(side: CardSide) {
        if (uiState.value.template.activeSide == side) return
        val prev = uiState.value
        update(prev.copy(
            template = prev.template.copy(activeSide = side),
            selectedElementId = null,
            hasUnsavedChanges = true,
            sideSwitched = true,
        ))
    }

    /** Clears the sideSwitched flag after the fragment has consumed it. */
    fun consumeSideSwitched() {
        if (uiState.value.sideSwitched)
            update(uiState.value.copy(sideSwitched = false))
    }

    // ── Template-level mutations ──────────────────────────────────────────────

    fun renameTemplate(newName: String) = mutateTemplate { it.copy(name = newName) }

    fun updateCardBackgroundColor(color: String) =
        mutateTemplate { it.copy(card = it.card.copy(backgroundColor = color)) }

    fun updateCardBackgroundImage(path: String?) =
        mutateTemplate { it.copy(card = it.card.copy(backgroundImagePath = path)) }

    /**
     * Adjusts the card height ratio, clamping to [0.2, 2.0].
     * Also resizes Frame and BackgroundDecoration to match the new card dimensions.
     */
    fun updateCardHeightRatio(ratio: Float) {
        val clamped = ratio.coerceIn(0.2f, 1.5f)   // max = widthDp × 1.5 as per UX requirement
        mutateTemplate { template ->
            val newCard = template.card.copy(heightRatio = clamped)
            fun resizeElements(elements: List<TemplateElement>) = elements.map { el ->
                when (el) {
                    is TemplateElement.FrameElement ->
                        el.copy(x = 0f, y = 0f, width = newCard.widthDp, height = newCard.widthDp * clamped)
                    is TemplateElement.BackgroundDecorationElement ->
                        el.copy(x = 0f, y = 0f, width = newCard.widthDp, height = newCard.widthDp * clamped)
                    else -> el
                }
            }
            template.copy(
                card = newCard,
                elements = resizeElements(template.elements),
                backElements = template.backElements?.let { resizeElements(it) }
            )
        }
        checkOutOfBounds()
    }

    // ── Element additions ─────────────────────────────────────────────────────

    private fun centerX(elW: Float) = (currentTemplate.card.widthDp - elW) / 2f
    private fun centerY(elH: Float) =
        (currentTemplate.card.widthDp * currentTemplate.card.heightRatio - elH) / 2f

    fun addTextElement() = addElement(
        TemplateElement.TextElement(
            id = newId(), x = centerX(160f), y = centerY(40f), width = 160f, height = 40f
        )
    )

    fun addUsernameElement() = addElement(
        TemplateElement.UsernameElement(
            id = newId(), x = centerX(160f), y = centerY(40f), width = 160f, height = 40f
        )
    )

    fun addPasswordElement() = addElement(
        TemplateElement.PasswordElement(
            id = newId(), x = centerX(160f), y = centerY(40f), width = 160f, height = 40f
        )
    )

    fun addQrElement() {
        val card = currentTemplate.card
        // Initial size = 50% of the shorter card dimension so it fits any card aspect ratio
        val initSize = minOf(card.widthDp, card.widthDp * card.heightRatio) * 0.5f
        addElement(
            TemplateElement.QrElement(
                id = newId(), x = centerX(initSize), y = centerY(initSize),
                width = initSize, height = initSize
            )
        )
    }

    fun addDateElement() {
        if (hasDateElement()) return
        addElement(
            TemplateElement.DateElement(
                id = newId(), x = centerX(160f), y = centerY(40f), width = 160f, height = 40f
            )
        )
    }

    fun addFrameElement() {
        val card = currentTemplate.card
        addElement(
            TemplateElement.FrameElement(
                id = newId(), x = 0f, y = 0f,
                width = card.widthDp, height = card.widthDp * card.heightRatio
            )
        )
        enforceLayerOrder()
    }

    fun addBackgroundDecoration() {
        addElement(TemplateElement.BackgroundDecorationElement())
        enforceLayerOrder()
    }

    fun addImageElement(imagePath: String, srcW: Int = 0, srcH: Int = 0) {
        val card = currentTemplate.card
        val cardW = card.widthDp
        val cardH = card.widthDp * card.heightRatio
        
        val maxW = cardW * 0.5f
        val maxH = cardH * 0.5f
        var elW = maxW
        var elH = maxH
        if (srcW > 0 && srcH > 0) {
            val aspect = srcW.toFloat() / srcH.toFloat()
            if (maxW / aspect <= maxH) {
                elW = maxW
                elH = maxW / aspect
            } else {
                elH = maxH
                elW = maxH * aspect
            }
        }
        
        addElement(
            TemplateElement.ImageElement(
                id = newId(),
                x = centerX(elW), y = (centerY(elH)).coerceAtLeast(0f),
                width = elW, height = elH,
                imagePath = imagePath
            )
        )
    }

    // ── Element CRUD ──────────────────────────────────────────────────────────

    fun deleteElement(id: String) {
        val prev = uiState.value
        val newElements = currentSideElements(prev.template).filterNot { it.id == id }
        val newSelected = if (prev.selectedElementId == id)
            newElements.firstOrNull()?.id else prev.selectedElementId
        update(prev.copy(
            template = setSideElements(prev.template, newElements),
            selectedElementId = newSelected,
            hasUnsavedChanges = true
        ))
        checkOutOfBounds()
    }

    fun toggleVisibility(id: String) {
        val wasVisible = currentElements.firstOrNull { it.id == id }?.isVisible ?: return
        mutateElement(id) { el ->
            when (el) {
                is TemplateElement.TextElement -> el.copy(isVisible = !el.isVisible)
                is TemplateElement.UsernameElement -> el.copy(isVisible = !el.isVisible)
                is TemplateElement.PasswordElement -> el.copy(isVisible = !el.isVisible)
                is TemplateElement.ImageElement -> el.copy(isVisible = !el.isVisible)
                is TemplateElement.QrElement -> el.copy(isVisible = !el.isVisible)
                is TemplateElement.DateElement -> el.copy(isVisible = !el.isVisible)
                is TemplateElement.FrameElement -> el.copy(isVisible = !el.isVisible)
                is TemplateElement.BackgroundDecorationElement -> el.copy(isVisible = !el.isVisible)
                is TemplateElement.CardBackground -> el
            }
        }
        if (wasVisible && uiState.value.selectedElementId == id) selectElement(null)
    }

    fun selectElement(id: String?) {
        update(uiState.value.copy(selectedElementId = id))
    }

    fun updateElement(element: TemplateElement) {
        val prev = uiState.value
        val newList = currentSideElements(prev.template).map { if (it.id == element.id) element else it }
        update(prev.copy(
            template = setSideElements(prev.template, newList),
            hasUnsavedChanges = true
        ))
        checkOutOfBounds()
    }

    fun moveElement(id: String, dx: Float, dy: Float) {
        mutateElement(id) { el ->
            when (el) {
                is TemplateElement.TextElement -> el.copy(x = el.x + dx, y = el.y + dy)
                is TemplateElement.UsernameElement -> el.copy(x = el.x + dx, y = el.y + dy)
                is TemplateElement.PasswordElement -> el.copy(x = el.x + dx, y = el.y + dy)
                is TemplateElement.ImageElement -> el.copy(x = el.x + dx, y = el.y + dy)
                is TemplateElement.QrElement -> el.copy(x = el.x + dx, y = el.y + dy)
                is TemplateElement.DateElement -> el.copy(x = el.x + dx, y = el.y + dy)
                is TemplateElement.FrameElement -> el.copy(x = el.x + dx, y = el.y + dy)
                is TemplateElement.BackgroundDecorationElement -> el.copy(x = el.x + dx, y = el.y + dy)
                is TemplateElement.CardBackground -> el
            }
        }
    }

    fun resizeElement(id: String, newWidth: Float, newHeight: Float) {
        val minSize = 20f
        val safeW = newWidth.coerceAtLeast(minSize)
        val safeH = newHeight.coerceAtLeast(minSize)
        mutateElement(id) { el ->
            val scaleW = if (el.width > 0f) safeW / el.width else 1f
            // For text elements: keep center fixed so text doesn't drift as font scales.
            // cx/cy = old center; new x/y = new center - new half-size.
            val oldCX = el.x + el.width / 2f
            val oldCY = el.y + el.height / 2f
            when (el) {
                is TemplateElement.TextElement ->
                    el.copy(x = oldCX - safeW / 2f, y = oldCY - safeH / 2f,
                        width = safeW, height = safeH,
                        textSizeSp = (el.textSizeSp * scaleW).coerceIn(6f, 72f))
                is TemplateElement.UsernameElement ->
                    el.copy(x = oldCX - safeW / 2f, y = oldCY - safeH / 2f,
                        width = safeW, height = safeH,
                        textSizeSp = (el.textSizeSp * scaleW).coerceIn(6f, 72f))
                is TemplateElement.PasswordElement ->
                    el.copy(x = oldCX - safeW / 2f, y = oldCY - safeH / 2f,
                        width = safeW, height = safeH,
                        textSizeSp = (el.textSizeSp * scaleW).coerceIn(6f, 72f))
                is TemplateElement.DateElement ->
                    el.copy(x = oldCX - safeW / 2f, y = oldCY - safeH / 2f,
                        width = safeW, height = safeH,
                        textSizeSp = (el.textSizeSp * scaleW).coerceIn(6f, 72f))
                is TemplateElement.ImageElement -> el.copy(width = safeW, height = safeH)
                is TemplateElement.QrElement -> el.copy(width = safeW, height = safeH)
                is TemplateElement.FrameElement -> el.copy(width = safeW, height = safeH)
                is TemplateElement.BackgroundDecorationElement -> el.copy(width = safeW, height = safeH)
                is TemplateElement.CardBackground -> el
            }
        }
        checkOutOfBounds()
    }

    fun rotateElement(id: String, angleDelta: Float) {
        mutateElement(id) { el ->
            when (el) {
                is TemplateElement.TextElement -> el.copy(rotation = (el.rotation + angleDelta) % 360f)
                is TemplateElement.UsernameElement -> el.copy(rotation = (el.rotation + angleDelta) % 360f)
                is TemplateElement.PasswordElement -> el.copy(rotation = (el.rotation + angleDelta) % 360f)
                is TemplateElement.ImageElement -> el.copy(rotation = (el.rotation + angleDelta) % 360f)
                is TemplateElement.QrElement -> el.copy(rotation = (el.rotation + angleDelta) % 360f)
                is TemplateElement.DateElement -> el.copy(rotation = (el.rotation + angleDelta) % 360f)
                is TemplateElement.FrameElement -> el.copy(rotation = (el.rotation + angleDelta) % 360f)
                is TemplateElement.BackgroundDecorationElement -> el
                is TemplateElement.CardBackground -> el
            }
        }
    }

    fun reorderElementsToOrder(ids: List<String>) {
        val prev = uiState.value
        val elementMap = currentSideElements(prev.template).associateBy { it.id }
        val reordered = ids.mapNotNull { elementMap[it] }
        val extra = currentSideElements(prev.template).filter { it.id !in ids.toSet() }
        update(prev.copy(
            template = setSideElements(prev.template, reordered + extra),
            hasUnsavedChanges = true
        ))
        enforceLayerOrder()
    }

    fun reorderElements(from: Int, to: Int) {
        val prev = uiState.value
        val items = currentSideElements(prev.template)
        val moving = items.getOrNull(from)
        val target = items.getOrNull(to)
        if (moving is TemplateElement.CardBackground || moving is TemplateElement.BackgroundDecorationElement
            || moving is TemplateElement.FrameElement) return
        if (target is TemplateElement.CardBackground || target is TemplateElement.BackgroundDecorationElement
            || target is TemplateElement.FrameElement) return
        val mutable = items.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        update(prev.copy(
            template = setSideElements(prev.template, mutable),
            hasUnsavedChanges = true
        ))
    }

    // ── Computed helpers ──────────────────────────────────────────────────────

    val currentTemplate: Template get() = uiState.value.template

    /** The active side's element list. */
    val currentElements: List<TemplateElement>
        get() = currentSideElements(currentTemplate)

    val selectedElement: TemplateElement?
        get() = uiState.value.selectedElementId?.let { id ->
            currentElements.firstOrNull { it.id == id }
        }

    val isBackSideEnabled: Boolean get() = currentTemplate.isBackSideEnabled
    val activeSide: CardSide get() = currentTemplate.activeSide

    // Side-aware uniqueness checks (scoped to active side only)
    fun hasUsernameElement()  = currentElements.any { it is TemplateElement.UsernameElement }
    fun hasPasswordElement()  = currentElements.any { it is TemplateElement.PasswordElement }
    fun hasQrElement()        = currentElements.any { it is TemplateElement.QrElement }
    fun hasDateElement()      = currentElements.any { it is TemplateElement.DateElement }
    fun hasFrameElement()     = currentElements.any { it is TemplateElement.FrameElement }
    fun hasDecorationElement()= currentElements.any { it is TemplateElement.BackgroundDecorationElement }

    fun getImageDirForCurrentTemplate(): java.io.File =
        repo.getOrCreateImageDir(currentTemplate.id)

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Returns the element list for the given template's active side. */
    private fun currentSideElements(template: Template): List<TemplateElement> =
        if (template.activeSide == CardSide.BACK && template.isBackSideEnabled)
            template.backElements ?: emptyList()
        else
            template.elements

    /** Returns a copy of [template] with the active side's list replaced by [newElements]. */
    private fun setSideElements(template: Template, newElements: List<TemplateElement>): Template =
        if (template.activeSide == CardSide.BACK && template.isBackSideEnabled)
            template.copy(backElements = newElements)
        else
            template.copy(elements = newElements)

    private fun addElement(el: TemplateElement) {
        val prev = uiState.value
        val newList = listOf(el) + currentSideElements(prev.template)
        update(prev.copy(
            template = setSideElements(prev.template, newList),
            selectedElementId = el.id,
            hasUnsavedChanges = true
        ))
        enforceLayerOrder()
        checkOutOfBounds()
    }

    private fun mutateElement(id: String, transform: (TemplateElement) -> TemplateElement) {
        val prev = uiState.value
        val newList = currentSideElements(prev.template).map { if (it.id == id) transform(it) else it }
        update(prev.copy(
            template = setSideElements(prev.template, newList),
            hasUnsavedChanges = true
        ))
    }

    private fun mutateTemplate(transform: (Template) -> Template) {
        val prev = uiState.value
        update(prev.copy(template = transform(prev.template), hasUnsavedChanges = true))
    }

    private fun update(state: EditorUiState) { _uiState.value = state }
    private fun newId() = UUID.randomUUID().toString()

    private fun enforceLayerOrder() {
        val prev = uiState.value
        val ordered = enforceOrder(currentSideElements(prev.template))
        update(prev.copy(template = setSideElements(prev.template, ordered)))
    }

    private fun enforceOrder(elements: List<TemplateElement>): List<TemplateElement> {
        val bg    = elements.filterIsInstance<TemplateElement.CardBackground>().firstOrNull()
        val deco  = elements.filterIsInstance<TemplateElement.BackgroundDecorationElement>().firstOrNull()
        val frame = elements.filterIsInstance<TemplateElement.FrameElement>().firstOrNull()
        val users = elements.filter {
            it !is TemplateElement.CardBackground &&
            it !is TemplateElement.BackgroundDecorationElement &&
            it !is TemplateElement.FrameElement
        }
        val ordered = mutableListOf<TemplateElement>()
        if (frame != null) ordered.add(frame)
        ordered.addAll(users)
        if (deco  != null) ordered.add(deco)
        if (bg    != null) ordered.add(bg)
        return ordered
    }

    private fun checkOutOfBounds() {
        val template = currentTemplate
        val cardH = template.card.widthDp * template.card.heightRatio
        val outOfBounds = currentElements
            .filter { it !is TemplateElement.CardBackground
                    && it !is TemplateElement.BackgroundDecorationElement
                    && it !is TemplateElement.FrameElement }
            .any { el ->
                if (el is TemplateElement.TextElement || el is TemplateElement.UsernameElement
                    || el is TemplateElement.PasswordElement || el is TemplateElement.DateElement) {
                    val cx = el.x + el.width / 2
                    val cy = el.y + el.height / 2
                    cx < 0 || cx > template.card.widthDp || cy < 0 || cy > cardH
                } else {
                    el.y + el.height > cardH || el.x + el.width > template.card.widthDp
                }
            }
        _uiState.value = uiState.value.copy(hasOutOfBoundsElements = outOfBounds)
    }
}

package dev.anonymous.cardsdesignerpro.ui.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.anonymous.cardsdesignerpro.data.model.CardSide
import dev.anonymous.cardsdesignerpro.data.model.CardStyle
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
    /** Set to true momentarily when the active side switches — triggers full list refresh. */
    val sideSwitched: Boolean = false,
    /** Whether the undo stack has any snapshots. */
    val canUndo: Boolean = false,
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

    /** Snapshot of the template as loaded from disk — used to restore on discard. */
    private var originalTemplate: Template? = null

    // ── Undo Stack ────────────────────────────────────────────────────────────

    private val undoStack = ArrayDeque<Template>()   // max UNDO_LIMIT snapshots
    /** System clock ms of the last checkpoint push — used for debounce. */
    private var lastCheckpointMs = 0L

    /**
     * Saves the current template as an undo checkpoint.
     * Debounced: rapid calls within [UNDO_DEBOUNCE_MS] are collapsed into one.
     * This lets drag / slider gestures produce a single undo step per gesture.
     */
    private fun pushCheckpoint() {
        val now = System.currentTimeMillis()
        if (now - lastCheckpointMs < UNDO_DEBOUNCE_MS) return   // still within same gesture
        lastCheckpointMs = now
        if (undoStack.size >= UNDO_LIMIT) undoStack.removeFirst()
        undoStack.addLast(currentTemplate)
        if (!uiState.value.canUndo) update(uiState.value.copy(canUndo = true))
    }

    /** Restores the most recent undo checkpoint. */
    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        val state = uiState.value
        // Keep selection only if the element still exists in the restored template
        val selId = state.selectedElementId?.takeIf { id ->
            currentSideElements(prev).any { it.id == id }
        }
        update(state.copy(
            template = prev,
            selectedElementId = selId,
            hasUnsavedChanges = true,
            canUndo = undoStack.isNotEmpty()
        ))
    }

    // ── Initialization ────────────────────────────────────────────────────────

    fun init(templateId: String) {
        if (isInitialized) return
        isInitialized = true
        viewModelScope.launch {
            val template = repo.getById(templateId) ?: return@launch
            originalTemplate = template
            // Always open on FRONT side — activeSide on disk is reset to FRONT before
            // each save, so this is just an extra safety guard.
            val startTemplate = template.copy(activeSide = CardSide.FRONT)
            _uiState.value = EditorUiState(
                template = startTemplate,
                selectedElementId = currentSideElements(startTemplate).firstOrNull()?.id
            )
        }
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    /** Explicit save (toolbar button / dialog). Clears the unsaved flag. */
    fun save(onComplete: () -> Unit = {}) {
        // Bump version only if it hasn't been bumped yet relative to the original template.
        // Always persist with activeSide=FRONT so the next session opens on the front face.
        val base = currentTemplate.copy(activeSide = CardSide.FRONT)
        val templateToSave = if (base.version == originalTemplate?.version) {
            base.copy(version = base.version + 1)
        } else {
            base
        }
        viewModelScope.launch {
            repo.save(templateToSave)
            originalTemplate = templateToSave
            _uiState.value = uiState.value.copy(template = templateToSave, hasUnsavedChanges = false)
            onComplete()
        }
    }

    /** Auto-save (onPause). Writes to disk but keeps hasUnsavedChanges so the
     *  discard dialog still appears when the user returns.
     *  Also resets activeSide to FRONT before writing so the next session starts
     *  on the front face. */
    fun saveIfNeeded() {
        if (uiState.value.hasUnsavedChanges) {
            val base = currentTemplate.copy(activeSide = CardSide.FRONT)
            val templateToSave = if (base.version == originalTemplate?.version) {
                base.copy(version = base.version + 1)
            } else {
                base
            }
            _uiState.value = uiState.value.copy(template = templateToSave)
            viewModelScope.launch { repo.save(templateToSave) }
        }
    }

    fun discardAndCleanup(onComplete: () -> Unit = {}) {
        _uiState.value = uiState.value.copy(hasUnsavedChanges = false)
        viewModelScope.launch {
            originalTemplate?.let { orig ->
                repo.save(orig)
                // Clean based on the ORIGINAL template so images it references are kept
                repo.cleanOrphanImages(orig)
            }
            onComplete()
        }
    }

    /** Resets the in-memory template to the original loaded state. */
    fun restoreOriginal() {
        val orig = originalTemplate ?: return
        _uiState.value = EditorUiState(
            template = orig,
            selectedElementId = currentSideElements(orig).firstOrNull()?.id,
            hasUnsavedChanges = false
        )
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
                backCard = if (nowEnabled && t.backCard == null) {
                    // Start with a clean white/neutral back — not a copy of the front
                    t.card.copy(
                        backgroundColor     = "#FFFFFFFF",
                        backgroundImagePath = null,
                    )
                } else {
                    t.backCard
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

    /** Switches the active editing face and fires a side-switch signal.
     *  This is a VIEW-ONLY toggle — it does NOT mark unsaved changes. */
    fun setActiveSide(side: CardSide) {
        if (uiState.value.template.activeSide == side) return
        val prev = uiState.value
        update(prev.copy(
            template = prev.template.copy(activeSide = side),
            selectedElementId = null,
            // hasUnsavedChanges intentionally NOT set — switching sides is not an edit
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

    fun toggleShortNumbers(enabled: Boolean) {
        mutateTemplate { t ->
            val newFront = if (!enabled) t.elements.filterNot { 
                (it is TemplateElement.UsernameElement && it.isShortVariant) || 
                (it is TemplateElement.PasswordElement && it.isShortVariant) 
            } else t.elements

            val newBack = if (!enabled && t.backElements != null) t.backElements.filterNot {
                (it is TemplateElement.UsernameElement && it.isShortVariant) || 
                (it is TemplateElement.PasswordElement && it.isShortVariant) 
            } else t.backElements

            t.copy(isShortNumbersEnabled = enabled, elements = newFront, backElements = newBack)
        }
        // Deselect if active element was deleted
        val currentActive = uiState.value.selectedElementId
        if (currentActive != null && currentElements.none { it.id == currentActive }) {
            selectElement(null)
        }
    }

    /** Returns the card style for the currently active side. */
    val activeCardStyle: CardStyle
        get() {
            val t = currentTemplate
            return if (t.activeSide == CardSide.BACK && t.isBackSideEnabled)
                t.backCard ?: t.card
            else t.card
        }

    /** Updates the card style for the currently active side. */
    private fun mutateActiveCardStyle(transform: (CardStyle) -> CardStyle) {
        mutateTemplate { t ->
            if (t.activeSide == CardSide.BACK && t.isBackSideEnabled) {
                t.copy(backCard = transform(t.backCard ?: t.card))
            } else {
                t.copy(card = transform(t.card))
            }
        }
    }

    fun updateCardBackgroundColor(color: String) =
        mutateActiveCardStyle { it.copy(backgroundColor = color) }

    fun updateCardBackgroundImage(path: String?) =
        mutateActiveCardStyle { it.copy(backgroundImagePath = path) }

    fun updateCardBackgroundScale(scaleType: String) =
        mutateActiveCardStyle { it.copy(backgroundImageScaleType = scaleType) }


    /**
     * Adjusts the card height ratio, clamping to [0.2, 2.0].
     * Also resizes Frame and BackgroundDecoration to match the new card dimensions.
     */
    fun updateCardHeightRatio(ratio: Float) {
        val clamped = ratio.coerceIn(0.2f, 1.2f)   // max = widthDp × 1.2 as per UX requirement
        mutateTemplate { template ->
            val newCard = template.card.copy(heightRatio = clamped)
            val newBackCard = template.backCard?.copy(heightRatio = clamped)
            fun resizeElements(elements: List<TemplateElement>) = elements.map { el ->
                when (el) {
                    is TemplateElement.FrameElement ->
                        el.copy(x = 0f, y = 0f, width = newCard.widthDp, height = newCard.widthDp * clamped)
                    else -> el
                }
            }
            template.copy(
                card = newCard,
                backCard = newBackCard,
                elements = resizeElements(template.elements),
                backElements = template.backElements?.let { resizeElements(it) }
            )
        }
    }

    // ── Element additions ─────────────────────────────────────────────────────

    private fun centerX(elW: Float) = (currentTemplate.card.widthDp - elW) / 2f
    private fun centerY(elH: Float) =
        (currentTemplate.card.widthDp * currentTemplate.card.heightRatio - elH) / 2f

    fun addTextElement() = addElement(
        TemplateElement.TextElement(
            id = newId(), x = centerX(160f), y = centerY(40f), width = 160f, height = 40f,
            text = getApplication<Application>().getString(dev.anonymous.cardsdesignerpro.R.string.default_text_placeholder)
        )
    )

    fun addUsernameElement(isShort: Boolean = false) = addElement(
        TemplateElement.UsernameElement(
            id = newId(), x = centerX(160f), y = centerY(40f), width = 160f, height = 40f,
            isShortVariant = isShort,
            digitCount = if (isShort) 5 else 12
        )
    )

    fun addPasswordElement(isShort: Boolean = false) = addElement(
        TemplateElement.PasswordElement(
            id = newId(), x = centerX(160f), y = centerY(40f), width = 160f, height = 40f,
            isShortVariant = isShort,
            digitCount = if (isShort) 5 else 6
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

    fun addShapeElement() {
        val w = 80f; val h = 60f
        addElement(
            TemplateElement.ShapeElement(
                id = newId(), x = centerX(w), y = centerY(h), width = w, height = h,
            )
        )
    }



    fun addImageElement(imagePath: String, srcW: Int = 0, srcH: Int = 0) {
        val card = currentTemplate.card
        val cardW = card.widthDp
        val cardH = card.widthDp * card.heightRatio
        
        val limitW = cardW * 0.5f
        val limitH = cardH * 0.5f
        var elW = srcW.toFloat()
        var elH = srcH.toFloat()
        
        // If no src dimensions provided, fallback to half size
        if (elW <= 0f || elH <= 0f) {
            elW = limitW
            elH = limitH
        }
        
        // As requested: if the image intrinsic size is bigger than the card template size itself, 
        // restrict it down to half the template size so it isn't overwhelmingly large.
        if (elW > cardW || elH > cardH) {
            val aspect = elW / elH
            if (limitW / aspect <= limitH) {
                elW = limitW
                elH = limitW / aspect
            } else {
                elH = limitH
                elW = limitH * aspect
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
    }

    fun toggleVisibility(id: String) {
        val wasVisible = currentElements.firstOrNull { it.id == id }?.isVisible ?: return
        mutateElement(id) { el ->
            when (el) {
                is TemplateElement.TextElement       -> el.copy(isVisible = !el.isVisible)
                is TemplateElement.UsernameElement   -> el.copy(isVisible = !el.isVisible)
                is TemplateElement.PasswordElement   -> el.copy(isVisible = !el.isVisible)
                is TemplateElement.ImageElement      -> el.copy(isVisible = !el.isVisible)
                is TemplateElement.QrElement         -> el.copy(isVisible = !el.isVisible)
                is TemplateElement.DateElement       -> el.copy(isVisible = !el.isVisible)
                is TemplateElement.FrameElement      -> el.copy(isVisible = !el.isVisible)
                is TemplateElement.ShapeElement      -> el.copy(isVisible = !el.isVisible)
                is TemplateElement.CardBackground    -> el
            }
        }
        if (wasVisible && uiState.value.selectedElementId == id) selectElement(null)
    }

    fun selectElement(id: String?) {
        update(uiState.value.copy(selectedElementId = id))
    }

    fun updateElement(element: TemplateElement) {
        val prev = uiState.value
        pushCheckpoint()
        val newList = currentSideElements(prev.template).map { if (it.id == element.id) element else it }
        update(prev.copy(
            template = setSideElements(prev.template, newList),
            hasUnsavedChanges = true,
            canUndo = undoStack.isNotEmpty()
        ))
    }

    fun moveElement(id: String, dx: Float, dy: Float) {
        mutateElement(id) { el ->
            when (el) {
                is TemplateElement.TextElement     -> el.copy(x = el.x + dx, y = el.y + dy)
                is TemplateElement.UsernameElement -> el.copy(x = el.x + dx, y = el.y + dy)
                is TemplateElement.PasswordElement -> el.copy(x = el.x + dx, y = el.y + dy)
                is TemplateElement.ImageElement    -> el.copy(x = el.x + dx, y = el.y + dy)
                is TemplateElement.QrElement       -> el.copy(x = el.x + dx, y = el.y + dy)
                is TemplateElement.DateElement     -> el.copy(x = el.x + dx, y = el.y + dy)
                is TemplateElement.FrameElement    -> el.copy(x = el.x + dx, y = el.y + dy)
                is TemplateElement.ShapeElement    -> el.copy(x = el.x + dx, y = el.y + dy)
                is TemplateElement.CardBackground  -> el
            }
        }
    }

    fun resizeElement(id: String, newWidth: Float, newHeight: Float) {
        val minSize = 0.1f
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
                        textSizeSp = (el.textSizeSp * scaleW).coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP))
                is TemplateElement.UsernameElement ->
                    el.copy(x = oldCX - safeW / 2f, y = oldCY - safeH / 2f,
                        width = safeW, height = safeH,
                        textSizeSp = (el.textSizeSp * scaleW).coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP))
                is TemplateElement.PasswordElement ->
                    el.copy(x = oldCX - safeW / 2f, y = oldCY - safeH / 2f,
                        width = safeW, height = safeH,
                        textSizeSp = (el.textSizeSp * scaleW).coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP))
                is TemplateElement.DateElement ->
                    el.copy(x = oldCX - safeW / 2f, y = oldCY - safeH / 2f,
                        width = safeW, height = safeH,
                        textSizeSp = (el.textSizeSp * scaleW).coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP))
                is TemplateElement.ImageElement    -> el.copy(width = safeW, height = safeH)
                is TemplateElement.QrElement       -> el.copy(width = safeW, height = safeH)
                is TemplateElement.FrameElement    -> el.copy(width = safeW, height = safeH)
                is TemplateElement.ShapeElement    -> el.copy(x = oldCX - safeW / 2f, y = oldCY - safeH / 2f, width = safeW, height = safeH)
                is TemplateElement.CardBackground  -> el
            }
        }
    }

    /** Right-center handle: changes width only, left edge stays fixed. */
    fun resizeShapeWidth(id: String, newWidth: Float) {
        val safeW = newWidth.coerceAtLeast(10f)
        mutateElement(id) { el ->
            when (el) {
                is TemplateElement.ShapeElement -> el.copy(width = safeW)
                else -> el
            }
        }
    }

    /** Top-center handle: top edge moves, bottom edge stays fixed. */
    fun resizeShapeHeight(id: String, newY: Float, newHeight: Float) {
        val safeH = newHeight.coerceAtLeast(10f)
        mutateElement(id) { el ->
            when (el) {
                is TemplateElement.ShapeElement -> el.copy(y = newY, height = safeH)
                else -> el
            }
        }
    }


    fun rotateElement(id: String, angleDelta: Float) {
        mutateElement(id) { el ->
            when (el) {
                is TemplateElement.TextElement     -> el.copy(rotation = (el.rotation + angleDelta) % 360f)
                is TemplateElement.UsernameElement -> el.copy(rotation = (el.rotation + angleDelta) % 360f)
                is TemplateElement.PasswordElement -> el.copy(rotation = (el.rotation + angleDelta) % 360f)
                is TemplateElement.ImageElement    -> el.copy(rotation = (el.rotation + angleDelta) % 360f)
                is TemplateElement.QrElement       -> el.copy(rotation = (el.rotation + angleDelta) % 360f)
                is TemplateElement.DateElement     -> el.copy(rotation = (el.rotation + angleDelta) % 360f)
                is TemplateElement.FrameElement    -> el.copy(rotation = (el.rotation + angleDelta) % 360f)
                is TemplateElement.ShapeElement    -> el.copy(rotation = (el.rotation + angleDelta) % 360f)
                is TemplateElement.CardBackground  -> el
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
        if (moving is TemplateElement.CardBackground
            || moving is TemplateElement.FrameElement) return
        if (target is TemplateElement.CardBackground
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
    fun hasNormalUsernameElement() = currentElements.any { it is TemplateElement.UsernameElement && !it.isShortVariant }
    fun hasShortUsernameElement()  = currentElements.any { it is TemplateElement.UsernameElement && it.isShortVariant }
    fun hasNormalPasswordElement() = currentElements.any { it is TemplateElement.PasswordElement && !it.isShortVariant }
    fun hasShortPasswordElement()  = currentElements.any { it is TemplateElement.PasswordElement && it.isShortVariant }
    fun hasQrElement()        = currentElements.any { it is TemplateElement.QrElement }
    fun hasDateElement()      = currentElements.any { it is TemplateElement.DateElement }
    fun hasFrameElement()     = currentElements.any { it is TemplateElement.FrameElement }


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
    }

    private fun mutateElement(id: String, transform: (TemplateElement) -> TemplateElement) {
        val prev = uiState.value
        pushCheckpoint()
        val newList = currentSideElements(prev.template).map { if (it.id == id) transform(it) else it }
        update(prev.copy(
            template = setSideElements(prev.template, newList),
            hasUnsavedChanges = true,
            canUndo = undoStack.isNotEmpty()
        ))
    }

    private fun mutateTemplate(transform: (Template) -> Template) {
        val prev = uiState.value
        pushCheckpoint()
        update(prev.copy(template = transform(prev.template), hasUnsavedChanges = true, canUndo = undoStack.isNotEmpty()))
    }

    private fun update(state: EditorUiState) { _uiState.value = state }
    private fun newId() = UUID.randomUUID().toString()

    private fun enforceLayerOrder() {
        val prev = uiState.value
        val ordered = enforceOrder(currentSideElements(prev.template))
        update(prev.copy(template = setSideElements(prev.template, ordered)))
    }

    /**
     * List order (top → bottom in UI list):
     *   1. Regular elements (text, image, qr, etc.)
     *   2. Frame (below regular elements in the list)
     *   3. CardBackground (always last)
     *
     * Draw order (TemplateRenderer uses asReversed()):
     *   CardBackground → Frame → regular elements
     * So Frame is drawn ON TOP of regular elements despite being below in the list.
     */
    private fun enforceOrder(elements: List<TemplateElement>): List<TemplateElement> {
        val bg    = elements.filterIsInstance<TemplateElement.CardBackground>().firstOrNull()
        val frame = elements.filterIsInstance<TemplateElement.FrameElement>().firstOrNull()
        val users = elements.filter {
            it !is TemplateElement.CardBackground &&
            it !is TemplateElement.FrameElement
        }
        val ordered = mutableListOf<TemplateElement>()
        ordered.addAll(users)
        if (frame != null) ordered.add(frame)
        if (bg    != null) ordered.add(bg)
        return ordered
    }

    companion object {
        const val MIN_TEXT_SIZE_SP = 10f
        const val MAX_TEXT_SIZE_SP = 100f
        private const val UNDO_LIMIT        = 20    // max snapshots kept
        private const val UNDO_DEBOUNCE_MS  = 1000L // changes within 1s = one checkpoint
    }
}

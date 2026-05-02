package dev.anonymous.cardsdesignerpro.ui.editor

import android.app.Application
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.anonymous.cardsdesignerpro.data.model.CardSide
import dev.anonymous.cardsdesignerpro.data.model.CardStyle
import dev.anonymous.cardsdesignerpro.data.model.Template
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.data.repository.TemplateRepository
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel.Companion.UNDO_DEBOUNCE_MS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
            template = Template(
                id = "",
                name = "",
                card = CardStyle()
            )
        )
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var isInitialized = false

    /** Snapshot of the template as loaded from disk — used to restore on discard. */
    private var originalTemplate: Template? = null

    private val typefaceCache = mutableMapOf<String, Typeface>()
    // Keeps resize-time line lock data so text width/height stay stable during drag.
    private var textCornerResizeSession: TextCornerResizeSession? = null

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
        update(
            state.copy(
                template = prev,
                selectedElementId = selId,
                hasUnsavedChanges = true,
                canUndo = undoStack.isNotEmpty()
            )
        )
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
            _uiState.value =
                uiState.value.copy(template = templateToSave, hasUnsavedChanges = false)
            onComplete()
        }
    }

    /** Auto-save (onPause). Writes to disk but keeps hasUnsavedChanges so the
     *  discard dialog still appears when the user returns.
     *  Persists with activeSide=FRONT so the next cold-start session opens on the
     *  front face, but does NOT change the live UI state — this prevents screen
     *  rotation from snapping back to the front side. */
    fun saveIfNeeded() {
        if (uiState.value.hasUnsavedChanges) {
            // Save to disk with activeSide=FRONT, but don't touch the live state
            val base = currentTemplate.copy(activeSide = CardSide.FRONT)
            val templateToSave = if (base.version == originalTemplate?.version) {
                base.copy(version = base.version + 1)
            } else {
                base
            }
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
                        backgroundColor = "#FFFFFFFF",
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
        update(
            prev.copy(
                template = prev.template.copy(activeSide = side),
                selectedElementId = null,
                // hasUnsavedChanges intentionally NOT set — switching sides is not an edit
                sideSwitched = true,
            )
        )
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
                        el.copy(
                            x = 0f,
                            y = 0f,
                            width = newCard.widthDp,
                            height = newCard.widthDp * clamped
                        )

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

    /**
     * Shows a dialog to collect text, then adds a TextElement sized to fit it.
     * Call [addTextElement] with the user's input (called from AddElementFragment).
     */
    fun addTextElement(text: String) {
        val safeText = text.trim()
            .ifEmpty { getApplication<Application>().getString(dev.anonymous.cardsdesignerpro.R.string.default_text_placeholder) }
        val card = currentTemplate.card
        val maxW = card.widthDp / 2f
        val defaultSizeSp = 15f

        val (naturalW, _) = measureTextSize(safeText, defaultSizeSp, "default", isBold = false, maxWidthDp = 8192f)
        val finalW = minOf(naturalW, maxW)
        val (_, finalH) = measureTextSize(safeText, defaultSizeSp, "default", isBold = false, maxWidthDp = finalW)

        addElement(
            TemplateElement.TextElement(
                id = newId(),
                x = centerX(finalW), y = centerY(finalH),
                width = finalW, height = finalH,
                text = safeText,
            )
        )
    }

    fun addUsernameElement(isShort: Boolean = false) {
        val digitCount = if (isShort) 5 else 12
        val linked = currentElements.firstOrNull {
            activeCardStyle.linkCredentialsStyle &&
                    it is TemplateElement.PasswordElement &&
                    it.isShortVariant == isShort
        } as? TemplateElement.PasswordElement
        if (linked != null) {
            addElement(
                TemplateElement.UsernameElement(
                    id = newId(),
                    x = centerX(linked.width),
                    y = centerY(linked.height),
                    width = linked.width,
                    height = linked.height,
                    rotation = linked.rotation,
                    digitCount = digitCount,
                    textColor = linked.textColor,
                    bgColor = linked.bgColor,
                    isBold = linked.isBold,
                    fontName = linked.fontName,
                    textSizeSp = linked.textSizeSp,
                    isShortVariant = isShort,
                    textStrokeWidth = linked.textStrokeWidth,
                    textStrokeColor = linked.textStrokeColor
                )
            )
            return
        }
        val dummy = dummyDigits(digitCount)
        val (w, h) = measureTextSize(dummy, 16f, "default", isBold = false)
        addElement(
            TemplateElement.UsernameElement(
                id = newId(), x = centerX(w), y = centerY(h), width = w, height = h,
                isShortVariant = isShort,
                digitCount = digitCount
            )
        )
    }

    fun addPasswordElement(isShort: Boolean = false) {
        val digitCount = if (isShort) 5 else 6
        val linked = currentElements.firstOrNull {
            activeCardStyle.linkCredentialsStyle &&
                    it is TemplateElement.UsernameElement &&
                    it.isShortVariant == isShort
        } as? TemplateElement.UsernameElement
        if (linked != null) {
            addElement(
                TemplateElement.PasswordElement(
                    id = newId(),
                    x = centerX(linked.width),
                    y = centerY(linked.height),
                    width = linked.width,
                    height = linked.height,
                    rotation = linked.rotation,
                    digitCount = digitCount,
                    textColor = linked.textColor,
                    bgColor = linked.bgColor,
                    isBold = linked.isBold,
                    fontName = linked.fontName,
                    textSizeSp = linked.textSizeSp,
                    isShortVariant = isShort,
                    textStrokeWidth = linked.textStrokeWidth,
                    textStrokeColor = linked.textStrokeColor
                )
            )
            return
        }
        val dummy = dummyDigits(digitCount)
        val (w, h) = measureTextSize(dummy, 16f, "default", isBold = false)
        addElement(
            TemplateElement.PasswordElement(
                id = newId(), x = centerX(w), y = centerY(h), width = w, height = h,
                isShortVariant = isShort,
                digitCount = digitCount
            )
        )
    }

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
        // Matches TemplateRenderer: use full date string for initial measurement
        val dummy = "2024-12-31"
        val (w, h) = measureTextSize(dummy, 16f, "default", isBold = false)
        addElement(
            TemplateElement.DateElement(
                id = newId(), x = centerX(w), y = centerY(h), width = w, height = h
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
        val w = 80f;
        val h = 60f
        addElement(
            TemplateElement.ShapeElement(
                id = newId(), x = centerX(w), y = centerY(h), width = w, height = h,
            )
        )
    }

    fun addLineElement() {
        val w = 80f
        val h = 2f   // thin line
        addElement(
            TemplateElement.LineElement(
                id = newId(), x = centerX(w), y = centerY(h), width = w, height = h,
            )
        )
    }

    /** Duplicates an element by id, places the copy slightly offset and selects it. */
    fun duplicateElement(id: String) {
        val original = currentElements.firstOrNull { it.id == id } ?: return
        val offset = 8f   // template-dp nudge so the copy is visibly offset from the original
        
        val newId = newId()
        val copy = when (original) {
            is TemplateElement.ShapeElement -> original.copy(id = newId, x = original.x + offset, y = original.y + offset)
            is TemplateElement.TextElement  -> original.copy(id = newId, x = original.x + offset, y = original.y + offset)
            is TemplateElement.ImageElement -> original.copy(id = newId, x = original.x + offset, y = original.y + offset)
            is TemplateElement.LineElement  -> original.copy(id = newId, x = original.x + offset, y = original.y + offset)
            else -> return // Only these elements are allowed to be duplicated
        }
        addElement(copy)
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
        update(
            prev.copy(
                template = setSideElements(prev.template, newElements),
                selectedElementId = newSelected,
                hasUnsavedChanges = true
            )
        )
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
                is TemplateElement.ShapeElement -> el.copy(isVisible = !el.isVisible)
                is TemplateElement.LineElement -> el.copy(isVisible = !el.isVisible)
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
        pushCheckpoint()
        
        val newList = currentSideElements(prev.template).map { 
            if (it.id == element.id) element else it
        }.toMutableList()

        if (activeCardStyle.linkCredentialsStyle) {
            syncLinkedCredentials(newList, element)
        }

        update(
            prev.copy(
                template = setSideElements(prev.template, newList),
                hasUnsavedChanges = true,
                canUndo = undoStack.isNotEmpty()
            )
        )
    }

    fun setLinkCredentialsStyle(link: Boolean) {
        if (activeCardStyle.linkCredentialsStyle == link) return
        
        mutateActiveCardStyle { it.copy(linkCredentialsStyle = link) }
        
        if (link) {
            val selected = currentElements.find { it.id == uiState.value.selectedElementId }
            if (selected is TemplateElement.UsernameElement || selected is TemplateElement.PasswordElement) {
                // Synchronize immediately using the selected element as the source of truth
                updateElement(selected)
            }
        }
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
                is TemplateElement.ShapeElement -> el.copy(x = el.x + dx, y = el.y + dy)
                is TemplateElement.LineElement -> el.copy(x = el.x + dx, y = el.y + dy)
                is TemplateElement.CardBackground -> el
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
                is TemplateElement.TextElement -> {
                    val pad2 = TEXT_ELEMENT_PAD_DP * 2f
                    val oldInnerW = (el.width - pad2).coerceAtLeast(1f)
                    val newInnerW = (safeW - pad2).coerceAtLeast(1f)
                    val scaleInnerW = newInnerW / oldInnerW
                    val newTextSize = (el.textSizeSp * scaleInnerW).coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
                    val (_, computedH) = measureTextSize(el.text, newTextSize, el.fontName, el.isBold, safeW)
                    el.copy(
                        x = oldCX - safeW / 2f, y = oldCY - computedH / 2f,
                        width = safeW, height = computedH,
                        textSizeSp = newTextSize
                    )
                }

                is TemplateElement.UsernameElement -> {
                    val pad2 = TEXT_ELEMENT_PAD_DP * 2f
                    val oldInnerW = (el.width - pad2).coerceAtLeast(1f)
                    val newInnerW = (safeW - pad2).coerceAtLeast(1f)
                    val scaleInnerW = newInnerW / oldInnerW
                    val newTextSize = (el.textSizeSp * scaleInnerW).coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
                    val pseudoText = dummyDigits(el.digitCount.coerceAtLeast(1))
                    val (_, computedH) = measureTextSize(pseudoText, newTextSize, el.fontName, el.isBold, safeW)
                    el.copy(
                        x = oldCX - safeW / 2f, y = oldCY - computedH / 2f,
                        width = safeW, height = computedH,
                        textSizeSp = newTextSize
                    )
                }

                is TemplateElement.PasswordElement -> {
                    val pad2 = TEXT_ELEMENT_PAD_DP * 2f
                    val oldInnerW = (el.width - pad2).coerceAtLeast(1f)
                    val newInnerW = (safeW - pad2).coerceAtLeast(1f)
                    val scaleInnerW = newInnerW / oldInnerW
                    val newTextSize = (el.textSizeSp * scaleInnerW).coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
                    val pseudoText = dummyDigits(el.digitCount.coerceAtLeast(1))
                    val (_, computedH) = measureTextSize(pseudoText, newTextSize, el.fontName, el.isBold, safeW)
                    el.copy(
                        x = oldCX - safeW / 2f, y = oldCY - computedH / 2f,
                        width = safeW, height = computedH,
                        textSizeSp = newTextSize
                    )
                }

                is TemplateElement.DateElement -> {
                    val pad2 = TEXT_ELEMENT_PAD_DP * 2f
                    val oldInnerW = (el.width - pad2).coerceAtLeast(1f)
                    val newInnerW = (safeW - pad2).coerceAtLeast(1f)
                    val scaleInnerW = newInnerW / oldInnerW
                    val newTextSize = (el.textSizeSp * scaleInnerW).coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
                    val (_, computedH) = measureTextSize("12/12/2026", newTextSize, el.fontName, el.isBold, safeW)
                    el.copy(
                        x = oldCX - safeW / 2f, y = oldCY - computedH / 2f,
                        width = safeW, height = computedH,
                        textSizeSp = newTextSize
                    )
                }

                is TemplateElement.ImageElement -> el.copy(
                    x = oldCX - safeW / 2f,
                    y = oldCY - safeH / 2f,
                    width = safeW, height = safeH
                )
                is TemplateElement.QrElement -> el.copy(
                    x = oldCX - safeW / 2f,
                    y = oldCY - safeH / 2f,
                    width = safeW, height = safeH
                )
                is TemplateElement.FrameElement -> el.copy(width = safeW, height = safeH)
                is TemplateElement.ShapeElement -> el.copy(
                    x = oldCX - safeW / 2f,
                    y = oldCY - safeH / 2f,
                    width = safeW,
                    height = safeH
                )
                is TemplateElement.LineElement -> el.copy(
                    x = oldCX - safeW / 2f,
                    y = oldCY - safeH / 2f,
                    width = safeW,
                    height = safeH
                )

                is TemplateElement.CardBackground -> el
            }
        }
    }

    /** Right-center handle: changes width only, left edge stays fixed. */
    fun resizeShapeWidth(id: String, newWidth: Float) {
        val safeW = newWidth.coerceAtLeast(10f)
        mutateElement(id) { el ->
            when (el) {
                is TemplateElement.ShapeElement -> {
                    val dW = safeW - el.width
                    if (dW == 0f) return@mutateElement el
                    // Compensate position to keep the left edge anchored during rotation
                    val rad = Math.toRadians(el.rotation.toDouble())
                    val cos = kotlin.math.cos(rad).toFloat()
                    val sin = kotlin.math.sin(rad).toFloat()
                    val newX = el.x + (dW / 2f) * (cos - 1f)
                    val newY = el.y + (dW / 2f) * sin
                    el.copy(width = safeW, x = newX, y = newY)
                }
                else -> el
            }
        }
    }

    fun resizeLineWidth(id: String, newWidth: Float) {
        val safeW = newWidth.coerceAtLeast(5f)
        mutateElement(id) { el ->
            if (el !is TemplateElement.LineElement) return@mutateElement el
            val dW = safeW - el.width
            if (dW == 0f) return@mutateElement el
            // Compensate position to keep the left edge (starting point) anchored during rotation
            val rad = Math.toRadians(el.rotation.toDouble())
            val cos = kotlin.math.cos(rad).toFloat()
            val sin = kotlin.math.sin(rad).toFloat()
            val newX = el.x + (dW / 2f) * (cos - 1f)
            val newY = el.y + (dW / 2f) * sin
            el.copy(width = safeW, x = newX, y = newY)
        }
    }

    /**
     * Right-center pill handle for TextElement: changes width and recalculates
     * the element height so the box always wraps the text content exactly.
     */
    /**
     * Right-center pill handle for TextElement: changes width and recalculates
     * the element height so the box always wraps the text content exactly.
     *
     * For rotated elements the position is compensated so the visual top-left
     * corner stays fixed (at rotation=0 this is a no-op: x,y unchanged).
     */
    fun resizeTextWidth(id: String, newWidth: Float, pxPerDp: Float? = null) {
        val safeW = newWidth.coerceAtLeast(20f)
        val el =
            currentElements.firstOrNull { it.id == id } as? TemplateElement.TextElement ?: return
        val after = measureTextMetrics(
            text = el.text,
            sizeSp = el.textSizeSp,
            fontName = el.fontName,
            isBold = el.isBold,
            maxWidthDp = safeW,
            pxPerDp = pxPerDp
        )
        // Keep the visual top-left corner fixed when the element is rotated.
        val dW = safeW - el.width
        val dH = after.heightDp - el.height
        val rad = Math.toRadians(el.rotation.toDouble())
        val cosR = kotlin.math.cos(rad).toFloat()
        val sinR = kotlin.math.sin(rad).toFloat()
        val newX = el.x + (dW / 2f) * (cosR - 1f) - (dH / 2f) * sinR
        val newY = el.y + (dH / 2f) * (cosR - 1f) + (dW / 2f) * sinR
        val newEl = el.copy(x = newX, y = newY, width = safeW, height = after.heightDp)
        val prev = uiState.value
        val newList = currentSideElements(prev.template).map { if (it.id == id) newEl else it }
        update(
            prev.copy(
                template = setSideElements(prev.template, newList),
                hasUnsavedChanges = true
            )
        )
    }

    /** Recalculates and applies element height to fit text at current width (called after text/font changes). */
    fun resizeTextElementToFit(el: TemplateElement.TextElement) {
        val (_, newH) = measureTextSize(el.text, el.textSizeSp, el.fontName, el.isBold, el.width)
        if (kotlin.math.abs(newH - el.height) > 1f) updateElement(el.copy(height = newH))
    }

    // Lock wrapped line count at drag start to prevent 2↔3 line oscillation while resizing.
    fun beginTextCornerResize(id: String, pxPerDp: Float? = null) {
        val el = currentElements.firstOrNull { it.id == id } as? TemplateElement.TextElement ?: return
        val metrics = measureTextMetrics(
            text = el.text,
            sizeSp = el.textSizeSp,
            fontName = el.fontName,
            isBold = el.isBold,
            maxWidthDp = el.width,
            pxPerDp = pxPerDp
        )
        textCornerResizeSession = TextCornerResizeSession(
            elementId = id,
            lockedLineCount = metrics.lineCount.coerceAtLeast(1),
            pxPerDp = pxPerDp
        )
    }

    // Release line lock at drag end and do one final natural height normalization.
    fun endTextCornerResize(id: String, pxPerDp: Float? = null) {
        val session = textCornerResizeSession
        textCornerResizeSession = null
        if (session?.elementId != id) return

        val effectivePxPerDp = pxPerDp ?: session.pxPerDp
        mutateElement(id) { el ->
            if (el is TemplateElement.TextElement) {
                val finalMetrics = measureTextMetrics(
                    text = el.text,
                    sizeSp = el.textSizeSp,
                    fontName = el.fontName,
                    isBold = el.isBold,
                    maxWidthDp = el.width,
                    pxPerDp = effectivePxPerDp
                )
                val oldCY = el.y + el.height / 2f
                el.copy(
                    y = oldCY - finalMetrics.heightDp / 2f,
                    height = finalMetrics.heightDp
                )
            } else el
        }
    }

    /**
     * Bottom-right corner-resize for text-based elements.
     * During active drag we keep wrapping stable and update geometry around the same center.
     */
    fun scaleTextFontSize(id: String, newSizeSp: Float, newWidth: Float, pxPerDp: Float? = null) {
        val safeSp = newSizeSp.coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
        val safeW = newWidth.coerceAtLeast(10f)

        mutateElement(id) { el ->
            val oldCX = el.x + el.width / 2f
            val oldCY = el.y + el.height / 2f
            when (el) {
                is TemplateElement.TextElement -> {
                    val resizeSession = textCornerResizeSession?.takeIf { it.elementId == id }
                    val lockedLines = resizeSession?.lockedLineCount
                    // Lock width to the same wrapping bucket while dragging (prevents 2<->3 flips).
                    val enforcedW = if (lockedLines != null) {
                        adjustWidthForLockedLineCount(
                            text = el.text,
                            sizeSp = safeSp,
                            fontName = el.fontName,
                            isBold = el.isBold,
                            targetWidthDp = safeW,
                            desiredLineCount = lockedLines,
                            pxPerDp = pxPerDp
                        )
                    } else {
                        safeW
                    }
                    val before = measureTextMetrics(
                        text = el.text,
                        sizeSp = el.textSizeSp,
                        fontName = el.fontName,
                        isBold = el.isBold,
                        maxWidthDp = el.width,
                        pxPerDp = pxPerDp
                    )
                    val after = measureTextMetrics(
                        text = el.text,
                        sizeSp = safeSp,
                        fontName = el.fontName,
                        isBold = el.isBold,
                        maxWidthDp = enforcedW,
                        pxPerDp = pxPerDp
                    )
                    // Hysteresis is used only outside lock mode (safety fallback path).
                    val stableAfter = if (lockedLines != null) {
                        after
                    } else {
                        stabilizeTextResizeMetrics(
                            text = el.text,
                            sizeSp = safeSp,
                            fontName = el.fontName,
                            isBold = el.isBold,
                            targetWidthDp = enforcedW,
                            beforeLineCount = before.lineCount,
                            candidate = after,
                            pxPerDp = pxPerDp
                        )
                    }
                    // Height uses a continuous metric with a fixed line count while dragging.
                    val lineCountForHeight = (lockedLines ?: stableAfter.lineCount).coerceAtLeast(1)
                    val smoothHeight = measureContinuousTextHeightDp(
                        sizeSp = safeSp,
                        fontName = el.fontName,
                        isBold = el.isBold,
                        lineCount = lineCountForHeight,
                        pxPerDp = pxPerDp
                    )
                    el.copy(
                        x = oldCX - enforcedW / 2f, y = oldCY - smoothHeight / 2f,
                        width = enforcedW, height = smoothHeight, textSizeSp = safeSp
                    )
                }

                is TemplateElement.UsernameElement -> {
                    val pseudoText = dummyDigits(el.digitCount.coerceAtLeast(1))
                    val (_, computedH) = measureTextSize(
                        text = pseudoText,
                        sizeSp = safeSp,
                        fontName = el.fontName,
                        isBold = el.isBold,
                        maxWidthDp = safeW,
                        pxPerDp = pxPerDp
                    )
                    el.copy(
                        x = oldCX - safeW / 2f, y = oldCY - computedH / 2f,
                        width = safeW, height = computedH, textSizeSp = safeSp
                    )
                }

                is TemplateElement.PasswordElement -> {
                    val pseudoText = dummyDigits(el.digitCount.coerceAtLeast(1))
                    val (_, computedH) = measureTextSize(
                        text = pseudoText,
                        sizeSp = safeSp,
                        fontName = el.fontName,
                        isBold = el.isBold,
                        maxWidthDp = safeW,
                        pxPerDp = pxPerDp
                    )
                    el.copy(
                        x = oldCX - safeW / 2f, y = oldCY - computedH / 2f,
                        width = safeW, height = computedH, textSizeSp = safeSp
                    )
                }

                is TemplateElement.DateElement -> {
                    val (_, computedH) = measureTextSize(
                        text = "12/12/2026",
                        sizeSp = safeSp,
                        fontName = el.fontName,
                        isBold = el.isBold,
                        maxWidthDp = safeW,
                        pxPerDp = pxPerDp
                    )
                    el.copy(
                        x = oldCX - safeW / 2f, y = oldCY - computedH / 2f,
                        width = safeW, height = computedH, textSizeSp = safeSp
                    )
                }

                else -> el
            }
        }
    }

    /**
     * Adds a small hysteresis band around text wrap thresholds during corner-resize.
     * This prevents rapid 2↔3 line flapping (visual jitter) when width/size move by tiny steps.
     */
    private fun stabilizeTextResizeMetrics(
        text: String,
        sizeSp: Float,
        fontName: String,
        isBold: Boolean,
        targetWidthDp: Float,
        beforeLineCount: Int,
        candidate: TextMetrics,
        pxPerDp: Float?
    ): TextMetrics {
        if (candidate.lineCount == beforeLineCount) return candidate

        val probeWidthDp = if (candidate.lineCount < beforeLineCount) {
            // Expanding text box (e.g. 3->2): require the lower line-count to survive
            // at a slightly narrower width before committing.
            (targetWidthDp - TEXT_RESIZE_LINE_HYSTERESIS_DP).coerceAtLeast(10f)
        } else {
            // Shrinking text box (e.g. 2->3): require the higher line-count to survive
            // at a slightly wider width before committing.
            targetWidthDp + TEXT_RESIZE_LINE_HYSTERESIS_DP
        }

        val probe = measureTextMetrics(
            text = text,
            sizeSp = sizeSp,
            fontName = fontName,
            isBold = isBold,
            maxWidthDp = probeWidthDp,
            pxPerDp = pxPerDp
        )

        return if (candidate.lineCount < beforeLineCount) {
            if (probe.lineCount <= candidate.lineCount) candidate else probe
        } else {
            if (probe.lineCount >= candidate.lineCount) candidate else probe
        }
    }

    /**
     * Continuous (non-step) text box height for interactive resize.
     * We still use StaticLayout to decide line count, but height comes from font metrics
     * to avoid integer-jump jitter while dragging.
     */
    private fun measureContinuousTextHeightDp(
        sizeSp: Float,
        fontName: String,
        isBold: Boolean,
        lineCount: Int,
        pxPerDp: Float?
    ): Float {
        val density = getApplication<Application>().resources.displayMetrics.density
        val effectivePxPerDp = pxPerDp?.takeIf { it > 0f } ?: density
        val tp = TextPaint().apply {
            isAntiAlias = true
            textSize = sizeSp * effectivePxPerDp
            typeface = resolveTypeface(fontName, isBold)
            isLinearText = true
            isSubpixelText = true
        }
        val safeLines = lineCount.coerceAtLeast(1)
        val textHeightDp = (tp.fontSpacing * safeLines) / effectivePxPerDp
        return textHeightDp + TEXT_ELEMENT_PAD_DP * 2f
    }

    /**
     * Keeps TextElement width on the same wrapped-line bucket during active corner-resize.
     * This prevents visible 2↔3 line flapping while dragging.
     */
    private fun adjustWidthForLockedLineCount(
        text: String,
        sizeSp: Float,
        fontName: String,
        isBold: Boolean,
        targetWidthDp: Float,
        desiredLineCount: Int,
        pxPerDp: Float?
    ): Float {
        val minW = 20f
        val maxW = activeCardStyle.widthDp.coerceAtLeast(minW)
        val target = targetWidthDp.coerceIn(minW, maxW)
        val desired = desiredLineCount.coerceAtLeast(1)

        fun linesAt(w: Float): Int = measureTextMetrics(
            text = text,
            sizeSp = sizeSp,
            fontName = fontName,
            isBold = isBold,
            maxWidthDp = w,
            pxPerDp = pxPerDp
        ).lineCount

        val targetLines = linesAt(target)
        if (targetLines == desired) return target

        return if (targetLines < desired) {
            // Need narrower width to increase line count
            var lo = minW
            var hi = target
            if (linesAt(lo) < desired) return target // impossible to reach desired

            repeat(TEXT_RESIZE_WIDTH_SEARCH_STEPS) {
                val mid = (lo + hi) / 2f
                val lines = linesAt(mid)
                if (lines >= desired) lo = mid else hi = mid
            }

            val loLines = linesAt(lo)
            val hiLines = linesAt(hi)
            when {
                loLines == desired -> lo
                hiLines == desired -> hi
                else -> lo // prefer keeping width slightly narrower to avoid dropping lines
            }
        } else {
            // Need wider width to reduce line count
            var lo = target
            var hi = maxW
            if (linesAt(hi) > desired) return target // impossible to reach desired

            repeat(TEXT_RESIZE_WIDTH_SEARCH_STEPS) {
                val mid = (lo + hi) / 2f
                val lines = linesAt(mid)
                if (lines > desired) lo = mid else hi = mid
            }

            val loLines = linesAt(lo)
            val hiLines = linesAt(hi)
            when {
                hiLines == desired -> hi
                loLines == desired -> lo
                else -> hi // prefer slightly wider to avoid overflow line jumps
            }
        }
    }

    private data class TextCornerResizeSession(
        val elementId: String,
        val lockedLineCount: Int,
        val pxPerDp: Float?
    )

    private data class TextMetrics(val widthDp: Float, val heightDp: Float, val lineCount: Int)

    private fun measureTextMetrics(
        text: String,
        sizeSp: Float,
        fontName: String,
        isBold: Boolean,
        maxWidthDp: Float = 8192f,
        pxPerDp: Float? = null
    ): TextMetrics {
        val density = getApplication<Application>().resources.displayMetrics.density
        val effectivePxPerDp = pxPerDp?.takeIf { it > 0f } ?: density
        val PAD = TEXT_ELEMENT_PAD_DP
        val tp = TextPaint().apply {
            isAntiAlias = true
            textSize = sizeSp * effectivePxPerDp
            typeface = resolveTypeface(fontName, isBold)
            isLinearText = true
            isSubpixelText = true
        }
        val slopPx = tp.measureText(" ") * 0.5f
        val innerWPx =
            kotlin.math.ceil((maxWidthDp - PAD * 2) * effectivePxPerDp + slopPx)
                .toInt()
                .coerceAtLeast(1)
        val layout = StaticLayout.Builder
            .obtain(text.ifBlank { " " }, 0, text.ifBlank { " " }.length, tp, innerWPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()
        val textW =
            (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: tp.measureText(
                text
            )
        val textH = layout.height.toFloat()
        // Provide 1dp of anti-truncation slop to ensure scaled bounding boxes remain
        // mathematically wide enough for the sub-pixel font layout phase at all scales
        return TextMetrics(
            widthDp = textW / effectivePxPerDp + PAD * 2 + 1f,
            heightDp = textH / effectivePxPerDp + PAD * 2,
            lineCount = layout.lineCount
        )
    }

    /** Measures the rendered width and height (in template-dp) of [text] in the given style. */
    private fun measureTextSize(
        text: String,
        sizeSp: Float,
        fontName: String,
        isBold: Boolean,
        maxWidthDp: Float = 8192f,
        pxPerDp: Float? = null
    ): Pair<Float, Float> {
        val metrics = measureTextMetrics(text, sizeSp, fontName, isBold, maxWidthDp, pxPerDp)
        return metrics.widthDp to metrics.heightDp
    }

    private fun resolveTypeface(fontName: String, isBold: Boolean): Typeface {
        val key = "$fontName|$isBold"
        return typefaceCache.getOrPut(key) {
            val style = if (isBold) Typeface.BOLD else Typeface.NORMAL
            val baseTypeface = when {
                fontName.startsWith("custom:") -> {
                    val fileName = fontName.removePrefix("custom:")
                    val file = java.io.File(getFontDirForCurrentTemplate(), fileName)
                    if (file.exists()) {
                        try { Typeface.createFromFile(file) } catch (_: Exception) { Typeface.DEFAULT }
                    } else Typeface.DEFAULT
                }
                fontName.lowercase().let { it == "default" || it.isEmpty() } -> Typeface.DEFAULT
                fontName.lowercase() == "serif" -> Typeface.SERIF
                fontName.lowercase() == "monospace" -> Typeface.MONOSPACE
                fontName.lowercase() == "sans-serif" -> Typeface.SANS_SERIF
                else -> runCatching {
                    val ctx = getApplication<Application>()
                    val resId = ctx.resources.getIdentifier(fontName, "font", ctx.packageName)
                    if (resId != 0) androidx.core.content.res.ResourcesCompat.getFont(ctx, resId)
                        ?: Typeface.DEFAULT
                    else Typeface.DEFAULT
                }.getOrDefault(Typeface.DEFAULT)
            }
            Typeface.create(baseTypeface, style)
        }
    }

    /** Must match TemplateRenderer.dummyDigits() exactly. */
    private fun dummyDigits(count: Int): String =
        (1..count).joinToString("") { (it % 10).toString() }

    /** Top-center handle: top edge moves, bottom edge stays fixed. */
    fun resizeShapeHeight(id: String, newHeight: Float) {
        val safeH = newHeight.coerceAtLeast(10f)
        mutateElement(id) { el ->
            when (el) {
                is TemplateElement.ShapeElement -> {
                    val dH = safeH - el.height
                    if (dH == 0f) return@mutateElement el
                    // Compensate position to keep the bottom edge anchored during rotation
                    val rad = Math.toRadians(el.rotation.toDouble())
                    val cos = kotlin.math.cos(rad).toFloat()
                    val sin = kotlin.math.sin(rad).toFloat()
                    val newX = el.x + (dH / 2f) * sin
                    val newY = el.y - (dH / 2f) * (1f + cos)
                    el.copy(height = safeH, x = newX, y = newY)
                }
                else -> el
            }
        }
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
                is TemplateElement.ShapeElement -> el.copy(rotation = (el.rotation + angleDelta) % 360f)
                is TemplateElement.LineElement -> el.copy(rotation = (el.rotation + angleDelta) % 360f)
                is TemplateElement.CardBackground -> el
            }
        }
    }

    fun reorderElementsToOrder(ids: List<String>) {
        val prev = uiState.value
        val elementMap = currentSideElements(prev.template).associateBy { it.id }
        val reordered = ids.mapNotNull { elementMap[it] }
        val extra = currentSideElements(prev.template).filter { it.id !in ids.toSet() }
        update(
            prev.copy(
                template = setSideElements(prev.template, reordered + extra),
                hasUnsavedChanges = true
            )
        )
        enforceLayerOrder()
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
    fun hasNormalUsernameElement() =
        currentElements.any { it is TemplateElement.UsernameElement && !it.isShortVariant }

    fun hasShortUsernameElement() =
        currentElements.any { it is TemplateElement.UsernameElement && it.isShortVariant }

    fun hasNormalPasswordElement() =
        currentElements.any { it is TemplateElement.PasswordElement && !it.isShortVariant }

    fun hasShortPasswordElement() =
        currentElements.any { it is TemplateElement.PasswordElement && it.isShortVariant }

    fun hasQrElement() = currentElements.any { it is TemplateElement.QrElement }
    fun hasDateElement() = currentElements.any { it is TemplateElement.DateElement }
    fun hasFrameElement() = currentElements.any { it is TemplateElement.FrameElement }


    fun getImageDirForCurrentTemplate(): java.io.File =
        repo.getImageDir(currentTemplate.id)

    fun getFontDirForCurrentTemplate(): java.io.File =
        repo.getFontDir(currentTemplate.id)

    fun ensureFontDirForCurrentTemplate(): java.io.File =
        repo.getOrCreateFontDir(currentTemplate.id)

    /** Returns (displayName, "custom:filename") pairs for all custom fonts in this template. */
    fun getCustomFontsForCurrentTemplate(): List<Pair<String, String>> =
        repo.getCustomFonts(currentTemplate.id).map { file ->
            val display = file.nameWithoutExtension
                .replace("_", " ")
                .replaceFirstChar { it.uppercase() }
            display to "custom:${file.name}"
        }

    /** Deletes a custom font file and resets any elements using it to "default". */
    fun deleteCustomFont(fontFileName: String) {
        val file = java.io.File(getFontDirForCurrentTemplate(), fontFileName)
        if (file.exists()) file.delete()
        val customKey = "custom:$fontFileName"
        // Reset elements on both sides that reference this font
        mutateTemplate { t ->
            fun resetElements(elements: List<TemplateElement>) = elements.map { el ->
                when (el) {
                    is TemplateElement.TextElement ->
                        if (el.fontName == customKey) el.copy(fontName = "default") else el
                    is TemplateElement.UsernameElement ->
                        if (el.fontName == customKey) el.copy(fontName = "default") else el
                    is TemplateElement.PasswordElement ->
                        if (el.fontName == customKey) el.copy(fontName = "default") else el
                    is TemplateElement.DateElement ->
                        if (el.fontName == customKey) el.copy(fontName = "default") else el
                    else -> el
                }
            }
            t.copy(
                elements = resetElements(t.elements),
                backElements = t.backElements?.let { resetElements(it) }
            )
        }
    }

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
        update(
            prev.copy(
                template = setSideElements(prev.template, newList),
                selectedElementId = el.id,
                hasUnsavedChanges = true
            )
        )
        enforceLayerOrder()
    }

    private fun mutateElement(id: String, transform: (TemplateElement) -> TemplateElement) {
        val prev = uiState.value
        pushCheckpoint()
        
        var mutatedElement: TemplateElement? = null
        val newList = currentSideElements(prev.template).map {
            if (it.id == id) {
                val newEl = transform(it)
                mutatedElement = newEl
                newEl
            } else it
        }.toMutableList()

        if (activeCardStyle.linkCredentialsStyle && mutatedElement != null) {
            syncLinkedCredentials(newList, mutatedElement!!)
        }

        update(
            prev.copy(
                template = setSideElements(prev.template, newList),
                hasUnsavedChanges = true,
                canUndo = undoStack.isNotEmpty()
            )
        )
    }

    private fun syncLinkedCredentials(list: MutableList<TemplateElement>, primary: TemplateElement) {
        if (primary is TemplateElement.UsernameElement) {
            val idx = list.indexOfFirst { it is TemplateElement.PasswordElement && it.isShortVariant == primary.isShortVariant }
            if (idx != -1) {
                val peer = list[idx] as TemplateElement.PasswordElement
                val oldCX = peer.x + peer.width / 2f
                val oldCY = peer.y + peer.height / 2f
                list[idx] = peer.copy(
                    width = primary.width, height = primary.height,
                    x = oldCX - primary.width / 2f, y = oldCY - primary.height / 2f,
                    rotation = primary.rotation, textSizeSp = primary.textSizeSp,
                    textColor = primary.textColor, bgColor = primary.bgColor,
                    fontName = primary.fontName, isBold = primary.isBold,
                    textStrokeColor = primary.textStrokeColor, textStrokeWidth = primary.textStrokeWidth
                )
            }
        } else if (primary is TemplateElement.PasswordElement) {
            val idx = list.indexOfFirst { it is TemplateElement.UsernameElement && it.isShortVariant == primary.isShortVariant }
            if (idx != -1) {
                val peer = list[idx] as TemplateElement.UsernameElement
                val oldCX = peer.x + peer.width / 2f
                val oldCY = peer.y + peer.height / 2f
                list[idx] = peer.copy(
                    width = primary.width, height = primary.height,
                    x = oldCX - primary.width / 2f, y = oldCY - primary.height / 2f,
                    rotation = primary.rotation, textSizeSp = primary.textSizeSp,
                    textColor = primary.textColor, bgColor = primary.bgColor,
                    fontName = primary.fontName, isBold = primary.isBold,
                    textStrokeColor = primary.textStrokeColor, textStrokeWidth = primary.textStrokeWidth
                )
            }
        }
    }

    private fun mutateTemplate(transform: (Template) -> Template) {
        val prev = uiState.value
        pushCheckpoint()
        update(
            prev.copy(
                template = transform(prev.template),
                hasUnsavedChanges = true,
                canUndo = undoStack.isNotEmpty()
            )
        )
    }

    private fun update(state: EditorUiState) {
        _uiState.value = state
    }

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
        val bg = elements.filterIsInstance<TemplateElement.CardBackground>().firstOrNull()
        val frame = elements.filterIsInstance<TemplateElement.FrameElement>().firstOrNull()
        val users = elements.filter {
            it !is TemplateElement.CardBackground &&
                    it !is TemplateElement.FrameElement
        }
        val ordered = mutableListOf<TemplateElement>()
        ordered.addAll(users)
        if (frame != null) ordered.add(frame)
        if (bg != null) ordered.add(bg)
        return ordered
    }

    companion object {
        const val MIN_TEXT_SIZE_SP = 10f
        const val MAX_TEXT_SIZE_SP = 100f
        private const val UNDO_LIMIT = 20    // max snapshots kept
        private const val UNDO_DEBOUNCE_MS = 1000L // changes within 1s = one checkpoint

        /** Padding (template-dp) inside TextElement box — must match TemplateRenderer.TEXT_PAD_DP. */
        private const val TEXT_ELEMENT_PAD_DP = 6f
        /** Wrap-threshold hysteresis for corner-resize to avoid line-count oscillation jitter. */
        private const val TEXT_RESIZE_LINE_HYSTERESIS_DP = 1.5f
        private const val TEXT_RESIZE_WIDTH_SEARCH_STEPS = 10
    }
}

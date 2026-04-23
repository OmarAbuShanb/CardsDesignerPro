package dev.anonymous.cardsdesignerpro.ui.editor

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.data.model.CardSide
import dev.anonymous.cardsdesignerpro.databinding.ActivityEditorBinding
import dev.anonymous.cardsdesignerpro.ui.editor.canvas.CardCanvasView
import kotlinx.coroutines.launch

class EditorActivity : AppCompatActivity(), CardCanvasView.Listener {

    companion object {
        const val EXTRA_TEMPLATE_ID = "extra_template_id"
        private const val TAG = "TextResizeDiag.Activity"
        private const val DEFAULT_BOTTOM_SHEET_RATIO = 0.45f
    }

    private lateinit var binding: ActivityEditorBinding
    val viewModel: EditorViewModel by viewModels()

    private lateinit var bottomSheetFragment: EditorBottomSheetFragment
    private var bottomSheetHeightRatio = -1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val templateId = intent.getStringExtra(EXTRA_TEMPLATE_ID) ?: run { finish(); return }
        viewModel.init(templateId)

        setupToolbar()
        setupCanvas()
        setupBackSideSwitch()
        setupBottomSheet(savedInstanceState)
        setupBackHandler()
        observeViewModel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (binding.root.height > 0) {
            val ratio = binding.bottomSheetHost.height.toFloat() / binding.root.height
            outState.putFloat("bottom_sheet_ratio", ratio)
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.saveIfNeeded()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupToolbar() {

        binding.btnEditTitle.setOnClickListener { showRenameDialog() }
        binding.toolbar.setNavigationOnClickListener { handleBack() }
        binding.toolbar.inflateMenu(R.menu.menu_editor)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_undo -> {
                    viewModel.undo()
                    true
                }
                R.id.action_save -> {
                    viewModel.save { finish() }
                    true
                }
                R.id.action_restore -> {
                    if (viewModel.uiState.value.hasUnsavedChanges) {
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.editor_restore_title)
                            .setMessage(R.string.editor_restore_message)
                            .setNegativeButton(R.string.btn_cancel, null)
                            .setPositiveButton(R.string.btn_restore) { _, _ ->
                                viewModel.restoreOriginal()
                            }
                            .show()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupCanvas() {
        binding.cardCanvas.listener = this
    }

    private fun setupBackSideSwitch() {
        binding.switchBackSide.setOnCheckedChangeListener { _, isChecked ->
            // Sync only if state doesn't match (avoid feedback loop)
            if (viewModel.isBackSideEnabled != isChecked) {
                viewModel.toggleBackSide()
            }
        }

        binding.switchShortNumbers.setOnCheckedChangeListener { _, isChecked ->
            if (viewModel.currentTemplate.isShortNumbersEnabled != isChecked) {
                viewModel.toggleShortNumbers(isChecked)
            }
        }

        // Tapping the active-side label toggles between front and back
        binding.llActiveSideToggle.setOnClickListener {
            val newSide = if (viewModel.activeSide == CardSide.FRONT) CardSide.BACK else CardSide.FRONT
            viewModel.setActiveSide(newSide)
        }
    }

    private fun setupBottomSheet(savedInstanceState: Bundle?) {
        bottomSheetHeightRatio = savedInstanceState?.getFloat("bottom_sheet_ratio", -1f) ?: -1f

        // Set initial height eagerly using screen height (before views are measured)
        val screenH = resources.displayMetrics.heightPixels
        val initialRatio =
            if (bottomSheetHeightRatio > 0f) bottomSheetHeightRatio else DEFAULT_BOTTOM_SHEET_RATIO
        binding.bottomSheetHost.layoutParams.height = (screenH * initialRatio).toInt()

        // Setup drag handler immediately
        setupBottomSheetDrag()

        // Once layout is complete, refine with actual measurements
        binding.root.post { applyBottomSheetHeight() }

        if (savedInstanceState == null) {
            bottomSheetFragment = EditorBottomSheetFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.bottom_sheet_container, bottomSheetFragment)
                .commit()
        } else {
            bottomSheetFragment = supportFragmentManager
                .findFragmentById(R.id.bottom_sheet_container) as? EditorBottomSheetFragment
                ?: EditorBottomSheetFragment().also {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.bottom_sheet_container, it).commit()
                }
        }
    }

    /** Computes the minimum sheet height (handle + tabs) and clamps the current height. */
    private fun computeMinHeight(): Int {
        val dp = resources.displayMetrics.density
        val tabLayout = findViewById<View>(R.id.tab_layout)
        val tabHeight = (tabLayout?.height ?: 0).coerceAtLeast((48 * dp).toInt())
        val handleHeight = binding.dragHandelView.height.coerceAtLeast((24 * dp).toInt())
        return handleHeight + tabHeight
    }

    /** Applies the stored ratio (or default 45%) as the sheet height, clamped to [min, max]. */
    private fun applyBottomSheetHeight() {
        val minH = computeMinHeight()
        val maxH = binding.root.height - binding.appBar.height
        val ratio =
            if (bottomSheetHeightRatio > 0f) bottomSheetHeightRatio else DEFAULT_BOTTOM_SHEET_RATIO
        val target = (binding.root.height * ratio).toInt().coerceIn(minH, maxH)
        val p = binding.bottomSheetHost.layoutParams
        p.height = target
        binding.bottomSheetHost.layoutParams = p
    }

    /** Wires up the drag-handle for resize, enforcing min/max at all times. */
    private fun setupBottomSheetDrag() {
        var initialDragY = 0f
        var initialHeight = 0

        binding.dragHandelView.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialDragY = event.rawY
                    initialHeight = binding.bottomSheetHost.height
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val minH = computeMinHeight()
                    val maxH = binding.root.height - binding.appBar.height

                    val deltaY = initialDragY - event.rawY
                    val newHeight = (initialHeight + deltaY).toInt().coerceIn(minH, maxH)

                    val p = binding.bottomSheetHost.layoutParams
                    if (p.height != newHeight) {
                        p.height = newHeight
                        binding.bottomSheetHost.layoutParams = p
                        if (binding.root.height > 0) {
                            bottomSheetHeightRatio = newHeight.toFloat() / binding.root.height
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this) { handleBack() }
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private var lastRenderedBackEnabled: Boolean? = null
    private var lastRenderedShortEnabled: Boolean? = null
    private var lastRenderedSide: CardSide? = null
    private var isFlipping = false
    /**
     * Becomes true after the first completed layout frame.
     * Set via post{} so that back-to-back renderState calls during startup
     * (e.g. immediate StateFlow emission + async template load) all see false.
     */
    private var hasRenderedOnce = false

    private fun renderState(state: EditorUiState) {
        val template = state.template
        binding.tvTemplateName.text = template.name

        if (state.sideSwitched && !isFlipping) {
            isFlipping = true
            val isRtl = binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val animOutRot = if (isRtl) -90f else 90f
            val animInRot = if (isRtl) 90f else -90f

            // Slightly lift the card (Z translation) during flip for enhanced 3D effect
            binding.cardCanvas.animate().translationZ(50f).setDuration(150).start()

            android.animation.ObjectAnimator.ofFloat(binding.cardCanvas, View.ROTATION_Y, 0f, animOutRot).apply {
                duration = 150
                interpolator = android.view.animation.AccelerateInterpolator()
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        val current = viewModel.uiState.value
                        binding.cardCanvas.bind(current.template, current.selectedElementId, current.template.activeSide)
                        binding.cardCanvas.rotationY = animInRot

                        android.animation.ObjectAnimator.ofFloat(binding.cardCanvas, View.ROTATION_Y, animInRot, 0f).apply {
                            duration = 150
                            interpolator = android.view.animation.DecelerateInterpolator()
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: android.animation.Animator) {
                                    isFlipping = false
                                    binding.cardCanvas.animate().translationZ(0f).setDuration(150).start()
                                    val finalState = viewModel.uiState.value
                                    binding.cardCanvas.bind(finalState.template, finalState.selectedElementId, finalState.template.activeSide)
                                }
                            })
                            start()
                        }
                    }
                })
                start()
            }
        } else if (!isFlipping) {
            binding.cardCanvas.bind(template, state.selectedElementId, template.activeSide)
        }

        // Side toggle visibility — animate only after the first render (not on screen entry)
        val backEnabled = template.isBackSideEnabled
        val targetVis = if (backEnabled) View.VISIBLE else View.GONE

        if (binding.llActiveSideToggle.visibility != targetVis) {
            if (hasRenderedOnce) {
                val transition = android.transition.AutoTransition().apply { duration = 250 }
                android.transition.TransitionManager.beginDelayedTransition(
                    binding.llActiveSideToggle.parent as android.view.ViewGroup, transition
                )
            }
            binding.llActiveSideToggle.visibility = targetVis
        }

        // Schedule the "settled" flag for the NEXT frame — any same-frame state
        // updates during startup will still see hasRenderedOnce=false.
        if (!hasRenderedOnce) binding.root.post { hasRenderedOnce = true }

        if (backEnabled && template.activeSide != lastRenderedSide) {
            val isFront = template.activeSide == CardSide.FRONT
            binding.tvSideTitle.text = if (isFront) getString(R.string.editor_front_side_title) else getString(R.string.editor_back_side_title)
            binding.tvSideSubtitle.text = if (isFront) getString(R.string.editor_front_side_subtitle) else getString(R.string.editor_back_side_subtitle)
            lastRenderedSide = template.activeSide
        }

        // Back-side switch — only touch the widget when the value actually changed
        if (lastRenderedBackEnabled != backEnabled) {
            lastRenderedBackEnabled = backEnabled
            binding.switchBackSide.setOnCheckedChangeListener(null)
            binding.switchBackSide.isChecked = backEnabled
            binding.switchBackSide.jumpDrawablesToCurrentState()
            binding.switchBackSide.setOnCheckedChangeListener { _, isChecked ->
                if (viewModel.isBackSideEnabled != isChecked) viewModel.toggleBackSide()
            }
        }

        val shortEnabled = template.isShortNumbersEnabled
        if (lastRenderedShortEnabled != shortEnabled) {
            lastRenderedShortEnabled = shortEnabled
            binding.switchShortNumbers.setOnCheckedChangeListener(null)
            binding.switchShortNumbers.isChecked = shortEnabled
            binding.switchShortNumbers.jumpDrawablesToCurrentState()
            binding.switchShortNumbers.setOnCheckedChangeListener { _, isChecked ->
                if (viewModel.currentTemplate.isShortNumbersEnabled != isChecked) viewModel.toggleShortNumbers(isChecked)
            }
        }

        // Notify bottom sheet
        if (::bottomSheetFragment.isInitialized && bottomSheetFragment.isAdded) {
            bottomSheetFragment.onUiStateChanged(state)
        }

        // After side switch — consume flag
        if (state.sideSwitched) viewModel.consumeSideSwitched()

        // Enable/disable toolbar actions based on unsaved changes
        val hasChanges = state.hasUnsavedChanges
        val canUndo = state.canUndo
        binding.toolbar.menu.findItem(R.id.action_undo)?.let { item ->
            item.isEnabled = canUndo
            item.icon?.alpha = if (canUndo) 255 else 80
        }
        binding.toolbar.menu.findItem(R.id.action_save)?.let { item ->
            item.isEnabled = hasChanges
            item.icon?.alpha = if (hasChanges) 255 else 80
        }
        binding.toolbar.menu.findItem(R.id.action_restore)?.let { item ->
            item.isEnabled = hasChanges
            item.icon?.alpha = if (hasChanges) 255 else 80
        }
    }

    // ── CardCanvasView.Listener ───────────────────────────────────────────────

    override fun onElementSelected(id: String?) {
        viewModel.selectElement(id)
        if (id != null && ::bottomSheetFragment.isInitialized) {
            bottomSheetFragment.switchToPropertiesTab()
        }
    }

    override fun onElementMoved(id: String, dx: Float, dy: Float) =
        viewModel.moveElement(id, dx, dy)

    override fun onElementResized(id: String, newW: Float, newH: Float) =
        viewModel.resizeElement(id, newW, newH)

    override fun onElementRotated(id: String, angleDelta: Float) =
        viewModel.rotateElement(id, angleDelta)

    override fun onCardBackgroundSelected() {
        viewModel.selectElement("card_background")
        if (::bottomSheetFragment.isInitialized) bottomSheetFragment.switchToPropertiesTab()
    }

    override fun onCardHeightDrag(deltaRatio: Float) {
        val current = viewModel.currentTemplate.card.heightRatio
        val target  = current + deltaRatio
        // Haptic bump when hitting the 1.2× height ceiling
        if (target >= 1.2f && current < 1.2f) {
            binding.cardCanvas.performHapticFeedback(
                android.view.HapticFeedbackConstants.CLOCK_TICK
            )
        }
        viewModel.updateCardHeightRatio(target)
    }

    override fun onShapeWidthResized(id: String, newWidth: Float) =
        viewModel.resizeShapeWidth(id, newWidth)

    override fun onShapeHeightResized(id: String, newY: Float, newHeight: Float) =
        viewModel.resizeShapeHeight(id, newY, newHeight)

    override fun onTextWidthResized(id: String, newWidth: Float, pxPerDp: Float) =
        viewModel.resizeTextWidth(id, newWidth, pxPerDp).also {
            Log.d(TAG, "CALLBACK width-resize id=$id newW=$newWidth pxPerDp=$pxPerDp")
        }

    override fun onTextFontScaled(id: String, newSizeSp: Float, newWidth: Float, pxPerDp: Float) =
        viewModel.scaleTextFontSize(id, newSizeSp, newWidth, pxPerDp).also {
            Log.d(
                TAG,
                "CALLBACK corner-resize id=$id newSp=$newSizeSp newW=$newWidth pxPerDp=$pxPerDp"
            )
        }

    // Start drag session: VM locks wrapping bucket for jitter-free corner-resize.
    override fun onTextCornerResizeStart(id: String, pxPerDp: Float) =
        viewModel.beginTextCornerResize(id, pxPerDp).also {
            Log.d(TAG, "CALLBACK corner-resize START id=$id pxPerDp=$pxPerDp")
        }

    // End drag session: VM applies one final natural wrap/height normalization.
    override fun onTextCornerResizeEnd(id: String, pxPerDp: Float) =
        viewModel.endTextCornerResize(id, pxPerDp).also {
            Log.d(TAG, "CALLBACK corner-resize END id=$id pxPerDp=$pxPerDp")
        }

    override fun onElementDeleteRequested(id: String) {
        viewModel.currentElements.firstOrNull { it.id == id } ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("حذف العنصر")
            .setMessage("هل تريد حذف هذا العنصر؟")
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                viewModel.deleteElement(id)
            }
            .show()
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showRenameDialog() {
        val reqKey = "rename_template_editor"
        supportFragmentManager.setFragmentResultListener(reqKey, this) { _, bundle ->
            val name = bundle.getString("name")
            if (!name.isNullOrEmpty()) viewModel.renameTemplate(name)
        }
        if (supportFragmentManager.findFragmentByTag(dev.anonymous.cardsdesignerpro.ui.common.TemplateNameDialogFragment.TAG) == null) {
            dev.anonymous.cardsdesignerpro.ui.common.TemplateNameDialogFragment.newInstance(
                titleRes = R.string.dialog_rename_template_title,
                positiveBtnRes = R.string.btn_save,
                initialName = viewModel.currentTemplate.name,
                requestKey = reqKey
            ).show(supportFragmentManager, dev.anonymous.cardsdesignerpro.ui.common.TemplateNameDialogFragment.TAG)
        }
    }

    override fun finish() {
        val intent = android.content.Intent().apply {
            putExtra(EXTRA_TEMPLATE_ID, viewModel.currentTemplate.id)
            putExtra("extra_template_version", viewModel.currentTemplate.version)
        }
        setResult(RESULT_OK, intent)
        super.finish()
    }

    private fun handleBack() {
        if (viewModel.uiState.value.hasUnsavedChanges) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.editor_back_confirm_title)
                .setMessage(R.string.editor_back_confirm_message)
                .setNeutralButton(R.string.btn_cancel, null)          // Cancel: stay in editor
                .setNegativeButton(R.string.btn_discard) { _, _ ->
                    viewModel.discardAndCleanup { finish() }
                }
                .setPositiveButton(R.string.btn_save) { _, _ ->
                    viewModel.save { finish() }
                }
                .show()
        } else {
            finish()
        }
    }
}

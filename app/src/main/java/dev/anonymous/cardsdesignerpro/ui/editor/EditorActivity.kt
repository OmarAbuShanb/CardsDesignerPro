package dev.anonymous.cardsdesignerpro.ui.editor

import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.data.model.CardSide
import dev.anonymous.cardsdesignerpro.databinding.ActivityEditorBinding
import dev.anonymous.cardsdesignerpro.databinding.DialogTemplateNameBinding
import dev.anonymous.cardsdesignerpro.ui.editor.canvas.CardCanvasView
import kotlinx.coroutines.launch

class EditorActivity : AppCompatActivity(), CardCanvasView.Listener {

    companion object {
        const val EXTRA_TEMPLATE_ID = "extra_template_id"
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
                R.id.action_save -> {
                    viewModel.save()
                    finish()
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
        val initialRatio = if (bottomSheetHeightRatio > 0f) bottomSheetHeightRatio else 0.35f
        binding.bottomSheetHost.layoutParams.height = (screenH * initialRatio).toInt()

        // Setup drag handler immediately
        setupBottomSheetDrag()

        // Once layout is complete, refine with actual measurements
        binding.bottomSheetHost.post { applyBottomSheetHeight() }

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

    /** Applies the stored ratio (or default 35%) as the sheet height, clamped to [min, max]. */
    private fun applyBottomSheetHeight() {
        val minH = computeMinHeight()
        val maxH = binding.root.height - binding.appBar.height
        val ratio = if (bottomSheetHeightRatio > 0f) bottomSheetHeightRatio else 0.35f
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
    private var lastRenderedSide: CardSide? = null

    private fun renderState(state: EditorUiState) {
        val template = state.template
        binding.tvTemplateName.text = template.name

        binding.cardCanvas.bind(template, state.selectedElementId, template.activeSide)

        // Side toggle visibility
        val backEnabled = template.isBackSideEnabled
        binding.llActiveSideToggle.visibility =
            if (backEnabled) View.VISIBLE else View.GONE

        if (backEnabled && template.activeSide != lastRenderedSide) {
            val isFront = template.activeSide == CardSide.FRONT
            binding.tvSideTitle.text = if (isFront) "الوجه الأمامي" else "الوجه الخلفي"
            binding.tvSideSubtitle.text = if (isFront) "إضغط لرؤية الوجه الخلفي" else "إضغط لرؤية الوجه الأمامي"
            lastRenderedSide = template.activeSide
        }

        binding.tvOutOfBounds.visibility =
            if (state.hasOutOfBoundsElements) View.VISIBLE else View.GONE

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

        // Notify bottom sheet
        if (::bottomSheetFragment.isInitialized && bottomSheetFragment.isAdded) {
            bottomSheetFragment.onUiStateChanged(state)
        }

        // After side switch — consume flag
        if (state.sideSwitched) viewModel.consumeSideSwitched()

        // Enable/disable toolbar actions based on unsaved changes
        val hasChanges = state.hasUnsavedChanges
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
        // Haptic bump when hitting the 1.5× height ceiling
        if (target >= 1.5f && current < 1.5f) {
            binding.cardCanvas.performHapticFeedback(
                android.view.HapticFeedbackConstants.CLOCK_TICK
            )
        }
        viewModel.updateCardHeightRatio(target)
    }

    override fun onElementDeleteRequested(id: String) {
        val el = viewModel.currentElements.firstOrNull { it.id == id } ?: return
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
        val dialogBinding = DialogTemplateNameBinding.inflate(layoutInflater)
        dialogBinding.etName.setText(viewModel.currentTemplate.name)
        dialogBinding.etName.selectAll()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_rename_template_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val name = dialogBinding.etName.text?.toString()?.trim()
                if (!name.isNullOrEmpty()) viewModel.renameTemplate(name)
            }
            .show()
    }

    private fun handleBack() {
        if (viewModel.uiState.value.hasUnsavedChanges) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.editor_back_confirm_title)
                .setMessage(R.string.editor_back_confirm_message)
                .setNegativeButton(R.string.btn_discard) { _, _ ->
                    viewModel.discardAndCleanup()
                    finish()
                }
                .setPositiveButton(R.string.btn_save) { _, _ ->
                    viewModel.save()
                    finish()
                }
                .show()
        } else {
            finish()
        }
    }
}

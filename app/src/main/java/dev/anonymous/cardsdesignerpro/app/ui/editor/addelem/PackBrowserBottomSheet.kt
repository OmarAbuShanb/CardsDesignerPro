package dev.anonymous.cardsdesignerpro.app.ui.editor.addelem

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.setFragmentResult
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import dev.anonymous.cardsdesignerpro.app.R
import dev.anonymous.cardsdesignerpro.app.databinding.BottomSheetPackBrowserBinding

/**
 * Bottom sheet that lets the user browse pre-made SVG assets organized by category.
 * Selecting an item delivers the pack path via [setFragmentResult] with key [RESULT_KEY].
 */
class PackBrowserBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val RESULT_KEY = "pack_asset_result"
        const val KEY_PACK_PATH = "pack_path"
        const val KEY_PACK_CATEGORY = "pack_category"

        private val CATEGORIES = listOf(
            Category("packs/credential_icons",    R.string.pack_tab_credential_icons),
            Category("packs/general_icons",       R.string.pack_tab_general_icons),
            Category("packs/fields",              R.string.pack_tab_fields),
            Category("packs/dividers",            R.string.pack_tab_dividers),
            Category("packs/logos",               R.string.pack_tab_logos),
            Category("packs/visual_shapes",       R.string.pack_tab_visual_shapes),
            Category("packs/corners",             R.string.pack_tab_corners),
            Category("packs/misc",                R.string.pack_tab_misc),
            Category("packs/characters",          R.string.pack_tab_characters)
        )
    }

    private data class Category(val dir: String, val titleRes: Int)

    private var _binding: BottomSheetPackBrowserBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: PackAssetAdapter
    private var selectedTabIndex = 0
    private val tabViews = mutableListOf<TextView>()

    // ── Colors ────────────────────────────────────────────────────────────────
    private val borderColor by lazy { resolveThemeColor(com.google.android.material.R.attr.colorOutline) }
    private val selectedBgColor by lazy { resolveThemeColor(android.R.attr.colorPrimary) }
    private val selectedTextColor by lazy { resolveThemeColor(com.google.android.material.R.attr.colorOnPrimary) }
    private val unselectedTextColor by lazy { resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetPackBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = PackAssetAdapter { packPath ->
            // Premium gate — show dialog on top of bottom sheet
            val lm = dev.anonymous.cardsdesignerpro.app.data.license.LicenseManager.getInstance(requireContext())
            if (!lm.canAccess(dev.anonymous.cardsdesignerpro.app.data.license.PremiumFeature.READY_MADE_ELEMENTS)) {
                dev.anonymous.cardsdesignerpro.app.ui.license.LicenseDialogs.showPremiumReadyMadeDialog(requireActivity()) {
                    dev.anonymous.cardsdesignerpro.app.ui.license.LicenseDialogs.showActivationDialog(requireActivity()) {}
                }
                return@PackAssetAdapter
            }

            val bundle = Bundle().apply {
                putString(KEY_PACK_PATH, packPath)
                putString(KEY_PACK_CATEGORY, CATEGORIES[selectedTabIndex].dir)
            }
            parentFragmentManager.setFragmentResult(RESULT_KEY, bundle)
            dismiss()
        }

        binding.rvPackAssets.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvPackAssets.adapter = adapter

        val restoredTab = savedInstanceState?.getInt("selected_tab", 0) ?: 0

        buildTabs()
        selectTab(restoredTab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("selected_tab", selectedTabIndex)
    }

    // ── Tab bar ───────────────────────────────────────────────────────────────

    private fun buildTabs() {
        val container = binding.tabContainer
        container.removeAllViews()
        tabViews.clear()

        CATEGORIES.forEachIndexed { index, cat ->
            val tv = TextView(requireContext()).apply {
                text = getString(cat.titleRes)
                textSize = 13f
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener { if (index != selectedTabIndex) selectTab(index) }
            }
            val lp = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            container.addView(tv, lp)
            tabViews.add(tv)
        }
    }

    private fun selectTab(index: Int) {
        selectedTabIndex = index
        tabViews.forEachIndexed { i, tv ->
            val selected = (i == index)
            tv.background = createTabBackground(selected)
            tv.setTextColor(if (selected) selectedTextColor else unselectedTextColor)
        }

        val cat = CATEGORIES[index]
        val assets = loadAssetsFromDir(cat.dir)
        adapter.submitList(cat.dir, assets)
    }

    private fun createTabBackground(selected: Boolean): MaterialShapeDrawable {
        val shape = ShapeAppearanceModel.builder()
            .setAllCornerSizes(dp(8).toFloat())
            .build()
        return MaterialShapeDrawable(shape).apply {
            if (selected) {
                fillColor = android.content.res.ColorStateList.valueOf(selectedBgColor)
                strokeWidth = 0f
            } else {
                fillColor = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                strokeWidth = dp(1.5f).toFloat()
                strokeColor = android.content.res.ColorStateList.valueOf(borderColor)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun loadAssetsFromDir(dir: String): List<String> {
        return runCatching {
            requireContext().assets.list(dir)
                ?.sorted()
                ?.map { "$dir/$it" }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun resolveThemeColor(attr: Int): Int {
        val ta = requireContext().obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, Color.BLACK)
        ta.recycle()
        return color
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

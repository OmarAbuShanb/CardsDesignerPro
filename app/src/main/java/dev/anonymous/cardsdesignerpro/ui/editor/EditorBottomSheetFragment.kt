package dev.anonymous.cardsdesignerpro.ui.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.databinding.FragmentEditorBottomSheetBinding
import dev.anonymous.cardsdesignerpro.ui.editor.addelem.AddElementFragment
import dev.anonymous.cardsdesignerpro.ui.editor.elements.ElementsListFragment
import dev.anonymous.cardsdesignerpro.ui.editor.properties.PropertiesContainerFragment

class EditorBottomSheetFragment : Fragment() {

    private var _binding: FragmentEditorBottomSheetBinding? = null
    private val binding get() = _binding!!
    val viewModel: EditorViewModel by activityViewModels()

    private val tabTitles by lazy {
        listOf(
            getString(R.string.tab_add_element),
            getString(R.string.tab_elements_list),
            getString(R.string.tab_properties)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEditorBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 3
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> AddElementFragment()
                1 -> ElementsListFragment()
                else -> PropertiesContainerFragment()
            }
        }
        // Keep all 3 tabs alive so Properties fragment always receives state updates
        binding.viewPager.offscreenPageLimit = 2

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = tabTitles[pos]
        }.attach()

        // NOTE: BottomSheetBehavior is configured in EditorActivity on the host
        // FragmentContainerView (a direct child of CoordinatorLayout), NOT here.
    }

    fun onUiStateChanged(state: EditorUiState) {
        if (_binding == null) return
        childFragmentManager.fragments.forEach { frag ->
            when (frag) {
                is ElementsListFragment -> frag.onUiStateChanged(state)
                is PropertiesContainerFragment -> frag.onUiStateChanged(state)
                is AddElementFragment -> frag.onUiStateChanged()
            }
        }
    }

    fun switchToPropertiesTab() {
        _binding?.viewPager?.currentItem = 2
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

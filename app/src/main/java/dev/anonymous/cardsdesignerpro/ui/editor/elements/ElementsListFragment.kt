package dev.anonymous.cardsdesignerpro.ui.editor.elements

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.databinding.FragmentElementsListBinding
import dev.anonymous.cardsdesignerpro.ui.editor.EditorUiState
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel

class ElementsListFragment : Fragment() {

    private var _binding: FragmentElementsListBinding? = null
    private val binding get() = _binding!!
    val viewModel: EditorViewModel by activityViewModels()
    private lateinit var adapter: ElementAdapter

    private var isDragging = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentElementsListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        adapter.submitList(viewModel.currentElements.toList())
    }

    private fun isLocked(el: TemplateElement?) =
        el is TemplateElement.CardBackground ||
        el is TemplateElement.FrameElement

    private fun setupRecyclerView() {
        adapter = ElementAdapter(
            onSelect = { id -> viewModel.selectElement(id) },
            onVisibilityToggle = { id -> viewModel.toggleVisibility(id) },
            onEdit = { id ->
                viewModel.selectElement(id)
                (parentFragment as? dev.anonymous.cardsdesignerpro.ui.editor.EditorBottomSheetFragment)
                    ?.switchToPropertiesTab()
            },
            onDelete = { id -> viewModel.deleteElement(id) }
        )
        binding.rvElements.layoutManager = LinearLayoutManager(requireContext())
        binding.rvElements.adapter = adapter
        binding.rvElements.setHasFixedSize(true)
        (binding.rvElements.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to   = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false

                val items = adapter.currentList
                if (isLocked(items.getOrNull(from)) || isLocked(items.getOrNull(to))) return false

                isDragging = true
                val mutable = items.toMutableList()
                mutable.add(to, mutable.removeAt(from))
                adapter.submitList(mutable.toList())
                return true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (isDragging) {
                    val finalIds = adapter.currentList.map { it.id }
                    viewModel.reorderElementsToOrder(finalIds)
                    isDragging = false
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled(): Boolean = true
        })
        touchHelper.attachToRecyclerView(binding.rvElements)
    }

    fun onUiStateChanged(state: EditorUiState) {
        if (_binding == null) return
        adapter.selectedId = state.selectedElementId
        if (state.sideSwitched) {
            adapter.submitList(null)
            adapter.submitList(viewModel.currentElements.toList())
        } else if (!isDragging) {
            adapter.submitList(viewModel.currentElements.toList())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

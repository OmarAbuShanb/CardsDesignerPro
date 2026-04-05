package dev.anonymous.cardsdesignerpro.ui.editor.properties

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dev.anonymous.cardsdesignerpro.R
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import dev.anonymous.cardsdesignerpro.ui.editor.EditorUiState
import dev.anonymous.cardsdesignerpro.ui.editor.EditorViewModel

/**
 * Container fragment for the Properties tab.
 * Swaps child property fragments based on the selected element type.
 */
class PropertiesContainerFragment : Fragment(), PropertyFragment {

    val viewModel: EditorViewModel by activityViewModels()
    private var lastSelectedId: String? = "INIT" // force first draw

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_properties_container, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lastSelectedId = "INIT" // reset so first onUiStateChanged always commits
        onUiStateChanged(viewModel.uiState.value)
    }

    override fun onUiStateChanged(state: EditorUiState) {
        val selectedId = state.selectedElementId

        if (selectedId == lastSelectedId) {
            // Same element — just forward updates to the current child
            (childFragmentManager.findFragmentById(R.id.properties_root) as? PropertyFragment)
                ?.onUiStateChanged(state)
            return
        }
        lastSelectedId = selectedId

        if (!isAdded || view == null) return

        val fragment: Fragment = when {
            selectedId == null -> NoSelectionFragment()
            selectedId == "card_background" -> CardPropertiesFragment()
            else -> {
                when (viewModel.currentElements.firstOrNull { it.id == selectedId }) {
                    is TemplateElement.TextElement -> TextPropertiesFragment()
                    is TemplateElement.UsernameElement -> UsernamePropertiesFragment()
                    is TemplateElement.PasswordElement -> PasswordPropertiesFragment()
                    is TemplateElement.ImageElement -> ImagePropertiesFragment()
                    is TemplateElement.QrElement -> QrPropertiesFragment()
                    is TemplateElement.DateElement -> DatePropertiesFragment()
                    is TemplateElement.FrameElement -> FramePropertiesFragment()
                    is TemplateElement.BackgroundDecorationElement -> BackgroundDecorationPropertiesFragment()
                    else -> NoSelectionFragment()
                }
            }
        }

        childFragmentManager.beginTransaction()
            .replace(R.id.properties_root, fragment)
            .commitAllowingStateLoss()
    }
}

/** Marker interface so the container can forward updates to the active property fragment. */
interface PropertyFragment {
    fun onUiStateChanged(state: EditorUiState)
}

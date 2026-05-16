package dev.anonymous.cardsdesignerpro.app.ui.common

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.anonymous.cardsdesignerpro.app.R

/**
 * A Material3 bottom sheet that lets the user choose between Gallery (photo picker)
 * and Files (SAF document picker) to select an image.
 *
 * The selected [Uri] is delivered via [setFragmentResult] using the caller's [requestKey].
 *
 * Activity-result launchers are registered inside this fragment, so the calling
 * fragment's lifecycle is not affected — this prevents the "back-side reset" bug
 * that occurred when launchers were registered in property fragments.
 */
class ImageSourceBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val KEY_URI = "image_source_uri"
        private const val ARG_REQUEST_KEY = "arg_request_key"

        fun show(
            fragmentManager: androidx.fragment.app.FragmentManager,
            requestKey: String
        ) {
            val sheet = ImageSourceBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_REQUEST_KEY, requestKey)
                }
            }
            sheet.show(fragmentManager, "image_source_sheet")
        }
    }

    private val requestKey: String
        get() = requireArguments().getString(ARG_REQUEST_KEY)!!

    // ── Gallery picker (system Photo Picker) ─────────────────────────────────
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        deliverAndDismiss(uri)
    }

    // ── File picker — OpenDocument forces SAF (not photo picker) ──────────────
    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        deliverAndDismiss(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_image_source, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.option_gallery).setOnClickListener {
            galleryLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        view.findViewById<View>(R.id.option_files).setOnClickListener {
            fileLauncher.launch(arrayOf("image/*"))
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener { dlg ->
            val d = dlg as BottomSheetDialog
            val bottomSheet = d.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener

            // Make the sheet container transparent so the MaterialCardView margins are visible
            bottomSheet.background = ColorDrawable(Color.TRANSPARENT)

            // Expand immediately
            BottomSheetBehavior.from(bottomSheet).state = BottomSheetBehavior.STATE_EXPANDED
        }
        return dialog
    }

    private fun deliverAndDismiss(uri: Uri?) {
        if (uri != null) {
            parentFragmentManager.setFragmentResult(
                requestKey,
                Bundle().apply {
                    putParcelable(KEY_URI, uri)
                }
            )
        }
        dismissAllowingStateLoss()
    }
}

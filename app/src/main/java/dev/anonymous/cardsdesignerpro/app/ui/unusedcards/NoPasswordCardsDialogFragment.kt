package dev.anonymous.cardsdesignerpro.app.ui.unusedcards

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.anonymous.cardsdesignerpro.app.R
import dev.anonymous.cardsdesignerpro.app.databinding.DialogNoPasswordCardsBinding
import dev.anonymous.cardsdesignerpro.app.databinding.ItemNoPasswordDialogRowBinding
import java.time.LocalDate

class NoPasswordCardsDialogFragment : DialogFragment() {

    private var _binding: DialogNoPasswordCardsBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: UnusedCardsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_CardsDesignerPro_CustomDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogNoPasswordCardsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[UnusedCardsViewModel::class.java]

        setupList()
        binding.btnCloseDialog.setOnClickListener {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val metrics = resources.displayMetrics
            val maxWidth = (720 * metrics.density).toInt()
            val width = (metrics.widthPixels * 0.92f).toInt().coerceAtMost(maxWidth)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun setupList() {
        val targetPackage = arguments?.getString(ARG_PACKAGE_NAME)
        var noPassCards = viewModel.getFilteredCardsList(
            targetPackage = targetPackage ?: ""
        ).filter { it.password.isEmpty() }
        
        if (!targetPackage.isNullOrEmpty()) {
            noPassCards = noPassCards.filter { it.packageName == targetPackage }
        }
        
        noPassCards = noPassCards.sortedWith(compareBy { it.date ?: LocalDate.MAX })

        binding.rvDialogNoPasswordCards.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDialogNoPasswordCards.adapter = NoPasswordCardsAdapter(noPassCards)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class NoPasswordCardsAdapter(
        private val items: List<UnusedCard>
    ) : RecyclerView.Adapter<NoPasswordCardsAdapter.VH>() {

        class VH(val b: ItemNoPasswordDialogRowBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemNoPasswordDialogRowBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.b.tvDialogRowUsername.text = item.username
            holder.b.tvDialogRowPackage.text = item.packageName
            holder.b.tvDialogRowCreated.text = item.dateText
        }

        override fun getItemCount() = items.size
    }

    companion object {
        const val TAG = "NoPasswordCardsDialogFragment"
        private const val ARG_PACKAGE_NAME = "arg_package_name"
        
        fun newInstance(packageName: String? = null): NoPasswordCardsDialogFragment {
            val fragment = NoPasswordCardsDialogFragment()
            if (packageName != null) {
                val args = Bundle()
                args.putString(ARG_PACKAGE_NAME, packageName)
                fragment.arguments = args
            }
            return fragment
        }
    }
}

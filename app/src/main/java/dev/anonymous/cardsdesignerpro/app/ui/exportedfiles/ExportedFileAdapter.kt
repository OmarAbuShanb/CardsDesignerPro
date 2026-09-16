package dev.anonymous.cardsdesignerpro.app.ui.exportedfiles

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.anonymous.cardsdesignerpro.app.R
import dev.anonymous.cardsdesignerpro.app.databinding.ItemExportedFileBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExportedFileAdapter(
    private val onFileClick: (PdfFileInfo) -> Unit
) : ListAdapter<PdfFileInfo, ExportedFileAdapter.FileViewHolder>(FileDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemExportedFileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FileViewHolder(
        private val binding: ItemExportedFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault())

        fun bind(file: PdfFileInfo) {
            binding.ivPdfIcon.setImageResource(R.drawable.ic_pdf_file)
            binding.tvFileName.text = file.name
            binding.tvFileSize.text = formatFileSize(file.size)
            binding.tvFileDate.text = dateFormat.format(Date(file.lastModified))

            binding.root.setOnClickListener {
                onFileClick(file)
            }
        }

        private fun formatFileSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
            }
        }
    }

    private class FileDiffCallback : DiffUtil.ItemCallback<PdfFileInfo>() {
        override fun areItemsTheSame(oldItem: PdfFileInfo, newItem: PdfFileInfo): Boolean {
            return oldItem.uri == newItem.uri
        }

        override fun areContentsTheSame(oldItem: PdfFileInfo, newItem: PdfFileInfo): Boolean {
            return oldItem == newItem
        }
    }
}

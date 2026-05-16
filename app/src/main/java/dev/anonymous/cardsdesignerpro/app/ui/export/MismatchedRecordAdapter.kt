package dev.anonymous.cardsdesignerpro.app.ui.export

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.anonymous.cardsdesignerpro.app.R

/**
 * Adapter for displaying [MismatchedRecord] entries inside the validation dialog.
 * Each item shows the record's sequential index (1-based position in the data),
 * and highlights which of username/password had a length mismatch.
 */
class MismatchedRecordAdapter(
    private val items: List<MismatchedRecord>
) : RecyclerView.Adapter<MismatchedRecordAdapter.VH>() {

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvIndex: TextView    = itemView.findViewById(R.id.tv_record_index)
        val tvUsername: TextView = itemView.findViewById(R.id.tv_mismatch_username)
        val tvPassword: TextView = itemView.findViewById(R.id.tv_mismatch_password)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_mismatch_record, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx  = holder.itemView.context

        // "المستخدم رقم 3" — reflects the user's order in the merged file, not the Excel row
        holder.tvIndex.text = ctx.getString(R.string.item_mismatch_record_index, item.recordIndex)

        // Username row — only shown when username has a mismatch
        val userMismatch = item.username != null &&
                item.expectedUserLen != null &&
                item.username.length != item.expectedUserLen
        if (userMismatch) {
            holder.tvUsername.text = ctx.getString(
                R.string.item_mismatch_username,
                item.username,
                item.username.length
            )
            holder.tvUsername.visibility = View.VISIBLE
        } else {
            holder.tvUsername.visibility = View.GONE
        }

        // Password row — only shown when password has a mismatch
        val passMismatch = item.password != null &&
                item.expectedPassLen != null &&
                item.password.length != item.expectedPassLen
        if (passMismatch) {
            holder.tvPassword.text = ctx.getString(
                R.string.item_mismatch_password,
                item.password,
                item.password.length
            )
            holder.tvPassword.visibility = View.VISIBLE
        } else {
            holder.tvPassword.visibility = View.GONE
        }
    }
}

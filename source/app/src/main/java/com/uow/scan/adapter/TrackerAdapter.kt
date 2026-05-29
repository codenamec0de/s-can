package com.uow.scan.adapter

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.uow.scan.R
import com.uow.scan.model.TrackerInfo
import com.uow.scan.util.LocalTrackerScanner
import com.uow.scan.util.ScanDialog

class TrackerAdapter(
    private val trackers: List<TrackerInfo>
) : RecyclerView.Adapter<TrackerAdapter.TrackerViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tracker, parent, false)
        return TrackerViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackerViewHolder, position: Int) {
        holder.bind(trackers[position], isLast = position == trackers.size - 1)
    }

    override fun getItemCount(): Int = trackers.size

    inner class TrackerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val row: View = itemView.findViewById(R.id.trackerRow)
        private val tvName: TextView = itemView.findViewById(R.id.tvTrackerName)
        private val tvCategories: TextView = itemView.findViewById(R.id.tvTrackerCategories)
        private val tvDescription: TextView = itemView.findViewById(R.id.tvTrackerDescription)
        private val divider: View = itemView.findViewById(R.id.trackerRowDivider)

        fun bind(tracker: TrackerInfo, isLast: Boolean) {
            tvName.text = tracker.name

            val categories = tracker.categories?.filter { it.isNotBlank() }?.joinToString(" · ")
            if (categories.isNullOrBlank()) {
                tvCategories.visibility = View.GONE
            } else {
                tvCategories.visibility = View.VISIBLE
                tvCategories.text = categories
            }

            val cleaned = LocalTrackerScanner.cleanDescription(tracker.description)
            if (cleaned.isBlank()) {
                tvDescription.visibility = View.GONE
            } else {
                tvDescription.visibility = View.VISIBLE
                tvDescription.text = cleaned
            }

            divider.visibility = if (isLast) View.GONE else View.VISIBLE

            row.setOnClickListener { showDetail(tracker, cleaned) }
        }

        private fun showDetail(tracker: TrackerInfo, cleaned: String) {
            val ctx = itemView.context
            val parts = mutableListOf<String>()
            tracker.categories?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                ?.let { parts += it.joinToString(" · ") }
            if (cleaned.isNotBlank()) parts += cleaned
            val message = parts.joinToString("\n\n")
                .ifBlank { ctx.getString(R.string.tracker_detail_no_description) }

            val website = tracker.website?.takeIf { it.startsWith("http") }
            if (!website.isNullOrBlank()) {
                ScanDialog.confirm(
                    context = ctx,
                    title = tracker.name,
                    message = message,
                    confirmText = ctx.getString(R.string.tracker_detail_open_website),
                    cancelText = "Close",
                ) {
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(website)))
                    }
                }
            } else {
                ScanDialog.notice(ctx, tracker.name, message)
            }
        }
    }
}

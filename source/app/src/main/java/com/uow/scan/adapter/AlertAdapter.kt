package com.uow.scan.adapter

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.uow.scan.R
import com.uow.scan.model.PermissionAlert

class AlertAdapter(
    private val onAlertClick: (PermissionAlert) -> Unit
) : ListAdapter<PermissionAlert, AlertAdapter.AlertViewHolder>(AlertDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alert, parent, false)
        return AlertViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AlertViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAppIcon: ImageView = itemView.findViewById(R.id.ivAlertAppIcon)
        private val tvAlertTitle: TextView = itemView.findViewById(R.id.tvAlertTitle)
        private val tvAlertMessage: TextView = itemView.findViewById(R.id.tvAlertMessage)
        private val tvAlertTime: TextView = itemView.findViewById(R.id.tvAlertTime)
        private val tvAlertDataBadge: TextView = itemView.findViewById(R.id.tvAlertDataBadge)
        private val indicatorUnread: View = itemView.findViewById(R.id.indicatorUnread)

        fun bind(alert: PermissionAlert) {
            // App icon
            try {
                val pm = itemView.context.packageManager
                val icon = pm.getApplicationIcon(alert.packageName)
                ivAppIcon.setImageDrawable(icon)
            } catch (e: Exception) {
                ivAppIcon.setImageResource(R.drawable.ic_shield)
            }

            // Title: app name
            tvAlertTitle.text = alert.appName

            // Build descriptive message. The `permissions` list reflects sensors
            // actually observed in use (camera/mic) during the scan window —
            // not the static set of granted permissions. When empty, the alert
            // is purely about background data transfer with no sensor access
            // detected.
            val permList = alert.permissions.joinToString(", ")
            tvAlertMessage.text = when {
                alert.isSilentBackground && alert.permissions.isEmpty() ->
                    "${alert.appName} transferred ${alert.formattedDataUsed} of data silently in the background " +
                            "(likely push notifications or background sync). No sensor access was detected during this window."
                alert.isSilentBackground ->
                    "${alert.appName} transferred ${alert.formattedDataUsed} of data in the background and " +
                            "was observed using $permList during the same window."
                alert.permissions.isEmpty() ->
                    "${alert.appName} ran in the background for ${alert.formattedDuration} and used " +
                            "${alert.formattedDataUsed} of data. No sensor access was detected during this window."
                else ->
                    "${alert.appName} was observed using $permList while running in the background for " +
                            "${alert.formattedDuration}. During this time it used ${alert.formattedDataUsed} of data."
            }

            // Relative timestamp
            tvAlertTime.text = DateUtils.getRelativeTimeSpanString(
                alert.timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )

            // Data badge
            tvAlertDataBadge.text = alert.formattedDataUsed

            // Unread indicator
            indicatorUnread.visibility = if (alert.isRead) View.GONE else View.VISIBLE

            itemView.setOnClickListener { onAlertClick(alert) }
        }
    }

    class AlertDiffCallback : DiffUtil.ItemCallback<PermissionAlert>() {
        override fun areItemsTheSame(old: PermissionAlert, new: PermissionAlert) =
            old.id == new.id

        override fun areContentsTheSame(old: PermissionAlert, new: PermissionAlert) =
            old == new
    }
}

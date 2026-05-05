package com.uow.scan.adapter

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.uow.scan.R
import com.uow.scan.model.AppInfo
import com.uow.scan.model.PermissionGroup
import com.uow.scan.model.RiskLevel

/**
 * Category-grouped list of apps. Each group renders a V2 section header
 * (· UPPERCASE TITLE …… count) with all apps expanded below it using the
 * same row style as the flat list.
 */
class PermissionGroupAdapter(
    private val groups: List<PermissionGroup>,
    private val onAppClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<PermissionGroupAdapter.GroupViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_permission_group, parent, false)
        return GroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(groups[position])
    }

    override fun getItemCount(): Int = groups.size

    inner class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvGroupName: TextView = itemView.findViewById(R.id.tvGroupName)
        private val tvAppCount: TextView = itemView.findViewById(R.id.tvAppCount)
        private val appListContainer: LinearLayout = itemView.findViewById(R.id.appListContainer)

        fun bind(group: PermissionGroup) {
            tvGroupName.text = group.displayName
            tvAppCount.text = group.appCount.toString()

            appListContainer.removeAllViews()
            val inflater = LayoutInflater.from(itemView.context)
            for (groupApp in group.apps) {
                val row = inflater.inflate(R.layout.item_app, appListContainer, false)
                bindAppRow(row, groupApp.appInfo)
                row.setOnClickListener { onAppClick(groupApp.appInfo) }
                appListContainer.addView(row)
            }
        }

        private fun bindAppRow(row: View, app: AppInfo) {
            val ctx = row.context
            val ivIcon: ImageView = row.findViewById(R.id.ivAppIcon)
            val tvName: TextView = row.findViewById(R.id.tvAppName)
            val tvCat: TextView = row.findViewById(R.id.tvAppCategory)
            val tvPermCount: TextView = row.findViewById(R.id.tvPermissionCount)
            val tvPermLabel: TextView = row.findViewById(R.id.tvPermissionLabel)
            val permDots: LinearLayout = row.findViewById(R.id.permDots)
            val tvScore: TextView = row.findViewById(R.id.tvRiskBadge)
            val riskDot: View = row.findViewById(R.id.riskDot)

            app.icon?.let { ivIcon.setImageDrawable(it) }
                ?: ivIcon.setImageResource(R.drawable.ic_shield)

            tvName.text = app.appName

            val category = AppListAdapter.categoryFor(app.packageName)
            if (category != null) {
                tvCat.visibility = View.VISIBLE
                tvCat.text = category
            } else {
                tvCat.visibility = View.GONE
            }

            val permCount = if (app.sensitivePermissionCount > 0)
                app.sensitivePermissionCount else app.permissionCount
            tvPermCount.text = permCount.toString()
            tvPermLabel.text = if (permCount == 1) " permission" else " permissions"

            renderPermDots(permDots, app)

            val riskColorRes = when (app.riskLevel) {
                RiskLevel.HIGH -> R.color.scan_bad
                RiskLevel.MEDIUM -> R.color.scan_warn
                RiskLevel.LOW -> R.color.scan_ok
            }
            val riskColor = ContextCompat.getColor(ctx, riskColorRes)
            val score = when (app.riskLevel) {
                RiskLevel.HIGH -> (45 - app.sensitivePermissionCount * 3).coerceIn(10, 100)
                RiskLevel.MEDIUM -> (70 - app.sensitivePermissionCount * 2).coerceIn(10, 100)
                RiskLevel.LOW -> (92 - app.sensitivePermissionCount).coerceIn(10, 100)
            }
            tvScore.text = score.toString()
            tvScore.setTextColor(riskColor)

            riskDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(riskColor)
            }
            riskDot.alpha = when (app.riskLevel) {
                RiskLevel.HIGH, RiskLevel.MEDIUM -> 1f
                RiskLevel.LOW -> 0.5f
            }
        }

        private fun renderPermDots(container: LinearLayout, app: AppInfo) {
            container.removeAllViews()
            val total = 8
            val filled = app.sensitivePermissionCount.coerceAtMost(total)
            val ctx = container.context
            val dotSize = (4 * ctx.resources.displayMetrics.density).toInt()
            val dotGap = (3 * ctx.resources.displayMetrics.density).toInt()

            val activeColor = when (app.riskLevel) {
                RiskLevel.HIGH -> ContextCompat.getColor(ctx, R.color.scan_bad)
                RiskLevel.MEDIUM -> ContextCompat.getColor(ctx, R.color.scan_warn)
                RiskLevel.LOW -> ContextCompat.getColor(ctx, R.color.fg_2)
            }
            val idleColor = ContextCompat.getColor(ctx, R.color.ink_4)

            for (i in 0 until total) {
                val dot = View(ctx)
                val lp = LinearLayout.LayoutParams(dotSize, dotSize)
                if (i > 0) lp.marginStart = dotGap
                dot.layoutParams = lp
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (i < filled) activeColor else idleColor)
                }
                dot.background = bg
                container.addView(dot)
            }
        }
    }
}

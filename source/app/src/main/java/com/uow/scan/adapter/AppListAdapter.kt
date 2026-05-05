package com.uow.scan.adapter

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.uow.scan.R
import com.uow.scan.model.AppInfo
import com.uow.scan.model.RiskLevel

class AppListAdapter(
    private val onAppClick: (AppInfo) -> Unit
) : ListAdapter<AppInfo, AppListAdapter.AppViewHolder>(AppDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAppIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        private val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvAppCategory: TextView = itemView.findViewById(R.id.tvAppCategory)
        private val tvPermissionCount: TextView = itemView.findViewById(R.id.tvPermissionCount)
        private val tvPermissionLabel: TextView = itemView.findViewById(R.id.tvPermissionLabel)
        private val permDots: LinearLayout = itemView.findViewById(R.id.permDots)
        private val tvScore: TextView = itemView.findViewById(R.id.tvRiskBadge)
        private val riskDot: View = itemView.findViewById(R.id.riskDot)

        fun bind(app: AppInfo) {
            val ctx = itemView.context

            app.icon?.let { ivAppIcon.setImageDrawable(it) }
                ?: ivAppIcon.setImageResource(R.drawable.ic_shield)

            tvAppName.text = app.appName

            val category = categoryFor(app.packageName)
            if (category != null) {
                tvAppCategory.visibility = View.VISIBLE
                tvAppCategory.text = category
            } else {
                tvAppCategory.visibility = View.GONE
            }

            val permCount = if (app.sensitivePermissionCount > 0)
                app.sensitivePermissionCount else app.permissionCount
            tvPermissionCount.text = permCount.toString()
            tvPermissionLabel.text = if (permCount == 1) " permission" else " permissions"

            renderPermDots(app)

            val score = computeScore(app)
            tvScore.text = score.toString()
            val riskColorRes = when (app.riskLevel) {
                RiskLevel.HIGH -> R.color.scan_bad
                RiskLevel.MEDIUM -> R.color.scan_warn
                RiskLevel.LOW -> R.color.scan_ok
            }
            val riskColor = ContextCompat.getColor(ctx, riskColorRes)
            tvScore.setTextColor(riskColor)

            // Risk dot (top-right): visible for high (glow) + med, dim for low
            val dotBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(riskColor)
            }
            riskDot.background = dotBg
            when (app.riskLevel) {
                RiskLevel.HIGH -> { riskDot.alpha = 1f; riskDot.elevation = 4f }
                RiskLevel.MEDIUM -> { riskDot.alpha = 1f; riskDot.elevation = 0f }
                RiskLevel.LOW -> { riskDot.alpha = 0.5f; riskDot.elevation = 0f }
            }

            itemView.setOnClickListener { onAppClick(app) }
        }

        private fun renderPermDots(app: AppInfo) {
            permDots.removeAllViews()
            val total = 8
            val filled = app.sensitivePermissionCount.coerceAtMost(total)
            val ctx = itemView.context
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
                permDots.addView(dot)
            }
        }

        /**
         * Lightweight per-app score that mirrors the mockup: higher risk →
         * lower number. Not a real security metric; it's a visual summary.
         */
        private fun computeScore(app: AppInfo): Int {
            val base = when (app.riskLevel) {
                RiskLevel.HIGH -> 45
                RiskLevel.MEDIUM -> 70
                RiskLevel.LOW -> 92
            }
            val penalty = app.sensitivePermissionCount * when (app.riskLevel) {
                RiskLevel.HIGH -> 3
                RiskLevel.MEDIUM -> 2
                RiskLevel.LOW -> 1
            }
            return (base - penalty).coerceIn(10, 100)
        }
    }

    class AppDiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
            oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
            oldItem == newItem
    }

    companion object {
        /**
         * Coarse category heuristic based on package keywords. Returns null
         * when we don't recognise the package - the row just omits the tag.
         */
        fun categoryFor(pkg: String): String? {
            val p = pkg.lowercase()
            return when {
                listOf("facebook", "instagram", "tiktok", "snapchat", "twitter",
                    "x.android", "pinterest", "reddit", "linkedin", "whatsapp",
                    "messenger", "telegram", "discord").any { it in p } -> "Social"
                listOf("maps", "waze", "navigation", "uber", "lyft").any { it in p } -> "Navigation"
                listOf("spotify", "youtube", "netflix", "music", "apple.music",
                    "soundcloud", "pandora", "hulu", "disney").any { it in p } -> "Media"
                listOf("chrome", "firefox", "opera", "edge", "browser", "safari",
                    "duckduckgo").any { it in p } -> "Browser"
                listOf("gmail", "outlook", "mail", "slack", "teams", "zoom",
                    "notes", "drive", "docs", "office", "evernote", "todoist",
                    "asana", "notion").any { it in p } -> "Productivity"
                listOf("camera", "photo", "gallery").any { it in p } -> "Photos"
                listOf("bank", "paypal", "venmo", "revolut", "wise", "cashapp",
                    "wallet", "finance").any { it in p } -> "Finance"
                listOf("strava", "health", "fitness", "workout", "run").any { it in p } -> "Health"
                listOf("weather", "calculator", "clock", "calendar").any { it in p } -> "Utilities"
                listOf("phone", "contacts", "sms", "dialer", "messaging").any { it in p } -> "Communication"
                listOf("system", "launcher", "provider", "settings").any { it in p } -> null
                else -> null
            }
        }
    }
}

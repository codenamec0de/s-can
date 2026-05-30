package com.uow.scan.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.uow.scan.R
import com.uow.scan.ui.home.widget.SignalBarsView
import com.uow.scan.util.WifiNetwork
import com.uow.scan.util.WifiSecurityAnalyzer
import com.uow.scan.util.WifiSecurityAnalyzer.Grade
import com.uow.scan.util.WifiSecurityAnalyzer.SecRisk

/** Nearby-networks list for the Wi-Fi Security overview. Evil twins get a red ring + tag. */
class WifiNetworkAdapter(
    private val onClick: (WifiNetwork) -> Unit
) : ListAdapter<WifiNetwork, WifiNetworkAdapter.NetworkViewHolder>(NetworkDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_wifi_network_row, parent, false)
        return NetworkViewHolder(view)
    }

    override fun onBindViewHolder(holder: NetworkViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NetworkViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val rowRoot: View = itemView.findViewById(R.id.rowRoot)
        private val signalBars: SignalBarsView = itemView.findViewById(R.id.signalBars)
        private val tvSsid: TextView = itemView.findViewById(R.id.tvRowSsid)
        private val tvDuplicate: TextView = itemView.findViewById(R.id.tvRowDuplicate)
        private val tvSecChip: TextView = itemView.findViewById(R.id.tvRowSecChip)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvRowMeta)
        private val tvScore: TextView = itemView.findViewById(R.id.tvRowScore)

        fun bind(net: WifiNetwork) {
            val ctx = itemView.context
            val gradeColor = ContextCompat.getColor(ctx, gradeColorRes(net.grade))
            val neutralFg = ContextCompat.getColor(ctx, R.color.v4_fg2)

            // Full grade/risk colour is reserved for the two cases that actually concern
            // the user: their own connected network and an evil twin (an AP impersonating
            // a network to lure a connection). Every other nearby AP is one the user isn't
            // connected to, so its posture is shown neutrally — a list of red OPEN/WEP and
            // red scores for networks you're not on only causes needless worry.
            val highlight = net.connected || net.evilTwin

            // Evil-twin ring vs normal surface
            rowRoot.setBackgroundResource(
                if (net.evilTwin) R.drawable.bg_v4_wifi_row_evil else R.drawable.bg_v4_surface
            )

            // Signal bars: grade-coloured only when highlighted, else neutral.
            val barColor = if (highlight) gradeColor else ContextCompat.getColor(ctx, R.color.v4_fg1)
            val dim = ContextCompat.getColor(ctx, R.color.v4_hairline2)
            signalBars.set(signalBucket(net.signalQuality), barColor, dim)

            tvSsid.text = net.ssid
            tvDuplicate.visibility = if (net.evilTwin) View.VISIBLE else View.GONE

            // Security chip — the factual label (OPEN / WEP / WPA2 …) is always shown, but
            // it's only risk-coloured when highlighted; otherwise it sits in a neutral pill.
            tvSecChip.text = WifiSecurityAnalyzer.authShortLabel(net.authType)
            if (highlight) {
                val (chipBg, chipColor) = when (WifiSecurityAnalyzer.securityRisk(net.authType)) {
                    SecRisk.BAD -> R.drawable.bg_v4_perm_pill_bad to R.color.v4_bad
                    SecRisk.WARN -> R.drawable.bg_v4_perm_pill_idle to R.color.v4_fg2
                    SecRisk.OK -> R.drawable.bg_v4_perm_pill_ok to R.color.v4_ok
                }
                tvSecChip.setBackgroundResource(chipBg)
                tvSecChip.setTextColor(ContextCompat.getColor(ctx, chipColor))
            } else {
                tvSecChip.setBackgroundResource(R.drawable.bg_v4_perm_pill_idle)
                tvSecChip.setTextColor(neutralFg)
            }

            tvMeta.text = ctx.getString(
                R.string.wifi_v4_meta_format,
                WifiSecurityAnalyzer.bandShort(net.bandMhz),
                net.channel?.toString() ?: "—",
                net.rssiDbm
            )

            // Score chip — grade-coloured when highlighted, neutral otherwise. The number is
            // always shown, so "sort by Risk" still works and the detail screen reveals the
            // full grade colour on tap.
            tvScore.text = net.score.toString()
            if (highlight) {
                tvScore.setTextColor(gradeColor)
                tvScore.setBackgroundResource(gradePillBg(net.grade))
            } else {
                tvScore.setTextColor(neutralFg)
                tvScore.setBackgroundResource(R.drawable.bg_v4_perm_pill_idle)
            }

            itemView.setOnClickListener { onClick(net) }
        }
    }

    private fun signalBucket(quality: Int): Int = when {
        quality >= 75 -> 4
        quality >= 50 -> 3
        quality >= 28 -> 2
        else -> 1
    }

    private fun gradeColorRes(grade: Grade): Int = when (grade) {
        Grade.EXCELLENT, Grade.GOOD -> R.color.v4_ok
        Grade.FAIR -> R.color.v4_accent
        Grade.POOR -> R.color.v4_warn
        Grade.CRITICAL -> R.color.v4_bad
    }

    private fun gradePillBg(grade: Grade): Int = when (grade) {
        Grade.EXCELLENT, Grade.GOOD -> R.drawable.bg_v4_perm_pill_ok
        Grade.FAIR -> R.drawable.bg_v4_perm_pill_accent
        Grade.POOR -> R.drawable.bg_v4_perm_pill_warn
        Grade.CRITICAL -> R.drawable.bg_v4_perm_pill_bad
    }

    class NetworkDiffCallback : DiffUtil.ItemCallback<WifiNetwork>() {
        override fun areItemsTheSame(old: WifiNetwork, new: WifiNetwork) = old.bssid == new.bssid
        override fun areContentsTheSame(old: WifiNetwork, new: WifiNetwork) = old == new
    }
}

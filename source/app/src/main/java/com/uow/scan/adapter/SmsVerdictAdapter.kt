package com.uow.scan.adapter

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.uow.scan.R
import com.uow.scan.model.SmsVerdict

class SmsVerdictAdapter(
    private val onClick: (SmsVerdict) -> Unit
) : ListAdapter<SmsVerdict, SmsVerdictAdapter.VerdictViewHolder>(VerdictDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VerdictViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sms_verdict, parent, false)
        return VerdictViewHolder(view)
    }

    override fun onBindViewHolder(holder: VerdictViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VerdictViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSender: TextView = itemView.findViewById(R.id.tvSmsSender)
        private val tvPreview: TextView = itemView.findViewById(R.id.tvSmsPreview)
        private val tvTime: TextView = itemView.findViewById(R.id.tvSmsTime)
        private val tvVerdictBadge: TextView = itemView.findViewById(R.id.tvVerdictBadge)
        private val indicatorUnread: View = itemView.findViewById(R.id.indicatorUnread)
        private val tvTagSuffix: TextView = itemView.findViewById(R.id.tvTagSuffix)

        fun bind(verdict: SmsVerdict) {
            val ctx = itemView.context
            tvSender.text = verdict.sender
            tvPreview.text = verdict.messageBody

            val (colorRes, dotRes, pillBg, pillLabelRes) = when (verdict.verdict.uppercase()) {
                "SCAM" -> Quad(R.color.v4_bad, R.drawable.bg_v4_sev_dot_bad,
                    R.drawable.bg_v4_perm_pill_bad, R.string.sms_v4_label_scam)
                "SUSPICIOUS" -> Quad(R.color.v4_warn, R.drawable.bg_v4_sev_dot_warn,
                    R.drawable.bg_v4_perm_pill_warn, R.string.sms_v4_label_suspicious)
                else -> Quad(R.color.v4_ok, R.drawable.bg_v4_sev_dot_ok,
                    R.drawable.bg_v4_perm_pill_ok, R.string.sms_v4_label_safe)
            }
            val color = ContextCompat.getColor(ctx, colorRes)

            indicatorUnread.setBackgroundResource(dotRes)
            tvVerdictBadge.setText(pillLabelRes)
            tvVerdictBadge.setBackgroundResource(pillBg)
            tvVerdictBadge.setTextColor(color)

            tvTime.text = DateUtils.getRelativeTimeSpanString(
                verdict.timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )

            // Pull a single short tag from the explanation if present (first sentence,
            // capped). Hidden when the verdict is safe.
            val tag = verdict.explanation
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.take(48)
            if (!tag.isNullOrBlank() && verdict.verdict.uppercase() != "SAFE") {
                tvTagSuffix.visibility = View.VISIBLE
                tvTagSuffix.text = "· $tag"
                tvTagSuffix.setTextColor(color)
            } else {
                tvTagSuffix.visibility = View.GONE
            }

            itemView.setOnClickListener { onClick(verdict) }
        }
    }

    private data class Quad(val color: Int, val dot: Int, val pillBg: Int, val pillLabel: Int)

    class VerdictDiffCallback : DiffUtil.ItemCallback<SmsVerdict>() {
        override fun areItemsTheSame(old: SmsVerdict, new: SmsVerdict) = old.id == new.id
        override fun areContentsTheSame(old: SmsVerdict, new: SmsVerdict) = old == new
    }
}

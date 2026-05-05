package com.uow.scan.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.uow.scan.R
import com.uow.scan.util.PermissionHelper

class PermissionAdapter(
    private val permissions: List<String>
) : RecyclerView.Adapter<PermissionAdapter.PermissionViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PermissionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_v4_perm_row, parent, false)
        return PermissionViewHolder(view)
    }

    override fun onBindViewHolder(holder: PermissionViewHolder, position: Int) {
        holder.bind(permissions[position], position == permissions.size - 1)
    }

    override fun getItemCount(): Int = permissions.size

    inner class PermissionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivPermIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvPermName)
        private val tvStatePill: TextView = itemView.findViewById(R.id.tvPermStatePill)
        private val tvStateLabel: TextView = itemView.findViewById(R.id.tvPermStateLabel)
        private val divider: View = itemView.findViewById(R.id.permRowDivider)

        fun bind(permission: String, isLast: Boolean) {
            val ctx = itemView.context
            tvName.text = PermissionHelper.getPermissionName(permission)
            ivIcon.setImageResource(iconResFor(permission))

            val isBackground = permission.contains("BACKGROUND")
            if (isBackground) {
                tvStatePill.visibility = View.VISIBLE
                tvStatePill.text = ctx.getString(R.string.app_detail_perm_state_used_in_bg)
                tvStatePill.setBackgroundResource(R.drawable.bg_v4_perm_pill_bad)
                tvStatePill.setTextColor(ctx.getColor(R.color.v4_bad))
                tvStateLabel.visibility = View.GONE
                ivIcon.setColorFilter(ctx.getColor(R.color.v4_bad))
            } else {
                tvStatePill.visibility = View.GONE
                tvStateLabel.visibility = View.VISIBLE
                tvStateLabel.text = ctx.getString(R.string.app_detail_perm_state_granted)
                ivIcon.setColorFilter(ctx.getColor(R.color.v4_fg2))
            }

            divider.visibility = if (isLast) View.GONE else View.VISIBLE
        }

        private fun iconResFor(permission: String): Int = when {
            permission.contains("CAMERA") -> R.drawable.ic_v4_glyph_camera
            permission.contains("RECORD_AUDIO") -> R.drawable.ic_v4_glyph_mic
            permission.contains("LOCATION") -> R.drawable.ic_v4_glyph_pin
            permission.contains("CONTACTS") -> R.drawable.ic_v4_glyph_contacts
            permission.contains("SMS") -> R.drawable.ic_glyph_sms
            permission.contains("CALL") || permission.contains("PHONE") -> R.drawable.ic_v4_glyph_phone
            permission.contains("CALENDAR") -> R.drawable.ic_v4_glyph_calendar
            permission.contains("STORAGE") || permission.contains("MEDIA") -> R.drawable.ic_v4_glyph_storage
            permission.contains("SENSORS") || permission.contains("ACTIVITY_RECOGNITION") -> R.drawable.ic_v4_glyph_body
            permission.contains("BIOMETRIC") || permission.contains("FINGERPRINT") -> R.drawable.ic_glyph_lock
            else -> R.drawable.ic_glyph_shield
        }
    }
}

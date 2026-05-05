package com.uow.scan

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PrivacyPolicyActivity : AppCompatActivity() {

    private data class Commitment(val titleRes: Int, val descRes: Int, val iconRes: Int, val colorRes: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy_policy)

        findViewById<TextView>(R.id.tvTopBarTitle).setText(R.string.privacy_v4_title)
        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        renderMeta()
        renderCommitments()
        renderSections()

        findViewById<FrameLayout>(R.id.btnReadFull).setOnClickListener { showFullPolicyDialog() }
    }

    private fun renderMeta() {
        val updated = runCatching {
            val ts = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(ts))
        }.getOrDefault("—")
        findViewById<TextView>(R.id.tvPrivacyMeta).text =
            getString(R.string.privacy_v4_meta_format, updated, BuildConfig.VERSION_NAME)
    }

    private fun renderCommitments() {
        val container = findViewById<LinearLayout>(R.id.commitmentsContainer)
        val commits = listOf(
            Commitment(R.string.privacy_v4_commit_ondevice_t, R.string.privacy_v4_commit_ondevice_d,
                R.drawable.ic_glyph_check, R.color.v4_ok),
            Commitment(R.string.privacy_v4_commit_telemetry_t, R.string.privacy_v4_commit_telemetry_d,
                R.drawable.ic_glyph_check, R.color.v4_ok),
            Commitment(R.string.privacy_v4_commit_sdks_t, R.string.privacy_v4_commit_sdks_d,
                R.drawable.ic_glyph_check, R.color.v4_ok),
            Commitment(R.string.privacy_v4_commit_ai_t, R.string.privacy_v4_commit_ai_d,
                R.drawable.ic_glyph_warn, R.color.v4_warn),
        )
        container.removeAllViews()
        for ((index, c) in commits.withIndex()) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_v4_privacy_commitment, container, false)
            val tile = v.findViewById<FrameLayout>(R.id.commitIconTile)
            val icon = v.findViewById<ImageView>(R.id.commitIcon)
            val title = v.findViewById<TextView>(R.id.commitTitle)
            val desc = v.findViewById<TextView>(R.id.commitDesc)
            val divider = v.findViewById<View>(R.id.commitDivider)

            val color = ContextCompat.getColor(this, c.colorRes)
            val density = resources.displayMetrics.density
            // 10% bg + 20% border, mirrored from the design's `${c}1A` / `${c}33` calls.
            tile.background = GradientDrawable().apply {
                cornerRadius = 9 * density
                setColor(blend(color, 0x1A))
                setStroke((1 * density).toInt(), blend(color, 0x33))
            }
            icon.setImageResource(c.iconRes)
            icon.setColorFilter(color)
            title.setText(c.titleRes)
            desc.setText(c.descRes)
            divider.visibility = if (index == commits.size - 1) View.GONE else View.VISIBLE
            container.addView(v)
        }
    }

    private fun blend(color: Int, alpha: Int): Int {
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    private fun renderSections() {
        val container = findViewById<LinearLayout>(R.id.sectionsContainer)
        val sections = listOf(
            R.string.privacy_v4_section_1, R.string.privacy_v4_section_2,
            R.string.privacy_v4_section_3, R.string.privacy_v4_section_4,
            R.string.privacy_v4_section_5, R.string.privacy_v4_section_6,
            R.string.privacy_v4_section_7,
        )
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for ((index, sectionRes) in sections.withIndex()) {
            val row = inflater.inflate(R.layout.item_v4_privacy_section_row, container, false)
            val label = row.findViewById<TextView>(R.id.sectionLabel)
            val divider = row.findViewById<View>(R.id.sectionDivider)
            label.setText(sectionRes)
            divider.visibility = if (index == sections.size - 1) View.GONE else View.VISIBLE
            row.setOnClickListener { showSectionDialog(sectionRes) }
            container.addView(row)
        }
    }

    private fun showSectionDialog(titleRes: Int) {
        val body = when (titleRes) {
            R.string.privacy_v4_section_1 ->
                "S'CAN reads installed app metadata, granted permissions, app data usage counters, " +
                "device security configuration (root/lock-screen/encryption), and Wi-Fi network state. " +
                "All of this is sourced from public Android APIs and stays on your device."
            R.string.privacy_v4_section_2 ->
                "We use granted permissions to evaluate risk — never to act on your behalf. We never " +
                "read your messages, contacts, or media unless a feature you opted into requires it " +
                "(only SMS Scam Detection ever reads SMS, and only forwards to the server you choose)."
            R.string.privacy_v4_section_3 ->
                "If you enable SMS Scam Detection, message text is sent to the URL you configure, " +
                "with the bearer token you provide. The connection uses TLS with a pinned cert hash " +
                "(SCAN_AI_CERT_PIN). We never see, log, or proxy any SMS content."
            R.string.privacy_v4_section_4 ->
                "Scan history defaults to 90-day retention; SMS verdicts to 30 days. Caches auto-purge " +
                "weekly. You can wipe everything from Settings → Data & Storage. Logout clears all " +
                "preferences and signs out of Firebase."
            R.string.privacy_v4_section_5 ->
                "Auth tokens go through Firebase. Local DB uses Room on encrypted device storage when " +
                "the OS supports it. The AI sidecar pin rotates with each release — see the runbook " +
                "in scan_vault."
            R.string.privacy_v4_section_6 ->
                "Under GDPR / Australian Privacy Principles, you may request access, correction, or " +
                "deletion of any personal data we hold. Email privacy@scan.app — we respond within 30 days."
            R.string.privacy_v4_section_7 ->
                "Security disclosures: security@scan.app (PGP key on the website). General privacy " +
                "questions: privacy@scan.app."
            else -> ""
        }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showFullPolicyDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.privacy_v4_btn_full)
            .setMessage(
                "The full privacy policy lives at https://scan.app/privacy. The summary above " +
                "and the per-section detail pages reflect the same commitments."
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}

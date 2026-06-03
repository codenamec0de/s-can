package com.uow.scan

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.uow.scan.util.NtmDemoData
import com.uow.scan.util.NtmDemoData.Dest
import com.uow.scan.util.NtmDemoData.NtmApp
import com.uow.scan.util.NtmBlocklist
import com.uow.scan.util.NtmLiveRepository
import com.uow.scan.util.PreferencesManager
import com.uow.scan.util.ScanDialog

/**
 * Network Traffic Monitor — App Network Detail (S'CAN V4 · Screen B). Mirrors the Wi-Fi
 * network-detail structure: a header with data splits, a tracker-company summary, a
 * per-destination list (org / ASN / geo / protocol / bytes + a block control), a cleartext-HTTP
 * callout, a "block all trackers" action, and Advanced-capture gating (exact hostnames are
 * hidden behind owners until Advanced capture is on).
 *
 * Design-first build over [NtmDemoData]; block state is in-memory for the session.
 */
class NetworkAppDetailActivity : AppCompatActivity() {

    private lateinit var app: NtmApp
    private var advanced = true
    private var liveMode = false
    private val blocked = mutableSetOf<String>()

    private lateinit var btnBack: View
    private lateinit var btnAdv: View
    private lateinit var advIcon: ImageView
    private lateinit var advText: TextView

    private lateinit var appTile: FrameLayout
    private lateinit var appMono: TextView
    private lateinit var appIcon: ImageView
    private lateinit var tvAppName: TextView
    private lateinit var tvAppPkg: TextView
    private lateinit var tvDetailTotal: TextView
    private lateinit var tvDetailFg: TextView
    private lateinit var tvDetailBg: TextView
    private lateinit var tvDataLine: TextView

    private lateinit var cleartextCallout: View
    private lateinit var tvCleartextTitle: TextView

    private lateinit var tvTrackerSummary: TextView
    private lateinit var trackerScroll: View
    private lateinit var trackerChips: LinearLayout
    private lateinit var noTrackersCard: View

    private lateinit var tvDestHeader: TextView
    private lateinit var advHint: View
    private lateinit var advHintEnable: TextView
    private lateinit var destsContainer: LinearLayout
    private lateinit var blockAllWrap: View
    private lateinit var btnBlockAll: com.google.android.material.button.MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ntm_app_detail)
        val appId = intent.getStringExtra(EXTRA_APP_ID)
        liveMode = PreferencesManager.isNetMonActive(this) && !PreferencesManager.isNetMonDemoMode(this)
        // Live mode resolves the real app (or a real empty placeholder) — never falls back to demo.
        app = if (liveMode) NtmLiveRepository(this).appById(appId) else NtmDemoData.appById(appId)
        advanced = intent.getBooleanExtra(EXTRA_ADVANCED, true)
        bindViews()
        setupListeners()
        renderAll()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        btnAdv = findViewById(R.id.btnAdv)
        advIcon = findViewById(R.id.advIcon)
        advText = findViewById(R.id.advText)
        appTile = findViewById(R.id.appTile)
        appMono = findViewById(R.id.appMono)
        appIcon = findViewById(R.id.appIcon)
        tvAppName = findViewById(R.id.tvAppName)
        tvAppPkg = findViewById(R.id.tvAppPkg)
        tvDetailTotal = findViewById(R.id.tvDetailTotal)
        tvDetailFg = findViewById(R.id.tvDetailFg)
        tvDetailBg = findViewById(R.id.tvDetailBg)
        tvDataLine = findViewById(R.id.tvDataLine)
        cleartextCallout = findViewById(R.id.cleartextCallout)
        tvCleartextTitle = findViewById(R.id.tvCleartextTitle)
        tvTrackerSummary = findViewById(R.id.tvTrackerSummary)
        trackerScroll = findViewById(R.id.trackerScroll)
        trackerChips = findViewById(R.id.trackerChips)
        noTrackersCard = findViewById(R.id.noTrackersCard)
        tvDestHeader = findViewById(R.id.tvDestHeader)
        advHint = findViewById(R.id.advHint)
        advHintEnable = findViewById(R.id.advHintEnable)
        destsContainer = findViewById(R.id.destsContainer)
        blockAllWrap = findViewById(R.id.blockAllWrap)
        btnBlockAll = findViewById(R.id.btnBlockAll)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish(); slideBack() }
        btnAdv.setOnClickListener { advanced = !advanced; renderAdvToggle(); renderDestinations() }
        advHintEnable.setOnClickListener { advanced = true; renderAdvToggle(); renderDestinations() }
        btnBlockAll.setOnClickListener {
            app.dests.filter { it.trk != null }.forEach { setHostBlocked(it.host, true) }
            Toast.makeText(
                this,
                getString(R.string.ntm_blocked_toast_fmt, NtmDemoData.appStats(app).trackerDests.size, app.name),
                Toast.LENGTH_SHORT,
            ).show()
            renderDestinations()
        }
    }

    private fun renderAll() {
        renderHeader()
        renderCleartext()
        renderTrackerSummary()
        renderAdvToggle()
        renderDestinations()
        renderBlockAll()
    }

    private fun renderHeader() {
        val brand = parseColor(app.brand, color(R.color.v4_accent))
        val icon = appIconFor(app.pkg)
        if (icon != null) {
            appIcon.setImageDrawable(icon)
            appIcon.visibility = View.VISIBLE
            appMono.visibility = View.GONE
            appTile.background = pillBg(color(R.color.v4_surf2), color(R.color.v4_hairline2), 14)
        } else {
            appIcon.visibility = View.GONE
            appMono.visibility = View.VISIBLE
            appMono.text = app.mono
            appMono.setTextColor(brand)
            appTile.background = pillBg(withAlpha(brand, 0x22), withAlpha(brand, 0x44), 14)
        }
        tvAppName.text = app.name
        tvAppPkg.text = app.pkg
        tvDetailTotal.text = NtmDemoData.fmtBytes(app.fg + app.bg)
        tvDetailFg.text = NtmDemoData.fmtBytes(app.fg)
        tvDetailBg.text = NtmDemoData.fmtBytes(app.bg)
        tvDetailBg.setTextColor(color(if (app.bg >= app.fg) R.color.v4_warn else R.color.v4_fg1))
        tvDataLine.text = getString(
            R.string.ntm_detail_data_line_fmt,
            NtmDemoData.fmtBytes(app.wifi), NtmDemoData.fmtBytes(app.mobile),
        )
    }

    private fun renderCleartext() {
        val count = app.dests.count { !it.enc }
        if (count > 0) {
            cleartextCallout.visibility = View.VISIBLE
            tvCleartextTitle.text = getString(R.string.ntm_cleartext_title_fmt, count)
        } else {
            cleartextCallout.visibility = View.GONE
        }
    }

    private fun renderTrackerSummary() {
        val companies = NtmDemoData.appStats(app).trackerCompanies
        tvTrackerSummary.text =
            if (companies.size == 1) getString(R.string.ntm_tracker_summary_one)
            else getString(R.string.ntm_tracker_summary_fmt, companies.size)

        trackerChips.removeAllViews()
        if (companies.isEmpty()) {
            trackerScroll.visibility = View.GONE
            noTrackersCard.visibility = View.VISIBLE
            return
        }
        trackerScroll.visibility = View.VISIBLE
        noTrackersCard.visibility = View.GONE
        companies.forEachIndexed { i, key ->
            val trk = NtmDemoData.tracker(key) ?: return@forEachIndexed
            trackerChips.addView(buildTrackerChip(key, trk.name, trk.cat), chipParams(i == 0))
        }
    }

    private fun buildTrackerChip(key: String, name: String, cat: String): View {
        val cc = catColor(cat)
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg(color(R.color.v4_surf3), withAlpha(cc, 0x44), 8)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            isClickable = true
            setOnClickListener { showTracker(key) }
        }
        val dot = View(this).apply {
            setBackgroundResource(R.drawable.bg_v4_dns_dot)
            backgroundTintList = ColorStateList.valueOf(cc)
        }
        chip.addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)))
        val nameTv = TextView(this).apply {
            text = name
            setTextColor(color(R.color.v4_fg1))
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.NORMAL)
        }
        chip.addView(nameTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(5) })
        val catTv = TextView(this).apply {
            text = cat
            setTextColor(color(R.color.v4_fg3))
            textSize = 9f
        }
        chip.addView(catTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(5) })
        return chip
    }

    private fun chipParams(first: Boolean) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { if (!first) marginStart = dp(7) }

    private fun renderAdvToggle() {
        if (advanced) {
            btnAdv.background = pillBg(color(R.color.v4_accent_bg), color(R.color.v4_accent_ring), 9)
            advIcon.imageTintList = colorState(R.color.v4_accent)
            advText.setTextColor(color(R.color.v4_accent))
        } else {
            btnAdv.background = pillBg(color(R.color.v4_surf2), color(R.color.v4_hairline), 9)
            advIcon.imageTintList = colorState(R.color.v4_fg3)
            advText.setTextColor(color(R.color.v4_fg3))
        }
    }

    private fun renderDestinations() {
        val dests = app.dests.sortedByDescending { it.bytesKb }
        tvDestHeader.text = getString(R.string.ntm_destinations_fmt, dests.size)
        advHint.visibility = if (advanced) View.GONE else View.VISIBLE

        destsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (d in dests) {
            val row = inflater.inflate(R.layout.item_ntm_dest, destsContainer, false)
            bindDest(row, d)
            destsContainer.addView(row)
        }
    }

    private fun bindDest(row: View, d: Dest) {
        val isBlocked = if (liveMode) isBlockedNow(d.host)
        else d.preBlocked || blocked.contains(d.host)

        row.background =
            if (!d.enc) pillBg(color(R.color.v4_surf2), withAlpha(color(R.color.v4_bad), 0x44), 16)
            else ContextCompat.getDrawable(this, R.drawable.bg_v4_surface)

        // On the DNS-only tunnel we know the host but not the protocol/encryption/ASN/geo/port —
        // those arrive with Stage-4 capture. proto == "" is the DNS-only sentinel → render lean.
        val dnsOnly = d.proto.isBlank()

        val hostOrg = row.findViewById<TextView>(R.id.destHostOrg)
        val showHost = advanced || d.org.isBlank()
        hostOrg.text = if (showHost) d.host else d.org
        hostOrg.setTextColor(color(if (showHost) R.color.v4_fg0 else R.color.v4_fg2))

        val protoBadge = row.findViewById<LinearLayout>(R.id.destProtoBadge)
        if (dnsOnly) {
            protoBadge.visibility = View.GONE
        } else {
            protoBadge.visibility = View.VISIBLE
            val protoColor = if (d.enc) R.color.v4_ok else R.color.v4_bad
            protoBadge.background = pillBg(
                color(if (d.enc) R.color.v4_ok_bg else R.color.v4_bad_bg),
                withAlpha(color(protoColor), 0x44), 4,
            )
            val protoIcon = row.findViewById<ImageView>(R.id.destProtoIcon)
            protoIcon.setImageResource(if (d.enc) R.drawable.ic_glyph_lock else R.drawable.ic_glyph_warn)
            protoIcon.imageTintList = colorState(protoColor)
            val protoText = row.findViewById<TextView>(R.id.destProtoText)
            protoText.text = d.proto
            protoText.setTextColor(color(protoColor))
        }

        val metaTv = row.findViewById<TextView>(R.id.destMeta)
        if (dnsOnly) {
            val parts = listOfNotNull(
                d.org.takeIf { it.isNotBlank() },
                d.asn.takeIf { it.isNotBlank() },
                d.geo.takeIf { it.isNotBlank() },
                d.trk?.let { NtmDemoData.tracker(it)?.cat },
            )
            metaTv.text = parts.joinToString(" · ")
            metaTv.visibility = if (metaTv.text.isBlank()) View.GONE else View.VISIBLE
        } else {
            metaTv.visibility = View.VISIBLE
            val meta = if (advanced) "${d.org} · ${d.asn}" else d.asn
            metaTv.text = "$meta · ${d.geo} · :${d.port}"
        }

        val chip = row.findViewById<LinearLayout>(R.id.destTrackerChip)
        if (d.trk != null) {
            val trk = NtmDemoData.tracker(d.trk)
            val cc = catColor(trk?.cat)
            chip.visibility = View.VISIBLE
            chip.background = pillBg(color(R.color.v4_surf_tint), withAlpha(cc, 0x44), 5)
            row.findViewById<View>(R.id.destTrackerDot).backgroundTintList = ColorStateList.valueOf(cc)
            val tt = row.findViewById<TextView>(R.id.destTrackerText)
            tt.text = "${trk?.name} · ${trk?.cat}"
            tt.setTextColor(cc)
            chip.setOnClickListener { showTracker(d.trk) }
        } else {
            chip.visibility = View.GONE
        }

        row.findViewById<TextView>(R.id.destBytes).text =
            if (d.proto.isBlank()) "" else NtmDemoData.fmtBytes(d.bytesKb)

        val blockBtn = row.findViewById<LinearLayout>(R.id.destBlock)
        val blockIcon = row.findViewById<ImageView>(R.id.destBlockIcon)
        val blockText = row.findViewById<TextView>(R.id.destBlockText)
        if (isBlocked) {
            blockBtn.background = pillBg(color(R.color.v4_bad_bg), withAlpha(color(R.color.v4_bad), 0x55), 8)
            blockIcon.imageTintList = colorState(R.color.v4_bad)
            blockText.setText(R.string.ntm_blocked)
            blockText.setTextColor(color(R.color.v4_bad))
        } else {
            blockBtn.background = pillBg(color(R.color.v4_surf3), color(R.color.v4_hairline2), 8)
            blockIcon.imageTintList = colorState(R.color.v4_fg2)
            blockText.setText(R.string.ntm_block)
            blockText.setTextColor(color(R.color.v4_fg2))
        }
        blockBtn.setOnClickListener {
            val nowBlocked = if (liveMode) isBlockedNow(d.host) else blocked.contains(d.host)
            setHostBlocked(d.host, !nowBlocked)
            Toast.makeText(
                this,
                getString(if (nowBlocked) R.string.ntm_allowed_toast else R.string.ntm_blocked_one_toast, d.host),
                Toast.LENGTH_SHORT,
            ).show()
            renderDestinations()
        }

        // Tapping anywhere else on the card opens its info dialog — full tracker details for a
        // tracker endpoint, or a basic destination card otherwise. The tracker chip and the Block
        // pill keep their own taps (they sit above this and consume the touch).
        row.isClickable = true
        row.setOnClickListener { if (d.trk != null) showTracker(d.trk) else showDestInfo(d) }
    }

    private fun renderBlockAll() {
        val s = NtmDemoData.appStats(app)
        // "Block all trackers for this app" adds every tracker destination to the user blocklist.
        if (s.trackerDests.isNotEmpty()) {
            blockAllWrap.visibility = View.VISIBLE
            btnBlockAll.text = getString(R.string.ntm_block_all_fmt, app.name)
        } else {
            blockAllWrap.visibility = View.GONE
        }
    }

    /** Tracker detail dialog: who it is (company · category), what data it collects (category
     *  expanded into plain English), why it's in this app (the Exodus/curated description), and the
     *  endpoint it talks to. Confirm blocks every destination of this tracker for the app. */
    private fun showTracker(key: String) {
        val trk = NtmDemoData.tracker(key) ?: return
        val sb = SpannableStringBuilder()
        val header = if (trk.owner.isNotBlank() && !trk.owner.equals(trk.name, ignoreCase = true))
            "${trk.owner} · ${trk.cat}" else trk.cat
        appendDim(sb, header)
        section(sb, getString(R.string.ntm_tracker_collects_label), collectsText(trk.cat))
        if (trk.desc.isNotBlank()) section(sb, getString(R.string.ntm_tracker_purpose_label), trk.desc)
        // The endpoint is an exact hostname → only reveal it when Show-full-hostnames is on.
        if (advanced && trk.domain.isNotBlank())
            section(sb, getString(R.string.ntm_tracker_endpoint_label), trk.domain)

        ScanDialog.confirm(
            this, trk.name, sb,
            getString(R.string.ntm_tracker_block), getString(R.string.ntm_tracker_close),
        ) {
            app.dests.filter { it.trk == key }.forEach { setHostBlocked(it.host, true) }
            Toast.makeText(this, getString(R.string.ntm_blocked_one_toast, trk.name), Toast.LENGTH_SHORT).show()
            renderDestinations()
        }
    }

    /** Info dialog for a non-tracker destination — what we know (owner / ASN / country) plus the
     *  honest note that it isn't a known tracker. Still offers a block, since any host can be sunk. */
    private fun showDestInfo(d: Dest) {
        val title = if (advanced || d.org.isBlank()) d.host else d.org
        val sb = SpannableStringBuilder()
        val meta = listOfNotNull(
            d.org.takeIf { it.isNotBlank() },
            d.asn.takeIf { it.isNotBlank() },
            d.geo.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (meta.isNotBlank()) { appendDim(sb, meta); sb.append("\n\n") }
        sb.append(getString(R.string.ntm_dest_not_tracker))
        ScanDialog.confirm(
            this, title, sb,
            getString(R.string.ntm_block), getString(R.string.ntm_tracker_close),
        ) {
            setHostBlocked(d.host, true)
            Toast.makeText(this, getString(R.string.ntm_blocked_one_toast, d.host), Toast.LENGTH_SHORT).show()
            renderDestinations()
        }
    }

    /** Plain-English "what information this collects", from the tracker's category. */
    private fun collectsText(cat: String): String = getString(
        when (cat) {
            "Advertising", "Advertisement" -> R.string.ntm_cat_advertising
            "Analytics" -> R.string.ntm_cat_analytics
            "Attribution" -> R.string.ntm_cat_attribution
            "Identification" -> R.string.ntm_cat_identification
            "Location" -> R.string.ntm_cat_location
            "Diagnostics", "Crash reporting" -> R.string.ntm_cat_diagnostics
            "Profiling" -> R.string.ntm_cat_profiling
            else -> R.string.ntm_cat_generic
        }
    )

    private fun appendDim(sb: SpannableStringBuilder, text: String) {
        val start = sb.length
        sb.append(text)
        sb.setSpan(ForegroundColorSpan(color(R.color.v4_fg3)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Append a bold section label + its body (skipped when the body is empty). */
    private fun section(sb: SpannableStringBuilder, label: String, body: String) {
        if (body.isBlank()) return
        if (sb.isNotEmpty()) sb.append("\n\n")
        val start = sb.length
        sb.append(label)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(color(R.color.v4_fg1)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n").append(body)
    }

    /** Mirrors ScanDnsVpnService.shouldBlock: user-allow wins; then explicit user-block; else the
     *  curated list while the global toggle is on. */
    private fun isBlockedNow(host: String): Boolean {
        if (PreferencesManager.isNetMonAllowed(this, host)) return false
        if (PreferencesManager.isNetMonUserBlocked(this, host)) return true
        return PreferencesManager.isNetMonBlockingEnabled(this) && NtmBlocklist.isBlocked(this, host)
    }

    /** Block/unblock a specific host. Live → user-blocklist + allowlist (the sinkhole picks it up on
     *  the next lookup); demo → the in-memory set. */
    private fun setHostBlocked(host: String, block: Boolean) {
        if (liveMode) {
            PreferencesManager.setNetMonUserBlocked(this, host, block)
            PreferencesManager.setNetMonAllowed(this, host, !block)
        } else {
            if (block) blocked.add(host) else blocked.remove(host)
        }
    }

    private fun catColor(cat: String?): Int = when (cat) {
        "Advertising" -> color(R.color.v4_bad)
        "Attribution", "Analytics" -> color(R.color.v4_warn)
        else -> color(R.color.v4_fg2)
    }

    private fun pillBg(fillColor: Int, strokeColor: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }

    private fun withAlpha(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0xFFFFFF)
    private fun parseColor(hex: String, fallback: Int): Int =
        runCatching { Color.parseColor(hex) }.getOrDefault(fallback)

    private val iconCache = HashMap<String, Drawable?>()
    private fun appIconFor(pkg: String): Drawable? =
        iconCache.getOrPut(pkg) { runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)
    private fun colorState(res: Int): ColorStateList = ColorStateList.valueOf(color(res))
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun slideBack() = overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)

    companion object {
        private const val EXTRA_APP_ID = "app_id"
        private const val EXTRA_ADVANCED = "advanced"

        fun start(context: Context, appId: String, advanced: Boolean) {
            context.startActivity(
                Intent(context, NetworkAppDetailActivity::class.java)
                    .putExtra(EXTRA_APP_ID, appId)
                    .putExtra(EXTRA_ADVANCED, advanced)
            )
            if (context is android.app.Activity) {
                @Suppress("DEPRECATION")
                context.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        }
    }
}

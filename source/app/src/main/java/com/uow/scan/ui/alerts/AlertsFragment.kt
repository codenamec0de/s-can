package com.uow.scan.ui.alerts

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.uow.scan.AppDetailActivity
import com.uow.scan.R
import com.uow.scan.model.PermissionAlert
import com.uow.scan.util.AlertStorage
import com.uow.scan.util.AttributionEngine
import com.uow.scan.util.BackgroundReasonInspector
import com.uow.scan.util.BackgroundUsageMonitor
import com.uow.scan.util.BehaviorScorer
import com.uow.scan.util.NotificationListenerHelper
import com.uow.scan.util.WeeklyStatsRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.max

class AlertsFragment : Fragment() {

    private enum class Filter { ALL, CRITICAL, PATTERNS }

    private lateinit var emptyState: LinearLayout
    private lateinit var summaryCard: LinearLayout
    private lateinit var filterRow: LinearLayout
    private lateinit var groupsContainer: LinearLayout
    private lateinit var tvEndOfTimeline: TextView
    private lateinit var btnAlertsSettings: FrameLayout
    private lateinit var notifAccessBanner: LinearLayout

    private lateinit var tvMetricToday: TextView
    private lateinit var tvMetricCritical: TextView
    private lateinit var tvMetricPatterns: TextView

    private lateinit var filterAll: FrameLayout
    private lateinit var filterCritical: FrameLayout
    private lateinit var filterPatterns: FrameLayout
    private lateinit var tvFilterAllLabel: TextView
    private lateinit var tvFilterAllCount: TextView
    private lateinit var tvFilterCriticalLabel: TextView
    private lateinit var tvFilterCriticalCount: TextView
    private lateinit var tvFilterPatternsLabel: TextView
    private lateinit var tvFilterPatternsCount: TextView

    private var filter: Filter = Filter.ALL
    private var allAlerts: List<PermissionAlert> = emptyList()
    /** Behavior-scored verdicts keyed by alert.id. Filled async after [loadAlerts]. */
    private var scoresById: Map<String, BehaviorScorer.Score> = emptyMap()
    /** Per-alert attribution (state-bucketed bytes + sensor timeline + reasons),
     *  filled async after [loadAlerts]. */
    private var attributionsById: Map<String, AttributionEngine.Attribution> = emptyMap()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_alerts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupListeners()
        loadAlerts()
    }

    override fun onResume() {
        super.onResume()
        refreshNotifAccessBanner()
        loadAlerts()
    }

    private fun bindViews(view: View) {
        emptyState = view.findViewById(R.id.emptyState)
        summaryCard = view.findViewById(R.id.summaryCard)
        filterRow = view.findViewById(R.id.filterRow)
        groupsContainer = view.findViewById(R.id.groupsContainer)
        tvEndOfTimeline = view.findViewById(R.id.tvEndOfTimeline)
        btnAlertsSettings = view.findViewById(R.id.btnAlertsSettings)
        notifAccessBanner = view.findViewById(R.id.notifAccessBanner)

        tvMetricToday = view.findViewById(R.id.tvMetricToday)
        tvMetricCritical = view.findViewById(R.id.tvMetricCritical)
        tvMetricPatterns = view.findViewById(R.id.tvMetricPatterns)

        filterAll = view.findViewById(R.id.filterAll)
        filterCritical = view.findViewById(R.id.filterCritical)
        filterPatterns = view.findViewById(R.id.filterPatterns)
        tvFilterAllLabel = view.findViewById(R.id.tvFilterAllLabel)
        tvFilterAllCount = view.findViewById(R.id.tvFilterAllCount)
        tvFilterCriticalLabel = view.findViewById(R.id.tvFilterCriticalLabel)
        tvFilterCriticalCount = view.findViewById(R.id.tvFilterCriticalCount)
        tvFilterPatternsLabel = view.findViewById(R.id.tvFilterPatternsLabel)
        tvFilterPatternsCount = view.findViewById(R.id.tvFilterPatternsCount)
    }

    private fun setupListeners() {
        filterAll.setOnClickListener { setFilter(Filter.ALL) }
        filterCritical.setOnClickListener { setFilter(Filter.CRITICAL) }
        filterPatterns.setOnClickListener { setFilter(Filter.PATTERNS) }
        btnAlertsSettings.setOnClickListener { confirmClearAlerts() }
        notifAccessBanner.setOnClickListener { NotificationListenerHelper.openSettings(requireContext()) }
    }

    private fun refreshNotifAccessBanner() {
        notifAccessBanner.visibility =
            if (NotificationListenerHelper.isGranted(requireContext())) View.GONE else View.VISIBLE
    }

    private fun setFilter(f: Filter) {
        if (filter == f) return
        filter = f
        renderGroups()
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun loadAlerts() {
        // Persist any completed weeks before scoping the live metrics to "this week".
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { WeeklyStatsRecorder.snapshotIfNeeded(requireContext()) }
            }
        }
        allAlerts = AlertStorage.getAlerts(requireContext())
        if (allAlerts.isEmpty()) {
            summaryCard.visibility = View.GONE
            filterRow.visibility = View.GONE
            groupsContainer.visibility = View.GONE
            tvEndOfTimeline.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
            return
        }
        summaryCard.visibility = View.VISIBLE
        filterRow.visibility = View.VISIBLE
        groupsContainer.visibility = View.VISIBLE
        emptyState.visibility = View.GONE

        // First pass renders with whatever verdicts we cached on the previous
        // load (could be empty). Then we score asynchronously and re-render
        // when the new verdicts arrive — perceptible delay is ~50 ms tops on
        // a real device.
        renderMetrics()
        renderGroups()
        viewLifecycleOwner.lifecycleScope.launch {
            val newScores = withContext(Dispatchers.IO) {
                BehaviorScorer.scoreAll(requireContext(), allAlerts)
            }
            if (!isAdded) return@launch
            scoresById = newScores
            renderMetrics()
            renderGroups()
        }
        // Attribution runs in parallel with scoring — both feed the same row
        // render. NetworkStats.queryDetails + DAO read can take ~50–200 ms per
        // alert on a real device, so we keep the work on Dispatchers.IO and
        // only re-render once the full map is built.
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            val map = withContext(Dispatchers.IO) {
                allAlerts.associate { alert ->
                    alert.id to AttributionEngine.attribute(ctx, alert)
                }
            }
            if (!isAdded) return@launch
            attributionsById = map
            renderGroups()
        }
    }

    private fun renderMetrics() {
        // Top metrics ("Today / Critical / Patterns") reset every Monday — they reflect
        // only the alerts inside the current ISO week. Completed weeks are snapshotted
        // by WeeklyStatsRecorder and surface in the PDF report.
        val weekStart = WeeklyStatsRecorder.startOfWeek(System.currentTimeMillis())
        val thisWeek = allAlerts.filter { it.timestamp >= weekStart }

        val today = countInDayOffset(allAlerts, 0)
        val critical = thisWeek.count { it.severity() == Sev.BAD }
        val patternCount = patternsFromAll(thisWeek).size

        tvMetricToday.text = today.toString()
        tvMetricCritical.text = critical.toString()
        tvMetricPatterns.text = patternCount.toString()

        // Filter chip counts (Critical/Patterns mirror the weekly scope; All counts
        // every alert in the timeline so users can still browse past weeks).
        tvFilterAllCount.text = allAlerts.size.toString()
        tvFilterCriticalCount.text = critical.toString()
        tvFilterPatternsCount.text = patternCount.toString()
    }

    private fun renderGroups() {
        val ctx = requireContext()
        groupsContainer.removeAllViews()

        // Apply chip styling
        applyFilterStyle(filterAll, tvFilterAllLabel, tvFilterAllCount, filter == Filter.ALL)
        applyFilterStyle(filterCritical, tvFilterCriticalLabel, tvFilterCriticalCount, filter == Filter.CRITICAL)
        applyFilterStyle(filterPatterns, tvFilterPatternsLabel, tvFilterPatternsCount, filter == Filter.PATTERNS)

        // Pre-compute pattern map (per package + perm) so each row can look up its own pattern.
        val patterns = patternsFromAll(allAlerts)

        val filtered: List<PermissionAlert> = when (filter) {
            Filter.ALL -> allAlerts
            Filter.CRITICAL -> allAlerts.filter { it.severity() == Sev.BAD }
            Filter.PATTERNS -> allAlerts.filter { patterns.containsKey(patternKey(it)) }
        }

        val today = filtered.filter { dayOffset(it.timestamp) == 0 }
        val yesterday = filtered.filter { dayOffset(it.timestamp) == 1 }
        val earlier = filtered.filter {
            val o = dayOffset(it.timestamp)
            o in 2..6
        }
        val older = filtered.filter { dayOffset(it.timestamp) > 6 }

        renderGroup(ctx, R.string.alerts_v4_group_today, today, patterns)
        renderGroup(ctx, R.string.alerts_v4_group_yesterday, yesterday, patterns)
        renderGroup(ctx, R.string.alerts_v4_group_earlier, earlier, patterns)
        renderGroup(ctx, R.string.alerts_v4_group_older, older, patterns)

        val flagged = allAlerts.count { it.severity() != Sev.OK }
        tvEndOfTimeline.text = getString(R.string.alerts_v4_end_of_timeline, flagged, allAlerts.size)
        tvEndOfTimeline.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderGroup(
        ctx: android.content.Context,
        labelRes: Int,
        alerts: List<PermissionAlert>,
        patterns: Map<String, Pattern>,
    ) {
        if (alerts.isEmpty()) return
        val groupView = LayoutInflater.from(ctx)
            .inflate(R.layout.item_v4_alert_group, groupsContainer, false)
        val tvLabel = groupView.findViewById<TextView>(R.id.tvGroupLabel)
        val tvCount = groupView.findViewById<TextView>(R.id.tvGroupCount)
        val body = groupView.findViewById<LinearLayout>(R.id.groupBody)

        tvLabel.setText(labelRes)
        tvCount.text = if (alerts.size == 1)
            getString(R.string.alerts_v4_count_one, 1)
        else
            getString(R.string.alerts_v4_count_n, alerts.size)

        val sortedDesc = alerts.sortedByDescending { it.timestamp }
        for ((index, alert) in sortedDesc.withIndex()) {
            val rowView = LayoutInflater.from(ctx)
                .inflate(R.layout.item_v4_alert_row, body, false)
            bindRow(rowView, alert, patterns[patternKey(alert)],
                isLast = index == sortedDesc.size - 1)
            body.addView(rowView)
        }
        groupsContainer.addView(groupView)
    }

    private fun bindRow(
        view: View,
        alert: PermissionAlert,
        pattern: Pattern?,
        isLast: Boolean,
    ) {
        val ctx = view.context

        val ivIcon = view.findViewById<ImageView>(R.id.ivAlertAppIcon)
        val tvInitial = view.findViewById<TextView>(R.id.tvAlertAppInitial)
        val tvAppName = view.findViewById<TextView>(R.id.tvAlertAppName)
        val tvTime = view.findViewById<TextView>(R.id.tvAlertTime)
        val tvTitle = view.findViewById<TextView>(R.id.tvAlertTitle)
        val tvDetail = view.findViewById<TextView>(R.id.tvAlertDetail)
        val tvPermsObserved = view.findViewById<TextView>(R.id.tvAlertPermsObserved)
        val tvPermsHeld = view.findViewById<TextView>(R.id.tvAlertPermsHeld)
        val tvReason = view.findViewById<TextView>(R.id.tvAlertReason)
        val divider = view.findViewById<View>(R.id.alertRowDivider)

        // Avatar
        try {
            val icon = ctx.packageManager.getApplicationIcon(alert.packageName)
            ivIcon.setImageDrawable(icon)
            ivIcon.visibility = View.VISIBLE
            tvInitial.visibility = View.GONE
        } catch (e: PackageManager.NameNotFoundException) {
            ivIcon.visibility = View.GONE
            tvInitial.visibility = View.VISIBLE
            tvInitial.text = alert.appName.firstOrNull()?.uppercase().orEmpty()
        }

        tvAppName.text = alert.appName
        tvTime.text = formatRelativeClockTime(alert.timestamp)
        tvTitle.text = buildAlertTitle(alert)
        val detail = buildAlertDetail(alert)
        if (detail.isNullOrBlank()) {
            tvDetail.visibility = View.GONE
        } else {
            tvDetail.visibility = View.VISIBLE
            tvDetail.text = detail
        }

        // Granular permission breakdown: what was actively observed vs. what
        // the app could have silently used (location/contacts/SMS — all the
        // ops Android won't let third-party apps observe at runtime).
        val (observedText, heldText) = buildPermissionsBreakdown(alert)
        if (observedText.isNullOrBlank()) {
            tvPermsObserved.visibility = View.GONE
        } else {
            tvPermsObserved.visibility = View.VISIBLE
            tvPermsObserved.text = observedText
        }
        if (heldText.isNullOrBlank()) {
            tvPermsHeld.visibility = View.GONE
        } else {
            tvPermsHeld.visibility = View.VISIBLE
            tvPermsHeld.text = heldText
        }

        // Why active in background: derived from the app's manifest (foreground
        // service types, sync adapters, FCM push, JobScheduler, boot-completed).
        // Tells the user *why* this app sent data even though it wasn't on screen.
        val reasonText = buildBackgroundReason(alert)
        if (reasonText.isNullOrBlank()) {
            tvReason.visibility = View.GONE
        } else {
            tvReason.visibility = View.VISIBLE
            tvReason.text = reasonText
        }

        // Permission pill
        bindPermPill(view, alert)

        // Pattern card
        bindPatternCard(view, alert, pattern)

        divider.visibility = if (isLast) View.GONE else View.VISIBLE

        view.setOnClickListener {
            AlertStorage.markAsRead(ctx, alert.id)
            startActivity(
                Intent(ctx, AppDetailActivity::class.java)
                    .putExtra(AppDetailActivity.EXTRA_PACKAGE_NAME, alert.packageName)
            )
        }
    }

    private fun bindPermPill(view: View, alert: PermissionAlert) {
        val ctx = view.context
        val pill = view.findViewById<LinearLayout>(R.id.permPill)
        val ivIcon = view.findViewById<ImageView>(R.id.ivPermPillIcon)
        val tvLabel = view.findViewById<TextView>(R.id.tvPermPillLabel)

        val (label, iconRes) = primaryPermDescriptor(alert)
        val sev = alert.severity()
        val color = ContextCompat.getColor(
            ctx,
            when (sev) {
                Sev.BAD -> R.color.v4_bad
                Sev.WARN -> R.color.v4_warn
                Sev.OK -> R.color.v4_accent
            }
        )
        val bg = when (sev) {
            Sev.BAD -> R.drawable.bg_v4_perm_pill_bad
            Sev.WARN -> R.drawable.bg_v4_perm_pill_warn
            Sev.OK -> R.drawable.bg_v4_perm_pill_accent_inset
        }

        pill.setBackgroundResource(bg)
        ivIcon.setImageResource(iconRes)
        ivIcon.setColorFilter(color)
        tvLabel.text = label
        tvLabel.setTextColor(color)
    }

    private fun bindPatternCard(view: View, alert: PermissionAlert, pattern: Pattern?) {
        val ctx = view.context
        val card = view.findViewById<LinearLayout>(R.id.patternCard)
        // Attach the pattern card only to the representative (most recent) alert in the
        // group so the timeline doesn't repeat identical sparklines on every matching row.
        if (pattern == null || alert.id != pattern.representativeId) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE

        val tvCount = view.findViewById<TextView>(R.id.tvPatternCount)
        val sparkline = view.findViewById<LinearLayout>(R.id.sparklineContainer)
        val tvSummary = view.findViewById<TextView>(R.id.tvPatternSummary)

        val sev = alert.severity()
        val color = ContextCompat.getColor(
            ctx,
            when (sev) {
                Sev.BAD -> R.color.v4_bad
                Sev.WARN -> R.color.v4_warn
                Sev.OK -> R.color.v4_accent
            }
        )
        tvCount.setTextColor(color)
        tvCount.text = getString(
            R.string.alerts_v4_pattern_count_cadence,
            pattern.count, formatCadence(pattern.cadenceMs)
        )

        // Sparkline: 24 bars, height proportional to bucket density
        sparkline.removeAllViews()
        val maxV = (pattern.bars.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val density = resources.displayMetrics.density
        val barHeightDp = 22
        val totalHeightPx = (barHeightDp * density).toInt()
        for (v in pattern.bars) {
            val frac = if (v <= 0f) 0f else max(0.18f, v / maxV)
            val barWrap = LinearLayout(ctx)
            val lp = LinearLayout.LayoutParams(0, totalHeightPx, 1f)
            lp.marginEnd = (1.5 * density).toInt()
            barWrap.layoutParams = lp
            barWrap.gravity = android.view.Gravity.BOTTOM
            barWrap.orientation = LinearLayout.VERTICAL

            val bar = View(ctx)
            val barLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (v <= 0f) (1 * density).toInt() else (totalHeightPx * frac).toInt()
            )
            bar.layoutParams = barLp
            bar.background = ContextCompat.getDrawable(ctx, R.drawable.bg_v4_sparkline_bar)
            bar.background?.setTint(
                if (v > 0f) color else ContextCompat.getColor(ctx, R.color.v4_hairline)
            )
            bar.alpha = if (v > 0f) 0.85f else 0.5f
            barWrap.addView(bar)
            sparkline.addView(barWrap)
        }
        tvSummary.text = patternSummary(alert, pattern)
    }

    // ------------------------------------------------------------------
    // Filter chip styling
    // ------------------------------------------------------------------

    private fun applyFilterStyle(
        seg: View, label: TextView, count: TextView, active: Boolean,
    ) {
        seg.setBackgroundResource(if (active) R.drawable.bg_v4_apps_segment_active else 0)
        val color = ContextCompat.getColor(
            requireContext(), if (active) R.color.v4_fg0 else R.color.v4_fg2
        )
        label.setTextColor(color)
        count.setTextColor(color)
    }

    // ------------------------------------------------------------------
    // Severity / pill / cadence helpers
    // ------------------------------------------------------------------

    private enum class Sev { BAD, WARN, OK }

    private fun PermissionAlert.severity(): Sev {
        // Prefer the behaviour-baseline verdict when we have one — it sees
        // history and night-hour context, not just bytes. Falls back to a
        // simple sensor-observed heuristic for alerts the scorer hasn't
        // touched yet (first paint before the async score lands).
        scoresById[id]?.let {
            return when (it.verdict) {
                BehaviorScorer.Verdict.SUSPICIOUS -> Sev.BAD
                BehaviorScorer.Verdict.UNUSUAL -> Sev.WARN
                BehaviorScorer.Verdict.NORMAL -> Sev.OK
            }
        }
        val sensorObserved = permissions.isNotEmpty()
        return when {
            sensorObserved && dataUsedBytes >= 1024L * 1024L -> Sev.BAD
            sensorObserved -> Sev.WARN
            dataUsedBytes > 0 -> Sev.WARN
            else -> Sev.OK
        }
    }

    private fun primaryPermDescriptor(alert: PermissionAlert): Pair<String, Int> {
        // The pill labels the *verdict* once we've scored the alert, so the
        // user reads "NORMAL / UNUSUAL / SUSPICIOUS" — a judgement they can
        // act on, not the underlying op name. Falls back to the legacy
        // sensor-name pill before scoring lands.
        scoresById[alert.id]?.let {
            return when (it.verdict) {
                BehaviorScorer.Verdict.NORMAL -> "Normal" to R.drawable.ic_glyph_shield
                BehaviorScorer.Verdict.UNUSUAL -> "Unusual" to R.drawable.ic_glyph_warn
                BehaviorScorer.Verdict.SUSPICIOUS -> "Suspicious" to R.drawable.ic_glyph_warn
            }
        }
        val first = alert.permissions.firstOrNull().orEmpty().uppercase()
        return when {
            first.isEmpty() -> "Background data" to R.drawable.ic_glyph_shield
            first.contains("CAMERA") -> "Camera" to R.drawable.ic_v4_glyph_camera
            first.contains("RECORD_AUDIO") || first.contains("MICROPHONE") -> "Microphone" to R.drawable.ic_v4_glyph_mic
            first.contains("LOCATION") -> "Location" to R.drawable.ic_v4_glyph_pin
            first.contains("CONTACTS") -> "Contacts" to R.drawable.ic_v4_glyph_contacts
            first.contains("SMS") -> "SMS" to R.drawable.ic_glyph_sms
            first.contains("CALL") || first.contains("PHONE") -> "Phone" to R.drawable.ic_v4_glyph_phone
            first.contains("CALENDAR") -> "Calendar" to R.drawable.ic_v4_glyph_calendar
            first.contains("STORAGE") || first.contains("MEDIA") -> "Storage" to R.drawable.ic_v4_glyph_storage
            first.contains("SENSORS") || first.contains("ACTIVITY_RECOGNITION") -> "Body" to R.drawable.ic_v4_glyph_body
            else -> (alert.permissions.firstOrNull() ?: "Permission") to R.drawable.ic_glyph_shield
        }
    }

    private fun buildAlertTitle(alert: PermissionAlert): String {
        // When the scorer has assessed this alert, lead with its plain-language
        // headline — that's the line the user actually reads. The legacy
        // descriptive title is the pre-scoring fallback only.
        scoresById[alert.id]?.let { return it.headline }
        if (alert.permissions.isEmpty()) {
            return if (alert.isSilentBackground)
                "${alert.appName} sent data silently in the background"
            else
                "${alert.appName} sent data in the background for ${alert.formattedDuration}"
        }
        val perm = primaryPermDescriptor(alert).first.lowercase()
        return if (alert.isSilentBackground)
            "${alert.appName} used $perm with silent background activity"
        else
            "${alert.appName} used $perm while backgrounded for ${alert.formattedDuration}"
    }

    private fun buildAlertDetail(alert: PermissionAlert): String? {
        // Scored: headline already carries the action — the supporting line
        // adds the why ("3× the usual amount", "matches typical behaviour").
        // The granular "Used / Could access" breakdown is rendered separately
        // by buildPermissionsBreakdown(), so we no longer mix it in here.
        scoresById[alert.id]?.let { score ->
            return score.supporting
        }
        val parts = mutableListOf<String>()
        if (alert.dataUsedBytes > 0) parts += "${alert.formattedDataUsed} of data"
        if (alert.permissions.size > 1) parts += "${alert.permissions.size} sensors observed"
        if (alert.isSilentBackground) parts += "no Activity transitions"
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /**
     * Builds the two granular detail lines for an alert:
     *  - first: ops actively observed during the window (Camera / Microphone — the only
     *    ones Android lets a non-privileged app watch via public callbacks).
     *  - second: every sensitive permission the app currently holds, with full
     *    granular labels ("Precise Location", "Background Location", "Approximate
     *    Location" — not collapsed to just "Location"), so the user can see exactly
     *    what data this app could have reached while it was sending traffic.
     *
     * Anything in `observed` is filtered out of `held` so we don't repeat
     * "Camera" on both lines when the app actually used it.
     */
    private fun buildPermissionsBreakdown(alert: PermissionAlert): Pair<String?, String?> {
        val observed = alert.permissions.filter { it.isNotBlank() }.distinct()
        val heldRaw = grantedSensitivePermsFor(alert.packageName)
        val held = heldRaw.filter { label ->
            observed.none { it.equals(label, ignoreCase = true) }
        }

        val observedLine = if (observed.isEmpty()) null
            else "Used " + observed.joinToString(" · ")

        val heldLine = if (held.isEmpty()) null
            else (if (observed.isEmpty()) "Could access " else "Could also access ") +
                held.joinToString(" · ")

        return observedLine to heldLine
    }

    /**
     * Full granular list of dangerous permissions [packageName] currently holds.
     * Unlike the previous `grantedSummaryFor`, labels are NOT collapsed —
     * "Precise Location" stays distinct from "Background Location" so the user
     * can see exactly what each app can reach. Cached per-package for the
     * lifetime of one bind pass to keep list scroll cheap.
     */
    private val grantedCache = mutableMapOf<String, List<String>>()
    private fun grantedSensitivePermsFor(packageName: String): List<String> {
        grantedCache[packageName]?.let { return it }
        val ctx = context ?: return emptyList()
        val labels = try {
            BackgroundUsageMonitor.getGrantedSensitivePermissions(ctx.packageManager, packageName)
        } catch (_: Exception) {
            emptyList()
        }
        val deduped = labels.distinct()
        grantedCache[packageName] = deduped
        return deduped
    }

    /**
     * Builds the "Why active in background" line.
     *
     * If [AttributionEngine] has finished computing for this alert, we surface
     * the rich evidence-backed explanation: concrete sensor-access timestamps,
     * background-vs-foreground byte split (true OS attribution via
     * `NetworkStats.Bucket.state`), mobile-vs-Wi-Fi split, and the most-likely
     * declared mechanism. This is the most accurate explanation possible
     * without `WATCH_APPOPS` (signature-only, unavailable to third-party apps).
     *
     * Before attribution lands, we fall back to the manifest-only summary —
     * still useful, just less specific.
     */
    private fun buildBackgroundReason(alert: PermissionAlert): String? {
        val ctx = context ?: return null

        attributionsById[alert.id]?.let { attr ->
            val rich = AttributionEngine.explain(attr)
            if (rich.isNotBlank()) return "Why active: $rich"
            // Attribution computed but had nothing useful — fall through to
            // the manifest-only summary so we still show the capability list.
        }

        val reasons = try {
            BackgroundReasonInspector.inspect(ctx, alert.packageName)
        } catch (_: Exception) {
            return null
        }
        val summary = BackgroundReasonInspector.summary(reasons) ?: return null
        return "Why active: $summary"
    }

    private fun formatRelativeClockTime(timestamp: Long): String {
        val now = Calendar.getInstance()
        val ts = Calendar.getInstance().apply { timeInMillis = timestamp }
        val sameDay = now.get(Calendar.YEAR) == ts.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == ts.get(Calendar.DAY_OF_YEAR)
        val sameWeek = now.get(Calendar.WEEK_OF_YEAR) == ts.get(Calendar.WEEK_OF_YEAR) &&
                now.get(Calendar.YEAR) == ts.get(Calendar.YEAR)

        val hh = ts.get(Calendar.HOUR_OF_DAY)
        val mm = ts.get(Calendar.MINUTE)
        return when {
            sameDay -> String.format("%02d:%02d", hh, mm)
            !sameWeek -> {
                val month = ts.getDisplayName(Calendar.MONTH, Calendar.SHORT, java.util.Locale.getDefault()) ?: ""
                "$month ${ts.get(Calendar.DAY_OF_MONTH)} · ${String.format("%02d:%02d", hh, mm)}"
            }
            else -> {
                val day = ts.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, java.util.Locale.getDefault()) ?: ""
                "$day ${String.format("%02d:%02d", hh, mm)}"
            }
        }
    }

    private fun formatCadence(cadenceMs: Long): String {
        if (cadenceMs <= 0) return getString(R.string.alerts_v4_cadence_irregular)
        val minutes = cadenceMs / 60000L
        return if (minutes < 90) {
            getString(R.string.alerts_v4_cadence_minutes, minutes.toInt())
        } else {
            val hours = minutes / 60.0
            getString(R.string.alerts_v4_cadence_hours, hours)
        }
    }

    // ------------------------------------------------------------------
    // Pattern detection
    // ------------------------------------------------------------------

    private data class Pattern(
        val count: Int,
        val cadenceMs: Long,
        val bars: List<Float>, // 24 hourly buckets normalized 0..1
        val representativeId: String, // id of the most recent alert in the pattern
    )

    private fun patternKey(alert: PermissionAlert): String {
        val first = alert.permissions.firstOrNull().orEmpty()
        return "${alert.packageName}|${primaryPermDescriptor(alert).first}|$first"
    }

    /**
     * Detects "patterns" — repeated alerts for the same (app, permission) within the last 24h.
     * A pattern requires at least 2 occurrences. The pattern attaches to the most recent
     * alert in the group so the timeline shows one card per recurring issue, not one per row.
     */
    private fun patternsFromAll(alerts: List<PermissionAlert>): Map<String, Pattern> {
        val now = System.currentTimeMillis()
        val cutoff = now - 24L * 60 * 60 * 1000
        val recent = alerts.filter { it.timestamp >= cutoff }

        val grouped = recent.groupBy { patternKey(it) }
        val out = HashMap<String, Pattern>()
        for ((key, occurrences) in grouped) {
            if (occurrences.size < 2) continue
            val sorted = occurrences.sortedBy { it.timestamp }
            val gaps = sorted.zipWithNext { a, b -> b.timestamp - a.timestamp }
            val cadence = if (gaps.isEmpty()) 0L else gaps.average().toLong()
            val bars = bucketize24h(sorted.map { it.timestamp }, now)
            out[key] = Pattern(
                count = occurrences.size,
                cadenceMs = cadence,
                bars = bars,
                representativeId = sorted.last().id,
            )
        }
        return out
    }

    /** Bucketizes timestamps into 24 hour-of-day bins, normalised to 0..1. */
    private fun bucketize24h(timestamps: List<Long>, now: Long): List<Float> {
        val buckets = IntArray(24)
        for (ts in timestamps) {
            val cal = Calendar.getInstance().apply { timeInMillis = ts }
            val hour = cal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
            buckets[hour] = buckets[hour] + 1
        }
        val maxV = max(1, buckets.maxOrNull() ?: 1)
        return buckets.map { it.toFloat() / maxV.toFloat() }
    }

    private fun patternSummary(alert: PermissionAlert, pattern: Pattern): String {
        // Find the highest-density bin to describe when reads happen most.
        val maxIdx = pattern.bars.indices.maxByOrNull { pattern.bars[it] } ?: 0
        val window = when (maxIdx) {
            in 6..11 -> getString(R.string.alerts_v4_pattern_summary_morning)
            in 12..17 -> getString(R.string.alerts_v4_pattern_summary_work)
            in 18..23 -> getString(R.string.alerts_v4_pattern_summary_evening)
            else -> getString(R.string.alerts_v4_pattern_summary_night)
        }
        val perm = primaryPermDescriptor(alert).first
        return getString(R.string.alerts_v4_pattern_summary_n, alert.appName, perm, pattern.count) +
                " " + window
    }

    // ------------------------------------------------------------------
    // Day grouping
    // ------------------------------------------------------------------

    /** 0 = today, 1 = yesterday, 2 = two days ago, ... */
    private fun dayOffset(timestamp: Long): Int {
        val now = Calendar.getInstance()
        val ts = Calendar.getInstance().apply { timeInMillis = timestamp }
        // Strip time to compare day boundaries.
        listOf(now, ts).forEach {
            it.set(Calendar.HOUR_OF_DAY, 0); it.set(Calendar.MINUTE, 0)
            it.set(Calendar.SECOND, 0); it.set(Calendar.MILLISECOND, 0)
        }
        val diff = now.timeInMillis - ts.timeInMillis
        val days = (diff / (24L * 60 * 60 * 1000)).toInt()
        return max(0, days)
    }

    private fun countInDayOffset(alerts: List<PermissionAlert>, offset: Int): Int =
        alerts.count { dayOffset(it.timestamp) == offset }

    // ------------------------------------------------------------------
    // Settings / clear / dev test
    // ------------------------------------------------------------------

    private fun confirmClearAlerts() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear all alerts?")
            .setMessage("This will remove all background usage alerts.")
            .setPositiveButton("Clear") { _, _ ->
                AlertStorage.clearAlerts(requireContext())
                loadAlerts()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

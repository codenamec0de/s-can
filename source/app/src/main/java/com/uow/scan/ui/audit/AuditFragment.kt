package com.uow.scan.ui.audit

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.uow.scan.AppDetailActivity
import com.uow.scan.R
import com.uow.scan.data.ScanDatabase
import com.uow.scan.data.entity.ScanResultEntity
import com.uow.scan.model.PermissionAlert
import com.uow.scan.util.AlertStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuditFragment : Fragment() {

    private enum class Filter { ALL, FLAGGED, CLEAN }
    private enum class Sev { BAD, WARN, OK }

    private data class AppRow(
        val packageName: String,
        val appName: String,
        val sev: Sev,
        val evidence: String,
    )

    private lateinit var tvAppsCount: TextView
    private lateinit var etSearch: EditText
    private lateinit var rvApps: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var loading: ProgressBar
    private lateinit var segAll: View
    private lateinit var segFlagged: View
    private lateinit var segClean: View
    private lateinit var tvSegAllLabel: TextView
    private lateinit var tvSegAllCount: TextView
    private lateinit var tvSegFlaggedLabel: TextView
    private lateinit var tvSegFlaggedCount: TextView
    private lateinit var tvSegCleanLabel: TextView
    private lateinit var tvSegCleanCount: TextView

    private val adapter = AppsAdapter()

    private var allRows: List<AppRow> = emptyList()
    private var filter: Filter = Filter.FLAGGED
    private var query: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_audit, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupListeners()
        rvApps.layoutManager = LinearLayoutManager(requireContext())
        rvApps.adapter = adapter
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        loadApps()
    }

    private fun bindViews(v: View) {
        tvAppsCount = v.findViewById(R.id.tvAppsCount)
        etSearch = v.findViewById(R.id.etSearch)
        rvApps = v.findViewById(R.id.rvApps)
        tvEmpty = v.findViewById(R.id.tvEmpty)
        loading = v.findViewById(R.id.loading)
        segAll = v.findViewById(R.id.segAll)
        segFlagged = v.findViewById(R.id.segFlagged)
        segClean = v.findViewById(R.id.segClean)
        tvSegAllLabel = v.findViewById(R.id.tvSegAllLabel)
        tvSegAllCount = v.findViewById(R.id.tvSegAllCount)
        tvSegFlaggedLabel = v.findViewById(R.id.tvSegFlaggedLabel)
        tvSegFlaggedCount = v.findViewById(R.id.tvSegFlaggedCount)
        tvSegCleanLabel = v.findViewById(R.id.tvSegCleanLabel)
        tvSegCleanCount = v.findViewById(R.id.tvSegCleanCount)
    }

    private fun setupListeners() {
        segAll.setOnClickListener { setFilter(Filter.ALL) }
        segFlagged.setOnClickListener { setFilter(Filter.FLAGGED) }
        segClean.setOnClickListener { setFilter(Filter.CLEAN) }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty().trim()
                applyFilters()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setFilter(f: Filter) {
        filter = f
        renderSegments()
        applyFilters()
    }

    private fun loadApps() {
        val ctx = context ?: return
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val db = ScanDatabase.getInstance(ctx)
                val scans = db.scanResultDao().getAll()
                val alerts = AlertStorage.getAlerts(ctx)
                buildRows(scans, alerts)
            }
            allRows = rows
            tvAppsCount.text = getString(R.string.apps_count_format, rows.size)
            renderSegments()
            applyFilters()
            loading.visibility = View.GONE
        }
    }

    private fun buildRows(
        scans: List<ScanResultEntity>,
        alerts: List<PermissionAlert>
    ): List<AppRow> {
        val alertsByPkg: Map<String, List<PermissionAlert>> = alerts.groupBy { it.packageName }
        return scans.map { entity ->
            val perms = entity.permissions.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val pkgAlerts = alertsByPkg[entity.packageName].orEmpty()
            val sev: Sev = when {
                pkgAlerts.isNotEmpty() -> Sev.BAD
                hasCriticalPermission(perms) -> Sev.WARN
                else -> Sev.OK
            }
            val evidence = when (sev) {
                Sev.BAD -> {
                    val mostCommon = pkgAlerts
                        .flatMap { it.permissions }
                        .groupingBy { it }
                        .eachCount()
                        .maxByOrNull { it.value }
                        ?.key
                    val count = pkgAlerts.size
                    val tag = humanizePermission(mostCommon)
                    getString(R.string.apps_evidence_bad_format, tag, count)
                }
                Sev.WARN -> getString(R.string.apps_evidence_warn)
                Sev.OK -> getString(R.string.apps_evidence_clean)
            }
            AppRow(
                packageName = entity.packageName,
                appName = entity.appName,
                sev = sev,
                evidence = evidence,
            )
        }.sortedWith(
            compareBy<AppRow> { it.sev.ordinal }.thenBy { it.appName.lowercase() }
        )
    }

    private fun hasCriticalPermission(perms: List<String>): Boolean {
        return perms.any { p ->
            val tail = p.substringAfterLast('.')
            tail in CRITICAL_PERMS
        }
    }

    private fun humanizePermission(p: String?): String {
        if (p.isNullOrEmpty()) return "background activity"
        val tail = p.substringAfterLast('.')
        return when (tail) {
            "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "ACCESS_COARSE_LOCATION" -> "location"
            "CAMERA" -> "camera"
            "RECORD_AUDIO" -> "mic"
            "READ_CONTACTS" -> "contacts"
            "READ_SMS", "RECEIVE_SMS" -> "SMS"
            else -> tail.lowercase().replace('_', ' ')
        }
    }

    private fun applyFilters() {
        val q = query.lowercase()
        val byFilter: List<AppRow> = when (filter) {
            Filter.ALL -> allRows
            Filter.FLAGGED -> allRows.filter { it.sev != Sev.OK }
            Filter.CLEAN -> allRows.filter { it.sev == Sev.OK }
        }
        val finalList = if (q.isEmpty()) byFilter
        else byFilter.filter {
            it.appName.lowercase().contains(q) ||
                it.packageName.lowercase().contains(q) ||
                it.evidence.lowercase().contains(q)
        }
        adapter.submit(finalList)
        tvEmpty.visibility = if (finalList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderSegments() {
        val ctx = context ?: return
        val flaggedCount = allRows.count { it.sev != Sev.OK }
        val cleanCount = allRows.count { it.sev == Sev.OK }

        tvSegAllCount.text = allRows.size.toString()
        tvSegFlaggedCount.text = flaggedCount.toString()
        tvSegCleanCount.text = cleanCount.toString()

        applySegmentStyle(segAll, tvSegAllLabel, tvSegAllCount, filter == Filter.ALL)
        applySegmentStyle(segFlagged, tvSegFlaggedLabel, tvSegFlaggedCount, filter == Filter.FLAGGED)
        applySegmentStyle(segClean, tvSegCleanLabel, tvSegCleanCount, filter == Filter.CLEAN)
    }

    private fun applySegmentStyle(
        seg: View, label: TextView, count: TextView, active: Boolean
    ) {
        seg.setBackgroundResource(
            if (active) R.drawable.bg_v4_apps_segment_active else 0
        )
        val color = ContextCompat.getColor(
            requireContext(),
            if (active) R.color.v4_fg0 else R.color.v4_fg2
        )
        label.setTextColor(color)
        count.setTextColor(color)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────────────────────────────────

    private inner class AppsAdapter : RecyclerView.Adapter<AppsVH>() {
        private val items = mutableListOf<AppRow>()

        fun submit(newItems: List<AppRow>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppsVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_apps_row, parent, false)
            // Add 8dp gap between cards via LayoutParams
            (v.layoutParams as? RecyclerView.LayoutParams)?.let {
                val gap = (8 * resources.displayMetrics.density).toInt()
                it.bottomMargin = gap
                v.layoutParams = it
            }
            return AppsVH(v)
        }

        override fun onBindViewHolder(holder: AppsVH, position: Int) =
            holder.bind(items[position])

        override fun getItemCount() = items.size
    }

    private inner class AppsVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        private val tvInitial: TextView = itemView.findViewById(R.id.tvAppInitial)
        private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvPill: TextView = itemView.findViewById(R.id.tvAppSevPill)
        private val tvEvidence: TextView = itemView.findViewById(R.id.tvAppEvidence)

        fun bind(row: AppRow) {
            val icon = try {
                itemView.context.packageManager.getApplicationIcon(row.packageName)
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                null
            }
            if (icon != null) {
                ivIcon.setImageDrawable(icon)
                ivIcon.visibility = View.VISIBLE
                tvInitial.visibility = View.GONE
            } else {
                ivIcon.setImageDrawable(null)
                ivIcon.visibility = View.GONE
                tvInitial.visibility = View.VISIBLE
                tvInitial.text = row.appName.firstOrNull()?.uppercase().orEmpty()
            }
            tvName.text = row.appName

            // Severity pill (only for non-ok)
            when (row.sev) {
                Sev.BAD -> {
                    tvPill.visibility = View.VISIBLE
                    tvPill.setText(R.string.apps_sev_flag)
                    tvPill.setBackgroundResource(R.drawable.bg_v4_perm_pill_warn)
                    tvPill.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.v4_bad)
                    )
                    tvPill.background.setTint(
                        ContextCompat.getColor(itemView.context, R.color.v4_bad_bg)
                    )
                }
                Sev.WARN -> {
                    tvPill.visibility = View.VISIBLE
                    tvPill.setText(R.string.apps_sev_review)
                    tvPill.setBackgroundResource(R.drawable.bg_v4_perm_pill_warn)
                    tvPill.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.v4_warn)
                    )
                }
                Sev.OK -> tvPill.visibility = View.GONE
            }

            tvEvidence.text = row.evidence
            tvEvidence.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    when (row.sev) {
                        Sev.BAD -> R.color.v4_bad
                        Sev.WARN -> R.color.v4_warn
                        Sev.OK -> R.color.v4_fg3
                    }
                )
            )

            itemView.setOnClickListener {
                val intent = Intent(itemView.context, AppDetailActivity::class.java)
                intent.putExtra(AppDetailActivity.EXTRA_PACKAGE_NAME, row.packageName)
                startActivity(intent)
            }
        }
    }

    companion object {
        private val CRITICAL_PERMS = setOf(
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
            "CAMERA", "RECORD_AUDIO",
            "READ_CONTACTS", "WRITE_CONTACTS",
            "READ_SMS", "RECEIVE_SMS", "SEND_SMS",
            "READ_CALL_LOG", "WRITE_CALL_LOG", "READ_PHONE_STATE",
            "READ_CALENDAR", "WRITE_CALENDAR",
            "BODY_SENSORS", "ACTIVITY_RECOGNITION",
            "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE",
        )
    }
}

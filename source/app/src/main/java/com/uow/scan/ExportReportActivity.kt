package com.uow.scan

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.uow.scan.util.CsvReportGenerator
import com.uow.scan.util.JsonReportGenerator
import com.uow.scan.util.PdfReportGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ExportReportActivity : AppCompatActivity() {

    private enum class Format(val mime: String) {
        PDF("application/pdf"),
        JSON("application/json"),
        CSV("text/csv")
    }

    private enum class Range(val days: Int?) {
        D7(7), D30(30), D90(90), ALL(null)
    }

    private lateinit var btnBack: View

    private lateinit var cardFormatPdf: LinearLayout
    private lateinit var cardFormatJson: LinearLayout
    private lateinit var cardFormatCsv: LinearLayout
    private lateinit var tvFormatPdfLabel: TextView
    private lateinit var tvFormatJsonLabel: TextView
    private lateinit var tvFormatCsvLabel: TextView

    private lateinit var segRange7d: View
    private lateinit var segRange30d: View
    private lateinit var segRange90d: View
    private lateinit var segRangeAll: View
    private lateinit var tvSegRange7d: TextView
    private lateinit var tvSegRange30d: TextView
    private lateinit var tvSegRange90d: TextView
    private lateinit var tvSegRangeAll: TextView

    private lateinit var swIncFindings: SwitchCompat
    private lateinit var swIncAlerts: SwitchCompat
    private lateinit var swIncSms: SwitchCompat
    private lateinit var swIncScans: SwitchCompat

    private lateinit var tvEstimatedSize: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView

    private lateinit var btnGenerate: MaterialButton
    private lateinit var btnEmail: MaterialButton
    private lateinit var btnViewHistory: MaterialButton

    private var format: Format = Format.PDF
    private var range: Range = Range.D30
    private var generatedReport: PdfReportGenerator.GeneratedReport? = null
    private var generatedFormat: Format? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export_report)

        bindViews()
        setupListeners()
        renderSelections()
        updateEstimatedSize()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    private fun bindViews() {
        val topBar = findViewById<View>(R.id.topBar)
        btnBack = topBar.findViewById(R.id.btnBack)
        topBar.findViewById<TextView>(R.id.tvTopBarTitle).setText(R.string.export_v4_title)

        cardFormatPdf = findViewById(R.id.cardFormatPdf)
        cardFormatJson = findViewById(R.id.cardFormatJson)
        cardFormatCsv = findViewById(R.id.cardFormatCsv)
        tvFormatPdfLabel = findViewById(R.id.tvFormatPdfLabel)
        tvFormatJsonLabel = findViewById(R.id.tvFormatJsonLabel)
        tvFormatCsvLabel = findViewById(R.id.tvFormatCsvLabel)

        segRange7d = findViewById(R.id.segRange7d)
        segRange30d = findViewById(R.id.segRange30d)
        segRange90d = findViewById(R.id.segRange90d)
        segRangeAll = findViewById(R.id.segRangeAll)
        tvSegRange7d = findViewById(R.id.tvSegRange7d)
        tvSegRange30d = findViewById(R.id.tvSegRange30d)
        tvSegRange90d = findViewById(R.id.tvSegRange90d)
        tvSegRangeAll = findViewById(R.id.tvSegRangeAll)

        swIncFindings = findViewById(R.id.swIncFindings)
        swIncAlerts = findViewById(R.id.swIncAlerts)
        swIncSms = findViewById(R.id.swIncSms)
        swIncScans = findViewById(R.id.swIncScans)

        tvEstimatedSize = findViewById(R.id.tvEstimatedSize)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)

        btnGenerate = findViewById(R.id.btnGenerate)
        btnEmail = findViewById(R.id.btnEmail)
        btnViewHistory = findViewById(R.id.btnViewHistory)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
        cardFormatPdf.setOnClickListener { selectFormat(Format.PDF) }
        cardFormatJson.setOnClickListener { selectFormat(Format.JSON) }
        cardFormatCsv.setOnClickListener { selectFormat(Format.CSV) }

        segRange7d.setOnClickListener { selectRange(Range.D7) }
        segRange30d.setOnClickListener { selectRange(Range.D30) }
        segRange90d.setOnClickListener { selectRange(Range.D90) }
        segRangeAll.setOnClickListener { selectRange(Range.ALL) }

        val include = { _: Any -> updateEstimatedSize() }
        swIncFindings.setOnCheckedChangeListener { _, _ -> include(Unit) }
        swIncAlerts.setOnCheckedChangeListener { _, _ -> include(Unit) }
        swIncSms.setOnCheckedChangeListener { _, _ -> include(Unit) }
        swIncScans.setOnCheckedChangeListener { _, _ -> include(Unit) }

        btnGenerate.setOnClickListener { generateReport() }
        btnEmail.setOnClickListener { emailReport() }
        btnViewHistory.setOnClickListener {
            startActivity(Intent(this, ReportHistoryActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Selection rendering
    // ─────────────────────────────────────────────────────────────────────

    private fun selectFormat(f: Format) {
        format = f
        renderSelections()
        updateEstimatedSize()
        // Force regeneration if format changed.
        if (generatedFormat != null && generatedFormat != f) {
            generatedReport = null
            tvStatus.visibility = View.GONE
        }
    }

    private fun selectRange(r: Range) {
        range = r
        renderSelections()
        updateEstimatedSize()
    }

    private fun renderSelections() {
        // Format cards
        styleFormatCard(cardFormatPdf, tvFormatPdfLabel, format == Format.PDF)
        styleFormatCard(cardFormatJson, tvFormatJsonLabel, format == Format.JSON)
        styleFormatCard(cardFormatCsv, tvFormatCsvLabel, format == Format.CSV)

        // Range segments
        styleRangeSeg(segRange7d, tvSegRange7d, range == Range.D7)
        styleRangeSeg(segRange30d, tvSegRange30d, range == Range.D30)
        styleRangeSeg(segRange90d, tvSegRange90d, range == Range.D90)
        styleRangeSeg(segRangeAll, tvSegRangeAll, range == Range.ALL)
    }

    private fun styleFormatCard(card: View, label: TextView, active: Boolean) {
        card.setBackgroundResource(
            if (active) R.drawable.bg_v4_format_card_active else R.drawable.bg_v4_surface
        )
        label.setTextColor(
            ContextCompat.getColor(this, if (active) R.color.v4_accent else R.color.v4_fg0)
        )
    }

    private fun styleRangeSeg(seg: View, label: TextView, active: Boolean) {
        seg.setBackgroundResource(if (active) R.drawable.bg_v4_apps_segment_active else 0)
        label.setTextColor(
            ContextCompat.getColor(this, if (active) R.color.v4_fg0 else R.color.v4_fg2)
        )
    }

    private fun currentFilters() = PdfReportGenerator.ReportFilters(
        rangeStartMs = range.days?.let {
            System.currentTimeMillis() - it.toLong() * 24L * 60 * 60 * 1000
        },
        includeFindings = swIncFindings.isChecked,
        includeAlerts = swIncAlerts.isChecked,
        includeSms = swIncSms.isChecked,
        includeScans = swIncScans.isChecked,
    )

    /**
     * Cheap rough estimate based on which sections are on and the format. Done
     * synchronously off DB queries so it stays responsive when toggles flip.
     */
    private fun updateEstimatedSize() {
        // Section counts (each "section on" adds a flat baseline + a per-format multiplier).
        val sectionsOn = listOf(
            swIncFindings.isChecked,
            swIncAlerts.isChecked,
            swIncSms.isChecked,
            swIncScans.isChecked,
        ).count { it }

        // Range factor: the larger the range, the more rows; rough multiplier.
        val rangeMultiplier = when (range) {
            Range.D7 -> 0.4f
            Range.D30 -> 1.0f
            Range.D90 -> 2.5f
            Range.ALL -> 4.0f
        }

        val baseKb = when (format) {
            Format.PDF -> 80f
            Format.JSON -> 12f
            Format.CSV -> 6f
        }
        val perSectionKb = when (format) {
            Format.PDF -> 70f
            Format.JSON -> 18f
            Format.CSV -> 10f
        }
        val sizeKb = (baseKb + sectionsOn * perSectionKb) * rangeMultiplier
        val sizeText = formatKb(sizeKb)

        val pages = if (format == Format.PDF) {
            // Each section ~3-6 PDF pages depending on rows; approximate 4.
            val pagesEst = (4 + sectionsOn * 4 * rangeMultiplier).toInt().coerceAtLeast(4)
            getString(R.string.export_v4_estimated_pages, sizeText, pagesEst)
        } else {
            sizeText
        }
        tvEstimatedSize.text = pages
    }

    private fun formatKb(kb: Float): String =
        if (kb >= 1024f) "%.1f MB".format(kb / 1024f) else "%d KB".format(kb.toInt())

    // ─────────────────────────────────────────────────────────────────────
    // Generation
    // ─────────────────────────────────────────────────────────────────────

    private fun generateReport() {
        val filters = currentFilters()
        if (!filters.includeFindings && !filters.includeAlerts &&
            !filters.includeSms && !filters.includeScans) {
            Toast.makeText(this, R.string.export_v4_err_no_sections, Toast.LENGTH_SHORT).show()
            return
        }

        val chosenFormat = format
        btnGenerate.isEnabled = false
        btnEmail.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.export_v4_status_gathering)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.v4_fg2))
        tvStatus.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    PdfReportGenerator.gatherData(this@ExportReportActivity, filters)
                }
                tvStatus.text = getString(R.string.export_v4_status_writing, chosenFormat.name)
                val report = withContext(Dispatchers.IO) {
                    when (chosenFormat) {
                        Format.PDF -> PdfReportGenerator.generate(this@ExportReportActivity, data)
                        Format.JSON -> JsonReportGenerator.generate(this@ExportReportActivity, data)
                        Format.CSV -> CsvReportGenerator.generate(this@ExportReportActivity, data)
                    }
                }
                generatedReport = report
                generatedFormat = chosenFormat
                progressBar.visibility = View.GONE
                tvStatus.text = if (report.publicUri != null) {
                    getString(R.string.export_v4_status_saved_downloads, report.displayName)
                } else {
                    getString(R.string.export_v4_status_saved_files, report.displayName)
                }
                tvStatus.setTextColor(ContextCompat.getColor(this@ExportReportActivity, R.color.v4_ok))
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                tvStatus.text = getString(R.string.export_v4_status_error, e.message.orEmpty())
                tvStatus.setTextColor(ContextCompat.getColor(this@ExportReportActivity, R.color.v4_bad))
                Toast.makeText(
                    this@ExportReportActivity,
                    R.string.export_v4_err_generate_failed,
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                btnGenerate.isEnabled = true
                btnEmail.isEnabled = true
            }
        }
    }

    private fun emailReport() {
        val report = generatedReport
        val fmt = generatedFormat
        if (report == null || fmt == null) {
            Toast.makeText(this, R.string.export_v4_err_generate_first, Toast.LENGTH_SHORT).show()
            return
        }
        val file: File = report.file
        val uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            file
        )
        val recipient = FirebaseAuth.getInstance().currentUser?.email
        val emailIntent = Intent(Intent.ACTION_SEND).apply {
            type = fmt.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_v4_email_subject))
            putExtra(Intent.EXTRA_TEXT, getString(R.string.export_v4_email_body))
            if (!recipient.isNullOrBlank()) {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(
            Intent.createChooser(emailIntent, getString(R.string.export_v4_email_chooser))
        )
    }
}

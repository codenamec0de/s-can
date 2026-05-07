package com.uow.scan.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.uow.scan.data.ScanDatabase
import com.uow.scan.data.dao.TopDataUser
import com.uow.scan.data.entity.AlertEntity
import com.uow.scan.data.entity.BreachResultEntity
import com.uow.scan.data.entity.DeviceCheckEntity
import com.uow.scan.data.entity.ScanResultEntity
import com.uow.scan.data.entity.SmsVerdictEntity
import com.uow.scan.data.entity.WeeklyStatsEntity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates the S'CAN security & privacy PDF report.
 *
 * Save location strategy:
 *  1. Primary: `<external-files>/Documents/SCAN Reports/` — always writable, no
 *     runtime permissions, survives app background, visible via the system Files
 *     app, and used by the in-app report history screen.
 *  2. Bonus (Android 10+): also inserted into `MediaStore.Downloads/SCAN Reports/`
 *     so the report shows up in the system Downloads folder and external apps.
 */
object PdfReportGenerator {

    // ─── Page geometry ────────────────────────────────────────────────────────
    private const val PAGE_WIDTH = 595           // A4 @ 72dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 44f
    private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
    private const val FOOTER_HEIGHT = 36f
    private const val REPORTS_SUBDIR = "SCAN Reports"

    // ─── Palette ──────────────────────────────────────────────────────────────
    private const val C_INK = "#0F172A"          // primary text
    private const val C_INK_SOFT = "#1E293B"     // subheadings
    private const val C_BODY = "#334155"         // body text
    private const val C_MUTED = "#64748B"        // caption / hint
    private const val C_HAIRLINE = "#E2E8F0"
    private const val C_SURFACE = "#F8FAFC"
    private const val C_SURFACE_BORDER = "#CBD5E1"
    private const val C_ACCENT = "#2563EB"       // brand blue
    private const val C_HIGH = "#DC2626"         // red
    private const val C_MEDIUM = "#D97706"       // amber
    private const val C_LOW = "#059669"          // green
    private const val C_HIGH_BG = "#FEE2E2"
    private const val C_MEDIUM_BG = "#FEF3C7"
    private const val C_LOW_BG = "#D1FAE5"
    private const val C_ACCENT_BG = "#DBEAFE"

    // ─── Data classes ─────────────────────────────────────────────────────────
    data class ReportData(
        val deviceCheck: DeviceCheckEntity?,
        val deviceCheckHistory: List<DeviceCheckEntity>,
        val scanResults: List<ScanResultEntity>,
        val breachResults: List<BreachResultEntity>,
        val alerts: List<AlertEntity>,
        val topDataUsers: List<TopDataUser>,
        val alertCount: Int,
        val highCount: Int,
        val mediumCount: Int,
        val lowCount: Int,
        val weeklyStats: List<WeeklyStatsEntity>,
        val smsVerdicts: List<SmsVerdictEntity>,
        val filters: ReportFilters
    )

    data class ReportFilters(
        val rangeStartMs: Long? = null,
        val includeFindings: Boolean = true,
        val includeAlerts: Boolean = true,
        val includeSms: Boolean = true,
        val includeScans: Boolean = false,
    ) {
        companion object {
            fun ofRangeDays(days: Int?): ReportFilters {
                val start = days?.let { System.currentTimeMillis() - it.toLong() * 24L * 60 * 60 * 1000 }
                return ReportFilters(rangeStartMs = start)
            }
        }
    }

    data class GeneratedReport(
        val file: File,
        val publicUri: Uri?,
        val displayName: String
    )

    // ─── Data gathering ───────────────────────────────────────────────────────
    suspend fun gatherData(
        context: Context,
        filters: ReportFilters = ReportFilters()
    ): ReportData {
        runCatching { WeeklyStatsRecorder.snapshotIfNeeded(context) }

        val db = ScanDatabase.getInstance(context)
        val rangeStart = filters.rangeStartMs

        val deviceCheck = db.deviceCheckDao().getLatest()
        val deviceCheckHistoryAll = db.deviceCheckDao().getAll()
        val deviceCheckHistory = if (rangeStart == null) {
            deviceCheckHistoryAll.takeLast(10)
        } else {
            deviceCheckHistoryAll.filter { it.checkedAt >= rangeStart }.takeLast(10)
        }

        val scanResults = db.scanResultDao().getAll()
            .let { if (rangeStart == null) it else it.filter { r -> r.scannedAt >= rangeStart } }
        val breachResults = db.breachResultDao().getAll()
            .let { if (rangeStart == null) it else it.filter { r -> r.checkedAt >= rangeStart } }
        val alertsAll = db.alertDao().getAll()
        val alerts = if (rangeStart == null) alertsAll else alertsAll.filter { it.timestamp >= rangeStart }
        val topDataUsers = db.alertDao().getTopDataUsers(10)
        val alertCount = alerts.size
        val high = scanResults.count { it.riskLevel == "HIGH" }
        val medium = scanResults.count { it.riskLevel == "MEDIUM" }
        val low = scanResults.count { it.riskLevel == "LOW" }
        val weeklyStats = db.weeklyStatsDao().getAll()
            .let { if (rangeStart == null) it else it.filter { w -> w.weekEndMs >= rangeStart } }
        val smsVerdicts = db.smsVerdictDao().getAll()
            .let { if (rangeStart == null) it else it.filter { v -> v.timestamp >= rangeStart } }

        return ReportData(
            deviceCheck = deviceCheck,
            deviceCheckHistory = deviceCheckHistory,
            scanResults = scanResults,
            breachResults = breachResults,
            alerts = alerts,
            topDataUsers = topDataUsers,
            alertCount = alertCount,
            highCount = high,
            mediumCount = medium,
            lowCount = low,
            weeklyStats = weeklyStats,
            smsVerdicts = smsVerdicts,
            filters = filters
        )
    }

    // ─── Main generate() ──────────────────────────────────────────────────────
    fun generate(context: Context, data: ReportData): GeneratedReport {
        val document = PdfDocument()
        val palette = Palette()
        val dateFormat = SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", Locale.getDefault())
        val dateOnlyFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        val now = Date()
        val nowStr = dateFormat.format(now)
        val deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

        val page = PageContext(document, palette)

        // ── Cover ────────────────────────────────────────────────────────────
        page.newPage(suppressFooter = true)
        drawCover(page, data, nowStr, deviceLabel)

        // ── Section 1 — Device Security Audit ────────────────────────────────
        page.newPage()
        drawSectionHeader(page, "01", "Device Security Audit")
        drawDeviceSecurity(page, data, dateFormat)

        // ── Section 2 — Security Score History (optional) ────────────────────
        if (data.filters.includeScans && data.deviceCheckHistory.isNotEmpty()) {
            page.ensureSpace(220f)
            drawSectionHeader(page, "02", "Security Score History")
            drawScoreHistory(page, data, dateOnlyFormat)
        }

        // ── Section 3 — Application Audit ────────────────────────────────────
        if (data.filters.includeFindings) {
            page.ensureSpace(240f)
            drawSectionHeader(page, "03", "Application Audit Summary")
            drawAppAuditSummary(page, data)

            page.ensureSpace(240f)
            drawSectionHeader(page, "04", "Permission Landscape")
            drawPermissionLandscape(page, data)
        }

        // ── Section 5 — Background Activity ──────────────────────────────────
        if (data.filters.includeAlerts) {
            page.ensureSpace(240f)
            drawSectionHeader(page, "05", "Background Activity Monitor")
            drawBackgroundActivity(page, data)

            if (data.weeklyStats.isNotEmpty()) {
                page.ensureSpace(220f)
                drawSectionHeader(page, "06", "Weekly Activity History")
                drawWeeklyActivityHistory(page, data, dateOnlyFormat)
            }
        }

        // ── Section 7 — SMS Verdicts ─────────────────────────────────────────
        if (data.filters.includeSms && data.smsVerdicts.isNotEmpty()) {
            page.ensureSpace(220f)
            drawSectionHeader(page, "07", "SMS Scam Verdicts")
            drawSmsVerdicts(page, data, dateFormat)
        }

        // ── Sections 8/9/10 — Risk-tier app details ──────────────────────────
        val highRiskApps = if (data.filters.includeFindings)
            data.scanResults.filter { it.riskLevel == "HIGH" } else emptyList()
        if (highRiskApps.isNotEmpty()) {
            page.newPage()
            drawSectionHeader(page, "08", "High-Risk Applications · ${highRiskApps.size}")
            drawHighRiskApps(page, highRiskApps, dateOnlyFormat)
        }

        val mediumRiskApps = if (data.filters.includeFindings)
            data.scanResults.filter { it.riskLevel == "MEDIUM" } else emptyList()
        if (mediumRiskApps.isNotEmpty()) {
            page.ensureSpace(220f)
            drawSectionHeader(page, "09", "Medium-Risk Applications · ${mediumRiskApps.size}")
            drawMediumRiskApps(page, mediumRiskApps)
        }

        val lowRiskApps = if (data.filters.includeFindings)
            data.scanResults.filter { it.riskLevel == "LOW" } else emptyList()
        if (lowRiskApps.isNotEmpty()) {
            page.ensureSpace(160f)
            drawSectionHeader(page, "10", "Low-Risk Applications · ${lowRiskApps.size}")
            drawLowRiskSummary(page, lowRiskApps)
        }

        // ── Section 11 — Breach Report ───────────────────────────────────────
        if (data.filters.includeFindings && data.breachResults.isNotEmpty()) {
            page.newPage()
            drawSectionHeader(page, "11", "Data Breach Report")
            drawBreachReport(page, data)
        }

        // ── Section 12 — Recommendations ─────────────────────────────────────
        page.ensureSpace(280f)
        drawSectionHeader(page, "12", "Recommendations")
        drawRecommendations(page, data)

        page.finishCurrentPage()

        // ── Persist ─────────────────────────────────────────────────────────
        val fileName = "SCAN_Report_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(now)}.pdf"
        val reportsDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            REPORTS_SUBDIR
        ).apply { mkdirs() }
        val file = File(reportsDir, fileName)
        FileOutputStream(file).use { out -> document.writeTo(out) }
        document.close()

        val publicUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + REPORTS_SUBDIR
                    )
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                uri?.also { u ->
                    context.contentResolver.openOutputStream(u)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                }
            } catch (_: Exception) {
                null
            }
        } else null

        return GeneratedReport(file = file, publicUri = publicUri, displayName = fileName)
    }

    // ─── Section drawers ──────────────────────────────────────────────────────

    private fun drawCover(p: PageContext, data: ReportData, nowStr: String, deviceLabel: String) {
        var c = p.canvas
        val pal = p.palette

        // Big accent rule at top
        val accentRule = RectF(MARGIN, MARGIN + 6f, MARGIN + 56f, MARGIN + 12f)
        c.drawRect(accentRule, pal.fill(C_ACCENT))
        p.y = MARGIN + 36f

        c.drawText(s("S'CAN"), MARGIN, p.y, pal.cover_brand)
        p.y += 18f
        c.drawText(s("Security & Privacy Report"), MARGIN, p.y + 28f, pal.cover_title)
        p.y += 64f

        c.drawText(s("Generated"), MARGIN, p.y, pal.label_small)
        c.drawText(s(nowStr), MARGIN + 80f, p.y, pal.body)
        p.y += 16f
        c.drawText(s("Device"), MARGIN, p.y, pal.label_small)
        c.drawText(s(deviceLabel), MARGIN + 80f, p.y, pal.body)
        p.y += 16f
        c.drawText(s("Range"), MARGIN, p.y, pal.label_small)
        c.drawText(s(rangeLabel(data.filters)), MARGIN + 80f, p.y, pal.body)
        p.y += 26f

        c.drawLine(MARGIN, p.y, PAGE_WIDTH - MARGIN, p.y, pal.hairline)
        p.y += 28f

        // AT A GLANCE label
        c.drawText(s("AT A GLANCE"), MARGIN, p.y, pal.eyebrow)
        p.y += 18f

        // 4-up stat cards
        val cardGap = 10f
        val cardW = (CONTENT_WIDTH - cardGap * 3f) / 4f
        val cardH = 96f
        val score = data.deviceCheck?.score ?: -1
        val scoreText = if (score >= 0) "$score" else "—"
        val scoreCaption = if (score >= 0) "/100 · ${gradeFor(score)}" else "Run a scan"
        val (riskLabel, riskColor) = overallRiskLabel(data)

        statCard(p, MARGIN + (cardW + cardGap) * 0, p.y, cardW, cardH,
            "Device score", scoreText, scoreCaption, paintForScoreColor(score), pal)
        statCard(p, MARGIN + (cardW + cardGap) * 1, p.y, cardW, cardH,
            "Apps audited", "${data.scanResults.size}",
            "${data.highCount}H · ${data.mediumCount}M · ${data.lowCount}L",
            C_INK, pal)
        statCard(p, MARGIN + (cardW + cardGap) * 2, p.y, cardW, cardH,
            "BG alerts", "${data.alertCount}",
            "monitored window",
            if (data.alertCount > 0) C_MEDIUM else C_LOW, pal)
        statCard(p, MARGIN + (cardW + cardGap) * 3, p.y, cardW, cardH,
            "Breaches", "${data.breachResults.size}",
            "${data.breachResults.map { it.email }.distinct().size} email(s)",
            if (data.breachResults.isNotEmpty()) C_HIGH else C_LOW, pal)
        p.y += cardH + 26f

        // Overall risk pill
        c.drawText(s("OVERALL RISK"), MARGIN, p.y, pal.eyebrow)
        p.y += 16f
        drawPill(c, MARGIN, p.y - 14f, riskLabel, riskColor, pal)
        p.y += 22f

        // Severity legend
        p.y += 18f
        c.drawText(s("SEVERITY LEGEND"), MARGIN, p.y, pal.eyebrow)
        p.y += 16f
        drawPill(c, MARGIN, p.y - 14f, "HIGH", C_HIGH, pal)
        c.drawText(s("Action recommended within days"),
            MARGIN + 70f, p.y, pal.body)
        p.y += 22f
        drawPill(c, MARGIN, p.y - 14f, "MEDIUM", C_MEDIUM, pal)
        c.drawText(s("Review at your next convenience"),
            MARGIN + 70f, p.y, pal.body)
        p.y += 22f
        drawPill(c, MARGIN, p.y - 14f, "LOW", C_LOW, pal)
        c.drawText(s("Informational — no action required"),
            MARGIN + 70f, p.y, pal.body)
        p.y += 26f

        // Confidential strip near bottom
        val stripY = PAGE_HEIGHT - MARGIN - 30f
        c.drawLine(MARGIN, stripY, PAGE_WIDTH - MARGIN, stripY, pal.hairline)
        c.drawText(s("Confidential — generated for the device owner."),
            MARGIN, stripY + 16f, pal.small_muted)
        val rightLabel = s("S'CAN Android · ${Build.VERSION.RELEASE}")
        c.drawText(
            rightLabel,
            PAGE_WIDTH - MARGIN - pal.small_muted.measureText(rightLabel),
            stripY + 16f,
            pal.small_muted
        )
    }

    private fun drawSectionHeader(p: PageContext, num: String, title: String) {
        var c = p.canvas
        val pal = p.palette
        // Breathing room above each section
        p.y += 14f
        // Number tile
        val tileW = 38f
        val tileH = 22f
        val rect = RectF(MARGIN, p.y - tileH + 2f, MARGIN + tileW, p.y + 2f)
        c.drawRoundRect(rect, 4f, 4f, pal.fill(C_ACCENT_BG))
        c.drawText(s(num), MARGIN + 7f, p.y - 4f, pal.tile_number)
        // Title
        c.drawText(s(title), MARGIN + tileW + 10f, p.y - 4f, pal.section_title)
        p.y += 10f
        c.drawLine(MARGIN, p.y, PAGE_WIDTH - MARGIN, p.y, pal.hairline)
        p.y += 18f
    }

    private fun drawDeviceSecurity(p: PageContext, data: ReportData, dateFormat: SimpleDateFormat) {
        var c = p.canvas
        val pal = p.palette
        val check = data.deviceCheck
        if (check == null) {
            val emptyLines = wrapText(
                s("No security checks have been performed yet. Open the Home tab and run a device scan to populate this section."),
                pal.body, CONTENT_WIDTH
            )
            for (line in emptyLines) {
                p.ensureSpace(14f)
                c = p.canvas
                c.drawText(line, MARGIN, p.y, pal.body)
                p.y += 14f
            }
            p.y += 18f
            return
        }

        val checks = listOf(
            "Screen Lock" to check.screenLockEnabled,
            "Biometric Authentication" to check.biometricEnrolled,
            "Disk Encryption" to check.diskEncrypted,
            "Operating System Up to Date" to check.osUpToDate,
            "Developer Options Disabled" to check.developerOptionsOff,
            "USB Debugging Disabled" to check.usbDebuggingOff,
            "Install From Unknown Sources Disabled" to check.unknownSourcesOff
        )
        val passed = checks.count { it.second }

        c.drawText(
            s("$passed of ${checks.size} checks passed · Score ${check.score}/100 (${gradeFor(check.score)})"),
            MARGIN, p.y, pal.body_bold
        )
        p.y += 20f

        for ((index, pair) in checks.withIndex()) {
            val (name, ok) = pair
            p.ensureSpace(22f)
            c = p.canvas
            // alternate row stripe
            if (index % 2 == 0) {
                val stripe = RectF(MARGIN, p.y - 14f, PAGE_WIDTH - MARGIN, p.y + 6f)
                c.drawRoundRect(stripe, 4f, 4f, pal.fill(C_SURFACE))
            }
            val icon = if (ok) "OK" else "FAIL"
            drawPill(c, MARGIN + 8f, p.y - 13f, icon, if (ok) C_LOW else C_HIGH, pal,
                paddingX = 7f)
            c.drawText(s(name), MARGIN + 64f, p.y, pal.body)
            val statusText = if (ok) "Passed" else "Action required"
            c.drawText(
                s(statusText),
                PAGE_WIDTH - MARGIN - pal.small_muted.measureText(statusText),
                p.y,
                pal.small_muted
            )
            p.y += 22f
        }

        p.y += 4f
        c.drawText(
            s("Last checked: ${dateFormat.format(Date(check.checkedAt))}"),
            MARGIN, p.y, pal.small_muted
        )
        p.y += 22f
    }

    private fun drawScoreHistory(p: PageContext, data: ReportData, dateFormat: SimpleDateFormat) {
        var c = p.canvas
        val pal = p.palette
        c.drawText(
            s("Last ${data.deviceCheckHistory.size} scans (oldest → newest)"),
            MARGIN, p.y, pal.small_muted
        )
        p.y += 18f

        val col1 = MARGIN + 8f
        val col2 = MARGIN + 200f
        val col3 = MARGIN + 270f
        val col4 = MARGIN + 360f
        c.drawText(s("Date"), col1, p.y, pal.subheading)
        c.drawText(s("Score"), col2, p.y, pal.subheading)
        c.drawText(s("Grade"), col3, p.y, pal.subheading)
        c.drawText(s("Change"), col4, p.y, pal.subheading)
        p.y += 8f
        c.drawLine(MARGIN, p.y, PAGE_WIDTH - MARGIN, p.y, pal.hairline)
        p.y += 16f

        var prev: Int? = null
        for ((index, ch) in data.deviceCheckHistory.withIndex()) {
            p.ensureSpace(18f)
            c = p.canvas
            if (index % 2 == 0) {
                val stripe = RectF(MARGIN, p.y - 13f, PAGE_WIDTH - MARGIN, p.y + 4f)
                c.drawRoundRect(stripe, 4f, 4f, pal.fill(C_SURFACE))
            }
            c.drawText(s(dateFormat.format(Date(ch.checkedAt))), col1, p.y, pal.body)
            c.drawText(s("${ch.score}"), col2, p.y, paintForScore(ch.score, pal))
            c.drawText(s(gradeFor(ch.score)), col3, p.y, pal.body)
            val delta = prev?.let { ch.score - it }
            val deltaText = when {
                delta == null -> "—"
                delta > 0 -> "+$delta"
                delta < 0 -> "$delta"
                else -> "0"
            }
            val deltaPaint = when {
                delta == null || delta == 0 -> pal.body
                delta > 0 -> pal.low
                else -> pal.high
            }
            c.drawText(s(deltaText), col4, p.y, deltaPaint)
            prev = ch.score
            p.y += 18f
        }
        p.y += 14f
    }

    private fun drawAppAuditSummary(p: PageContext, data: ReportData) {
        var c = p.canvas
        val pal = p.palette
        val total = data.scanResults.size
        c.drawText(s("Total applications audited: $total"), MARGIN, p.y, pal.body_bold)
        p.y += 18f
        val systemCount = data.scanResults.count { it.isSystemApp }
        val userCount = total - systemCount
        c.drawText(s("· User-installed: $userCount"), MARGIN + 8f, p.y, pal.body); p.y += 14f
        c.drawText(s("· System: $systemCount"), MARGIN + 8f, p.y, pal.body); p.y += 22f

        // Bars
        val labelWidth = 110f
        val countWidth = 50f
        val barX = MARGIN + labelWidth + countWidth
        val maxBarWidth = (PAGE_WIDTH - MARGIN - barX - 12f).coerceAtLeast(40f)
        val maxCount = maxOf(data.highCount, data.mediumCount, data.lowCount, 1)

        fun bar(label: String, count: Int, accentColor: String) {
            p.ensureSpace(22f)
            c = p.canvas
            c.drawText(s(label), MARGIN, p.y, pal.body)
            c.drawText(s("$count"), MARGIN + labelWidth, p.y, pal.solidColor(accentColor, bold = true))
            val bw = (count.toFloat() / maxCount) * maxBarWidth
            val barRect = RectF(barX, p.y - 9f, barX + maxBarWidth, p.y - 9f + 9f)
            c.drawRoundRect(barRect, 4f, 4f, pal.fill(C_HAIRLINE))
            if (bw > 0f) {
                val fillRect = RectF(barX, p.y - 9f, barX + bw, p.y - 9f + 9f)
                c.drawRoundRect(fillRect, 4f, 4f, pal.fill(accentColor))
            }
            p.y += 20f
        }

        bar("High risk", data.highCount, C_HIGH)
        bar("Medium risk", data.mediumCount, C_MEDIUM)
        bar("Low risk", data.lowCount, C_LOW)
        p.y += 8f
    }

    private fun drawPermissionLandscape(p: PageContext, data: ReportData) {
        var c = p.canvas
        val pal = p.palette
        val permCounts = mutableMapOf<String, Int>()
        for (app in data.scanResults) {
            val perms = app.permissions.split(",").map { it.trim() }.filter { it.isNotBlank() }
            for (perm in perms) permCounts[perm] = (permCounts[perm] ?: 0) + 1
        }
        if (permCounts.isEmpty()) {
            c.drawText(s("No permission data available."), MARGIN, p.y, pal.body); p.y += 22f; return
        }
        c.drawText(
            s("Top permissions requested across ${data.scanResults.size} apps:"),
            MARGIN, p.y, pal.body
        )
        p.y += 18f

        val top = permCounts.entries.sortedByDescending { it.value }.take(10)
        val maxCount = top.firstOrNull()?.value ?: 1

        for ((index, entry) in top.withIndex()) {
            val (perm, count) = entry
            p.ensureSpace(22f)
            c = p.canvas
            if (index % 2 == 0) {
                val stripe = RectF(MARGIN, p.y - 14f, PAGE_WIDTH - MARGIN, p.y + 6f)
                c.drawRoundRect(stripe, 4f, 4f, pal.fill(C_SURFACE))
            }
            c.drawText(s(perm), MARGIN + 8f, p.y, pal.body)
            val countText = "$count apps"
            c.drawText(
                s(countText),
                PAGE_WIDTH - MARGIN - pal.accent.measureText(countText),
                p.y,
                pal.accent
            )
            // Mini bar
            val barY = p.y + 7f
            val barStartX = MARGIN + 8f
            val barEndX = PAGE_WIDTH - MARGIN - pal.accent.measureText(countText) - 12f
            val barMaxW = (barEndX - barStartX).coerceAtLeast(0f)
            val track = RectF(barStartX, barY, barStartX + barMaxW, barY + 3f)
            c.drawRoundRect(track, 2f, 2f, pal.fill(C_HAIRLINE))
            val fill = RectF(barStartX, barY, barStartX + (count.toFloat() / maxCount) * barMaxW, barY + 3f)
            c.drawRoundRect(fill, 2f, 2f, pal.fill(C_ACCENT))
            p.y += 22f
        }
        p.y += 8f
    }

    private fun drawBackgroundActivity(p: PageContext, data: ReportData) {
        var c = p.canvas
        val pal = p.palette
        c.drawText(s("Total background permission alerts: ${data.alertCount}"),
            MARGIN, p.y, pal.body_bold)
        p.y += 20f

        if (data.topDataUsers.isEmpty()) {
            c.drawText(
                s("No background data usage recorded yet. Either no apps have triggered an alert, or background monitoring is still warming up."),
                MARGIN, p.y, pal.body
            )
            p.y += 22f
            return
        }

        c.drawText(s("Top background data consumers:"), MARGIN, p.y, pal.body)
        p.y += 18f

        for ((i, user) in data.topDataUsers.take(10).withIndex()) {
            p.ensureSpace(36f)
            c = p.canvas
            if (i % 2 == 0) {
                val stripe = RectF(MARGIN, p.y - 14f, PAGE_WIDTH - MARGIN, p.y + 22f)
                c.drawRoundRect(stripe, 4f, 4f, pal.fill(C_SURFACE))
            }
            // rank circle
            val cx = MARGIN + 14f
            val cy = p.y - 4f
            c.drawCircle(cx, cy, 9f, pal.fill(C_ACCENT_BG))
            val rankText = s("${i + 1}")
            c.drawText(rankText, cx - pal.tile_number.measureText(rankText) / 2f, cy + 4f, pal.tile_number)

            c.drawText(s(user.appName.ifBlank { "(unknown)" }), MARGIN + 36f, p.y, pal.body_bold)
            val sizeText = formatBytes(user.totalData)
            c.drawText(
                s(sizeText),
                PAGE_WIDTH - MARGIN - pal.accent.measureText(sizeText),
                p.y,
                pal.accent
            )
            p.y += 14f
            c.drawText(s(user.packageName.ifBlank { "—" }), MARGIN + 36f, p.y, pal.small_muted)
            p.y += 22f
        }
        p.y += 6f
    }

    private fun drawWeeklyActivityHistory(
        p: PageContext, data: ReportData, dateFormat: SimpleDateFormat
    ) {
        var c = p.canvas
        val pal = p.palette
        c.drawText(
            s("Snapshot of the Alerts page metrics for each completed week. The live counters reset every Monday — values below are preserved here."),
            MARGIN, p.y, pal.small_muted
        )
        p.y += 22f

        val col1 = MARGIN + 8f
        val col2 = MARGIN + 220f
        val col3 = MARGIN + 290f
        val col4 = MARGIN + 380f
        c.drawText(s("Week"), col1, p.y, pal.subheading)
        c.drawText(s("Total"), col2, p.y, pal.subheading)
        c.drawText(s("Critical"), col3, p.y, pal.subheading)
        c.drawText(s("Patterns"), col4, p.y, pal.subheading)
        p.y += 8f
        c.drawLine(MARGIN, p.y, PAGE_WIDTH - MARGIN, p.y, pal.hairline)
        p.y += 16f

        for ((i, w) in data.weeklyStats.withIndex()) {
            p.ensureSpace(20f)
            c = p.canvas
            if (i % 2 == 0) {
                val stripe = RectF(MARGIN, p.y - 13f, PAGE_WIDTH - MARGIN, p.y + 6f)
                c.drawRoundRect(stripe, 4f, 4f, pal.fill(C_SURFACE))
            }
            val range = "${dateFormat.format(Date(w.weekStartMs))} – ${dateFormat.format(Date(w.weekEndMs))}"
            c.drawText(s(range), col1, p.y, pal.body)
            c.drawText(s("${w.totalAlerts}"), col2, p.y, pal.body)
            val critPaint = if (w.criticalAlerts > 0) pal.high else pal.body
            c.drawText(s("${w.criticalAlerts}"), col3, p.y, critPaint)
            c.drawText(s("${w.patternCount}"), col4, p.y, pal.body)
            p.y += 18f
        }
        p.y += 14f
    }

    private fun drawSmsVerdicts(
        p: PageContext, data: ReportData, dateFormat: SimpleDateFormat
    ) {
        var c = p.canvas
        val pal = p.palette
        val total = data.smsVerdicts.size
        val scams = data.smsVerdicts.count { it.verdict.equals("SCAM", ignoreCase = true) }
        val suspicious = data.smsVerdicts.count { it.verdict.equals("SUSPICIOUS", ignoreCase = true) }
        c.drawText(
            s("Classified $total messages — $scams scam, $suspicious suspicious."),
            MARGIN, p.y, pal.body_bold
        )
        p.y += 20f

        for (v in data.smsVerdicts.take(40)) {
            p.ensureSpace(46f)
            c = p.canvas
            val sevColor = when (v.verdict.uppercase()) {
                "SCAM" -> C_HIGH
                "SUSPICIOUS" -> C_MEDIUM
                else -> C_LOW
            }
            drawPill(c, MARGIN, p.y - 13f, v.verdict.uppercase(), sevColor, pal)
            val sender = if (v.sender.isBlank()) "(unknown)" else v.sender
            c.drawText(
                s("$sender · ${dateFormat.format(Date(v.timestamp))}"),
                MARGIN + 96f, p.y, pal.small_muted
            )
            p.y += 14f
            val truncated = v.messageBody.replace('\n', ' ').take(120)
                .let { if (v.messageBody.length > 120) "$it…" else it }
            // Wrap one row of body if needed
            val lines = wrapText(s(truncated), pal.body, CONTENT_WIDTH - 8f)
            for (line in lines.take(2)) {
                p.ensureSpace(14f)
                c = p.canvas
                c.drawText(line, MARGIN + 8f, p.y, pal.body)
                p.y += 13f
            }
            p.y += 8f
        }
        if (data.smsVerdicts.size > 40) {
            c.drawText(
                s("+ ${data.smsVerdicts.size - 40} more verdicts not shown"),
                MARGIN, p.y, pal.small_muted
            )
            p.y += 14f
        }
        p.y += 6f
    }

    private fun drawHighRiskApps(
        p: PageContext, apps: List<ScanResultEntity>, dateFormat: SimpleDateFormat
    ) {
        var c = p.canvas
        val pal = p.palette
        c.drawText(
            s("Apps requesting many or sensitive permissions — review each entry."),
            MARGIN, p.y, pal.small_muted
        )
        p.y += 22f

        for (app in apps) {
            p.ensureSpace(110f)
            c = p.canvas
            // Card background
            val cardTop = p.y - 14f
            val perms = app.permissions.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val titleH = 16f
            val metaH = 14f
            val permsHeader = if (perms.isNotEmpty()) 16f else 0f
            val permLines = if (perms.isNotEmpty()) {
                wrapText(s(perms.joinToString(", ")), pal.body, CONTENT_WIDTH - 24f)
            } else emptyList()
            val permLinesH = permLines.size * 13f
            val approxCardH = titleH + metaH * 3 + permsHeader + permLinesH + 18f
            val cardRect = RectF(MARGIN, cardTop, PAGE_WIDTH - MARGIN, cardTop + approxCardH)
            c.drawRoundRect(cardRect, 6f, 6f, pal.fill(C_SURFACE))
            c.drawRoundRect(cardRect, 6f, 6f, pal.surfaceBorder)

            // Header line
            c.drawText(s(app.appName), MARGIN + 12f, p.y + 2f, pal.body_bold_accent)
            drawPill(c, PAGE_WIDTH - MARGIN - 56f, p.y - 11f, "HIGH", C_HIGH, pal)
            p.y += titleH + 2f
            c.drawText(s(app.packageName), MARGIN + 12f, p.y, pal.small_muted)
            p.y += metaH

            c.drawText(
                s("Version ${app.versionName} · code ${app.versionCode}"),
                MARGIN + 12f, p.y, pal.body
            )
            p.y += metaH

            c.drawText(
                s("Installed ${dateFormat.format(Date(app.installedDate))} · Updated ${dateFormat.format(Date(app.lastUpdated))}"),
                MARGIN + 12f, p.y, pal.small_muted
            )
            p.y += metaH

            if (perms.isNotEmpty()) {
                c.drawText(s("Permissions (${perms.size}):"), MARGIN + 12f, p.y, pal.body_bold)
                p.y += 14f
                for (line in permLines) {
                    p.ensureSpace(14f)
                    c = p.canvas
                    c.drawText(line, MARGIN + 20f, p.y, pal.body)
                    p.y += 13f
                }
            }
            p.y += 12f
        }
    }

    private fun drawMediumRiskApps(p: PageContext, apps: List<ScanResultEntity>) {
        var c = p.canvas
        val pal = p.palette
        for ((i, app) in apps.withIndex()) {
            p.ensureSpace(20f)
            c = p.canvas
            if (i % 2 == 0) {
                val stripe = RectF(MARGIN, p.y - 14f, PAGE_WIDTH - MARGIN, p.y + 6f)
                c.drawRoundRect(stripe, 4f, 4f, pal.fill(C_SURFACE))
            }
            val permCount = app.permissions.split(",").count { it.isNotBlank() }
            c.drawText(s("· ${app.appName}"), MARGIN + 8f, p.y, pal.body)
            val detail = "$permCount perms · ${app.packageName}"
            c.drawText(
                s(detail),
                PAGE_WIDTH - MARGIN - pal.small_muted.measureText(detail),
                p.y,
                pal.small_muted
            )
            p.y += 18f
        }
        p.y += 10f
    }

    private fun drawLowRiskSummary(p: PageContext, apps: List<ScanResultEntity>) {
        var c = p.canvas
        val pal = p.palette
        c.drawText(
            s("These apps request few or no dangerous permissions."),
            MARGIN, p.y, pal.body
        )
        p.y += 18f

        val sample = apps.take(15).joinToString(", ") { it.appName }
        val more = if (apps.size > 15) " … and ${apps.size - 15} more." else ""
        val lines = wrapText(s(sample + more), pal.body, CONTENT_WIDTH - 8f)
        for (line in lines) {
            p.ensureSpace(14f)
            c = p.canvas
            c.drawText(line, MARGIN + 8f, p.y, pal.body)
            p.y += 13f
        }
        p.y += 12f
    }

    private fun drawBreachReport(p: PageContext, data: ReportData) {
        var c = p.canvas
        val pal = p.palette
        val emails = data.breachResults.map { it.email }.distinct()
        c.drawText(
            s("${data.breachResults.size} breach(es) found across ${emails.size} email(s)."),
            MARGIN, p.y, pal.body_bold
        )
        p.y += 22f

        for (email in emails) {
            val breaches = data.breachResults.filter { it.email == email }
                .sortedByDescending { it.breachDate }

            p.ensureSpace(40f)

            c = p.canvas
            c.drawText(s(email), MARGIN, p.y, pal.body_bold_accent)
            val rightLabel = "${breaches.size} breach(es)"
            c.drawText(
                s(rightLabel),
                PAGE_WIDTH - MARGIN - pal.high.measureText(rightLabel),
                p.y,
                pal.high
            )
            p.y += 18f

            for (breach in breaches) {
                p.ensureSpace(70f)
                c = p.canvas
                val sevColor = when (breach.severity.uppercase()) {
                    "HIGH" -> C_HIGH
                    "MEDIUM" -> C_MEDIUM
                    else -> C_LOW
                }
                c.drawText(s("• ${breach.breachName}"), MARGIN + 8f, p.y, pal.body_bold)
                drawPill(c, PAGE_WIDTH - MARGIN - 70f, p.y - 11f, breach.severity.uppercase(), sevColor, pal)
                p.y += 14f
                c.drawText(s("Date: ${breach.breachDate}"), MARGIN + 20f, p.y, pal.small_muted)
                p.y += 12f
                c.drawText(s("Exposed: ${breach.dataExposed}"), MARGIN + 20f, p.y, pal.small_muted)
                p.y += 12f
                val descLines = wrapText(s(breach.description), pal.small_muted, CONTENT_WIDTH - 24f)
                for (line in descLines.take(3)) {
                    p.ensureSpace(12f)
                    c = p.canvas
                    c.drawText(line, MARGIN + 20f, p.y, pal.small_muted)
                    p.y += 11f
                }
                p.y += 10f
            }
            p.y += 6f
            c.drawLine(MARGIN, p.y, PAGE_WIDTH - MARGIN, p.y, pal.hairline)
            p.y += 14f
        }
    }

    private fun drawRecommendations(p: PageContext, data: ReportData) {
        var c = p.canvas
        val pal = p.palette
        val recommendations = buildRecommendations(data)
        if (recommendations.isEmpty()) {
            c.drawText(s("No specific recommendations — your device is in good shape."),
                MARGIN, p.y, pal.low)
            p.y += 22f
            return
        }
        for ((i, rec) in recommendations.withIndex()) {
            p.ensureSpace(40f)
            c = p.canvas
            c.drawText(s("${i + 1}. ${rec.first}"), MARGIN, p.y, pal.body_bold)
            p.y += 14f
            val lines = wrapText(s(rec.second), pal.body, CONTENT_WIDTH - 16f)
            for (line in lines) {
                p.ensureSpace(13f)
                c = p.canvas
                c.drawText(line, MARGIN + 16f, p.y, pal.body)
                p.y += 12f
            }
            p.y += 12f
        }
    }

    // ─── UI primitives ────────────────────────────────────────────────────────

    private fun statCard(
        p: PageContext, x: Float, y: Float, w: Float, h: Float,
        label: String, value: String, caption: String, valueColor: String, pal: Palette
    ) {
        var c = p.canvas
        val rect = RectF(x, y, x + w, y + h)
        c.drawRoundRect(rect, 8f, 8f, pal.fill(C_SURFACE))
        c.drawRoundRect(rect, 8f, 8f, pal.surfaceBorder)
        c.drawText(s(label.uppercase()), x + 12f, y + 22f, pal.eyebrow)
        c.drawText(s(value), x + 12f, y + 58f, pal.solidColor(valueColor, big = true))
        // Caption can wrap to two lines if needed
        val capLines = wrapText(s(caption), pal.small_muted, w - 20f)
        var cy = y + 78f
        for (line in capLines.take(2)) {
            c.drawText(line, x + 12f, cy, pal.small_muted)
            cy += 11f
        }
    }

    private fun drawPill(
        c: Canvas, x: Float, y: Float, label: String, color: String, pal: Palette,
        paddingX: Float = 10f
    ) {
        val text = s(label)
        val textWidth = pal.pill.measureText(text)
        val rect = RectF(x, y, x + textWidth + paddingX * 2f, y + 18f)
        c.drawRoundRect(rect, 9f, 9f, pal.fill(pillBg(color)))
        c.drawText(text, x + paddingX, y + 13f, pal.pillText(color))
    }

    private fun pillBg(color: String): String = when (color) {
        C_HIGH -> C_HIGH_BG
        C_MEDIUM -> C_MEDIUM_BG
        C_LOW -> C_LOW_BG
        C_ACCENT -> C_ACCENT_BG
        else -> C_SURFACE
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Sanitize a string before passing it to Canvas.drawText. Strips characters
     * that have crashed Android's PDF text shaper on certain devices (Samsung
     * One UI / Android 14+): supplementary code points (emoji, surrogate pairs),
     * BOM/zero-width marks, and control characters except tab.
     */
    private fun s(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            val cp = if (Character.isHighSurrogate(ch) && i + 1 < input.length &&
                Character.isLowSurrogate(input[i + 1])
            ) {
                val full = Character.toCodePoint(ch, input[i + 1])
                i += 2
                full
            } else {
                i += 1
                ch.code
            }
            // Strip non-BMP entirely — Android PDF text shaper SIGSEGV vector.
            if (cp > 0xFFFF) {
                out.append('?')
                continue
            }
            // Strip control chars (keep tab); strip zero-width / BOM-like marks.
            if (cp < 0x20 && cp != 0x09) continue
            if (cp == 0x7F) continue
            if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF) continue
            out.append(cp.toChar())
        }
        return out.toString()
    }

    private fun rangeLabel(filters: ReportFilters): String {
        val ms = filters.rangeStartMs ?: return "All time"
        val days = ((System.currentTimeMillis() - ms) / (24L * 60 * 60 * 1000)).toInt()
        return "Last $days day${if (days == 1) "" else "s"}"
    }

    private fun gradeFor(score: Int): String = when {
        score < 0 -> "Unknown"
        score >= 90 -> "Excellent"
        score >= 70 -> "Good"
        score >= 50 -> "Fair"
        else -> "At Risk"
    }

    private fun paintForScore(score: Int, pal: Palette): Paint = when {
        score < 0 -> pal.body
        score >= 90 -> pal.low
        score >= 70 -> pal.accent
        score >= 50 -> pal.medium
        else -> pal.high
    }

    private fun paintForScoreColor(score: Int): String = when {
        score < 0 -> C_BODY
        score >= 90 -> C_LOW
        score >= 70 -> C_ACCENT
        score >= 50 -> C_MEDIUM
        else -> C_HIGH
    }

    private fun overallRiskLabel(data: ReportData): Pair<String, String> {
        val score = data.deviceCheck?.score ?: 0
        val hasBreach = data.breachResults.isNotEmpty()
        val highApps = data.highCount
        return when {
            score in 1..49 || highApps >= 10 -> "HIGH" to C_HIGH
            score < 70 || highApps >= 4 || hasBreach -> "MEDIUM" to C_MEDIUM
            else -> "LOW" to C_LOW
        }
    }

    private fun buildRecommendations(data: ReportData): List<Pair<String, String>> {
        val recs = mutableListOf<Pair<String, String>>()
        val check = data.deviceCheck
        if (check != null) {
            if (!check.screenLockEnabled) recs += "Enable Screen Lock" to
                "Your device does not have a screen lock configured. Set a PIN, pattern, or password in Settings > Security to prevent unauthorized access if your phone is lost or stolen."
            if (!check.biometricEnrolled) recs += "Enroll Biometric Authentication" to
                "Enrolling a fingerprint or face unlocks the device faster without weakening security, and is required by many banking apps. Add a biometric in Settings > Biometrics."
            if (!check.diskEncrypted) recs += "Verify Disk Encryption" to
                "Full-disk encryption protects your data at rest. Most modern Android devices enable this by default — if yours is reporting as unencrypted, investigate in Settings > Security."
            if (!check.osUpToDate) recs += "Install Pending OS Updates" to
                "Security patches address known vulnerabilities and are time-sensitive. Go to Settings > Software update and install any pending updates."
            if (!check.developerOptionsOff) recs += "Disable Developer Options" to
                "Developer Options expose internal toggles that can weaken security. If you are not actively developing, disable them in Settings > Developer options."
            if (!check.usbDebuggingOff) recs += "Turn Off USB Debugging" to
                "USB debugging grants any connected computer extensive control over your device. Turn it off when you are not actively debugging."
            if (!check.unknownSourcesOff) recs += "Restrict Unknown Source Installs" to
                "Allowing installs from unknown sources is the primary vector for sideloaded malware. Revoke this permission for any app that does not strictly need it."
        }
        if (data.highCount >= 3) recs += "Review High-Risk Applications" to
            "${data.highCount} app(s) were classified as high-risk due to the number and sensitivity of permissions they hold. Open the Audit tab and consider revoking permissions or uninstalling apps you no longer use."
        if (data.alertCount > 0) recs += "Investigate Background Activity" to
            "S'CAN detected ${data.alertCount} instance(s) of apps transmitting data while running in the background with sensitive permissions granted. Review the Alerts tab to identify which apps are consuming data unexpectedly."
        if (data.breachResults.isNotEmpty()) recs += "Change Breached Account Passwords" to
            "Your email(s) appear in ${data.breachResults.size} known breach(es). Change the affected passwords immediately, do not reuse them across sites, and enable two-factor authentication where supported."
        if (recs.isEmpty()) recs += "Maintain Current Posture" to
            "Your device passes all standard security checks. Continue to install OS updates promptly, review app permissions when they are first granted, and run a fresh S'CAN audit monthly."
        return recs
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""
        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) <= maxWidth) currentLine = testLine
            else {
                if (currentLine.isNotEmpty()) lines.add(currentLine)
                currentLine = word
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine)
        return lines
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    // ─── Palette (built per generate() to avoid shared static Paint state) ────

    private class Palette {
        private val sansBold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        private val sans = Typeface.SANS_SERIF
        private val mono = Typeface.MONOSPACE

        val cover_brand = makeText(C_INK, 22f, sansBold)
        val cover_title = makeText(C_INK, 28f, sansBold)
        val section_title = makeText(C_INK, 16f, sansBold)
        val tile_number = makeText(C_ACCENT, 11f, sansBold)
        val eyebrow = Paint().apply {
            color = Color.parseColor(C_MUTED)
            textSize = 9f
            isAntiAlias = true
            typeface = sansBold
            letterSpacing = 0.08f
        }
        val label_small = Paint().apply {
            color = Color.parseColor(C_MUTED)
            textSize = 10f
            isAntiAlias = true
            typeface = sansBold
            letterSpacing = 0.05f
        }
        val subheading = makeText(C_INK_SOFT, 11f, sansBold)
        val body = makeText(C_BODY, 11f, sans)
        val body_bold = makeText(C_INK_SOFT, 11f, sansBold)
        val body_bold_accent = makeText(C_ACCENT, 11f, sansBold)
        val small_muted = makeText(C_MUTED, 9f, sans)
        val accent = makeText(C_ACCENT, 11f, sansBold)
        val high = makeText(C_HIGH, 11f, sansBold)
        val medium = makeText(C_MEDIUM, 11f, sansBold)
        val low = makeText(C_LOW, 11f, sansBold)
        val pill = Paint().apply {
            color = Color.WHITE
            textSize = 9f
            isAntiAlias = true
            typeface = sansBold
            letterSpacing = 0.12f
        }
        val hairline = Paint().apply {
            color = Color.parseColor(C_HAIRLINE)
            strokeWidth = 1f
            isAntiAlias = true
        }
        val surfaceBorder = Paint().apply {
            color = Color.parseColor(C_SURFACE_BORDER)
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            isAntiAlias = true
        }
        val footer = makeText(C_MUTED, 8f, sans)

        fun fill(hex: String): Paint = Paint().apply {
            color = Color.parseColor(hex)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        fun solidColor(hex: String, bold: Boolean = false, big: Boolean = false): Paint =
            makeText(hex, if (big) 30f else 11f, if (bold || big) sansBold else sans)

        fun pillText(color: String): Paint = Paint().apply {
            this.color = Color.parseColor(color)
            textSize = 9f
            isAntiAlias = true
            typeface = sansBold
            letterSpacing = 0.12f
        }

        private fun makeText(color: String, size: Float, tf: Typeface): Paint = Paint().apply {
            this.color = Color.parseColor(color)
            this.textSize = size
            this.isAntiAlias = true
            this.typeface = tf
        }

        @Suppress("unused") val monoLabel = makeText(C_INK, 10f, mono)
    }

    // ─── Page lifecycle ──────────────────────────────────────────────────────

    private class PageContext(val document: PdfDocument, val palette: Palette) {
        var pageNumber = 0
        private var currentPage: PdfDocument.Page? = null
        private var suppressFooter = false
        lateinit var canvas: Canvas
        var y: Float = MARGIN

        fun newPage(suppressFooter: Boolean = false) {
            finishCurrentPage()
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            val page = document.startPage(info)
            currentPage = page
            canvas = page.canvas
            this.suppressFooter = suppressFooter
            y = MARGIN + 22f
        }

        fun ensureSpace(needed: Float) {
            if (currentPage == null) {
                newPage()
                return
            }
            if (y + needed > PAGE_HEIGHT - FOOTER_HEIGHT - MARGIN / 2f) {
                newPage()
            }
        }

        fun finishCurrentPage() {
            val pageRef = currentPage ?: return
            if (!suppressFooter) drawFooter()
            document.finishPage(pageRef)
            currentPage = null
        }

        private fun drawFooter() {
            val footerY = PAGE_HEIGHT - 22f
            canvas.drawLine(MARGIN, footerY - 14f, PAGE_WIDTH - MARGIN, footerY - 14f, palette.hairline)
            canvas.drawText("S'CAN Security Report · Confidential", MARGIN, footerY, palette.footer)
            val pageText = "Page $pageNumber"
            canvas.drawText(
                pageText,
                PAGE_WIDTH - MARGIN - palette.footer.measureText(pageText),
                footerY,
                palette.footer
            )
        }
    }
}

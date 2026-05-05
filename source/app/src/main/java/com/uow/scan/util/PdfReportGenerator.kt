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
 * Generates a multi-page PDF security report and saves it to a persistent,
 * user-visible location.
 *
 * Save location strategy:
 *  1. Primary: `getExternalFilesDir(DIRECTORY_DOCUMENTS)/SCAN Reports/` - always
 *     writable, no runtime permissions, survives app background/kill, and is
 *     visible to the user via the system Files app. This is the source of
 *     truth used by the in-app report history screen.
 *  2. Bonus (Android 10+): a copy is also inserted into `MediaStore.Downloads/SCAN Reports/`
 *     so the report shows up directly in the system Downloads folder and in
 *     apps like Google Drive, Gmail attachments, etc.
 *
 * The previous implementation wrote to `cacheDir`, which Android's storage
 * manager is free to wipe at any time - users reported being able to share
 * the PDF immediately (because the file still existed during that process)
 * but then being unable to find it afterwards. The new location is permanent.
 */
object PdfReportGenerator {

    private const val PAGE_WIDTH = 595   // A4 at 72dpi
    private const val PAGE_HEIGHT = 842  // A4 at 72dpi
    private const val MARGIN = 40f
    private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
    private const val REPORTS_SUBDIR = "SCAN Reports"

    // -------------------------------------------------------------------------
    // Paint palette
    // -------------------------------------------------------------------------

    private val titlePaint = Paint().apply {
        color = Color.parseColor("#0F172A")
        textSize = 26f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val subtitlePaint = Paint().apply {
        color = Color.parseColor("#475569")
        textSize = 13f
        isAntiAlias = true
    }

    private val sectionPaint = Paint().apply {
        color = Color.parseColor("#0F172A")
        textSize = 17f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val subheadingPaint = Paint().apply {
        color = Color.parseColor("#1E293B")
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val bodyPaint = Paint().apply {
        color = Color.parseColor("#334155")
        textSize = 11f
        isAntiAlias = true
    }

    private val bodyBoldPaint = Paint().apply {
        color = Color.parseColor("#1E293B")
        textSize = 11f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val smallPaint = Paint().apply {
        color = Color.parseColor("#64748B")
        textSize = 9f
        isAntiAlias = true
    }

    private val accentPaint = Paint().apply {
        color = Color.parseColor("#2B8CEE")
        textSize = 11f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val highPaint = Paint().apply {
        color = Color.parseColor("#EF4444")
        textSize = 11f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val mediumPaint = Paint().apply {
        color = Color.parseColor("#F59E0B")
        textSize = 11f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val lowPaint = Paint().apply {
        color = Color.parseColor("#10B981")
        textSize = 11f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        color = Color.parseColor("#E2E8F0")
        strokeWidth = 1f
    }

    private val boxFillPaint = Paint().apply {
        color = Color.parseColor("#F1F5F9")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val boxBorderPaint = Paint().apply {
        color = Color.parseColor("#CBD5E1")
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }

    // -------------------------------------------------------------------------
    // Data classes
    // -------------------------------------------------------------------------

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

    /**
     * Controls what the report includes:
     *  - [rangeStartMs] = null → all time; else only entries with timestamp ≥ this value.
     *  - [includeFindings] toggles app-audit / risk / breach sections.
     *  - [includeAlerts] toggles background-activity + weekly-history sections.
     *  - [includeSms] toggles SMS verdicts section.
     *  - [includeScans] toggles security-score history section.
     *
     *  Cover, executive summary, device-security audit, and recommendations are always
     *  included — they're the report's spine.
     */
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

    /**
     * Result returned by [generate]. [file] is always populated (app-private external
     * files dir). [publicUri] is populated on Android 10+ when the MediaStore insert
     * into public Downloads succeeded - useful when you want to show "saved to Downloads"
     * or grant the user a direct content:// link.
     */
    data class GeneratedReport(
        val file: File,
        val publicUri: Uri?,
        val displayName: String
    )

    // -------------------------------------------------------------------------
    // Data gathering
    // -------------------------------------------------------------------------

    suspend fun gatherData(
        context: Context,
        filters: ReportFilters = ReportFilters()
    ): ReportData {
        // Make sure all completed weeks are persisted before we read the snapshot table.
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

    // -------------------------------------------------------------------------
    // Main generate() - composes pages and persists to disk
    // -------------------------------------------------------------------------

    fun generate(context: Context, data: ReportData): GeneratedReport {
        val document = PdfDocument()
        val dateFormat = SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", Locale.getDefault())
        val dateOnlyFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        val now = Date()
        val nowStr = dateFormat.format(now)
        val deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL} - Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

        val pageCtx = PageContext(document)

        // ------- Section 1: Cover + Executive Summary -------
        pageCtx.newPage()
        drawCoverAndSummary(pageCtx, data, nowStr, deviceLabel)

        // ------- Section 2: Device Security Audit -------
        pageCtx.ensureSpace(250f)
        drawDeviceSecurity(pageCtx, data, dateFormat)

        // ------- Section 3: Security Score History -------
        if (data.filters.includeScans && data.deviceCheckHistory.isNotEmpty()) {
            pageCtx.ensureSpace(220f)
            drawScoreHistory(pageCtx, data, dateOnlyFormat)
        }

        // ------- Section 4: App Audit Summary -------
        if (data.filters.includeFindings) {
            pageCtx.ensureSpace(220f)
            drawAppAuditSummary(pageCtx, data)

            // ------- Section 5: Permission Landscape -------
            pageCtx.ensureSpace(220f)
            drawPermissionLandscape(pageCtx, data)
        }

        // ------- Section 6: Background Activity Monitor -------
        if (data.filters.includeAlerts) {
            pageCtx.ensureSpace(220f)
            drawBackgroundActivity(pageCtx, data)

            // ------- Section 6.5: Weekly Activity History -------
            if (data.weeklyStats.isNotEmpty()) {
                pageCtx.ensureSpace(220f)
                drawWeeklyActivityHistory(pageCtx, data, dateOnlyFormat)
            }
        }

        // ------- Section 6.7: SMS Verdicts -------
        if (data.filters.includeSms && data.smsVerdicts.isNotEmpty()) {
            pageCtx.ensureSpace(220f)
            drawSmsVerdicts(pageCtx, data, dateFormat)
        }

        // ------- Section 7: High-Risk Apps Detail -------
        val highRiskApps = if (data.filters.includeFindings)
            data.scanResults.filter { it.riskLevel == "HIGH" } else emptyList()
        if (highRiskApps.isNotEmpty()) {
            pageCtx.newPage()
            drawHighRiskApps(pageCtx, highRiskApps, dateOnlyFormat)
        }

        // ------- Section 8: Medium-Risk Apps Summary -------
        val mediumRiskApps = if (data.filters.includeFindings)
            data.scanResults.filter { it.riskLevel == "MEDIUM" } else emptyList()
        if (mediumRiskApps.isNotEmpty()) {
            pageCtx.ensureSpace(200f)
            drawMediumRiskApps(pageCtx, mediumRiskApps)
        }

        // ------- Section 9: Low-Risk Summary -------
        val lowRiskApps = if (data.filters.includeFindings)
            data.scanResults.filter { it.riskLevel == "LOW" } else emptyList()
        if (lowRiskApps.isNotEmpty()) {
            pageCtx.ensureSpace(140f)
            drawLowRiskSummary(pageCtx, lowRiskApps)
        }

        // ------- Section 10: Data Breach Report -------
        if (data.filters.includeFindings && data.breachResults.isNotEmpty()) {
            pageCtx.newPage()
            drawBreachReport(pageCtx, data)
        }

        // ------- Section 11: Recommendations -------
        pageCtx.ensureSpace(280f)
        drawRecommendations(pageCtx, data)

        pageCtx.finishCurrentPage()

        // ------- Persist -------
        val fileName = "SCAN_Report_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(now)}.pdf"

        // Primary: app's external files dir - always accessible, no permissions,
        // visible to the user via the system Files app.
        val reportsDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            REPORTS_SUBDIR
        ).apply { mkdirs() }
        val file = File(reportsDir, fileName)
        FileOutputStream(file).use { out ->
            document.writeTo(out)
        }
        document.close()

        // Bonus: on Android 10+, also copy to public Downloads/SCAN Reports via MediaStore
        // so the user can find it in the system Downloads app and share from anywhere.
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
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                )
                uri?.also { u ->
                    context.contentResolver.openOutputStream(u)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                }
            } catch (e: Exception) {
                // Non-fatal - user can still access the file via the app's Files path
                null
            }
        } else null

        return GeneratedReport(file = file, publicUri = publicUri, displayName = fileName)
    }

    // -------------------------------------------------------------------------
    // Section drawers
    // -------------------------------------------------------------------------

    private fun drawCoverAndSummary(ctx: PageContext, data: ReportData, nowStr: String, deviceLabel: String) {
        val canvas = ctx.canvas
        ctx.y += 20f

        // Title block
        canvas.drawText("S'CAN", MARGIN, ctx.y + 28f, titlePaint)
        ctx.y += 34f
        canvas.drawText("Security & Privacy Report", MARGIN, ctx.y + 18f, sectionPaint)
        ctx.y += 28f
        canvas.drawText("Generated $nowStr", MARGIN, ctx.y, subtitlePaint)
        ctx.y += 14f
        canvas.drawText(deviceLabel, MARGIN, ctx.y, subtitlePaint)
        ctx.y += 16f
        canvas.drawLine(MARGIN, ctx.y, PAGE_WIDTH - MARGIN, ctx.y, linePaint)
        ctx.y += 22f

        // Executive summary box
        canvas.drawText("Executive Summary", MARGIN, ctx.y, sectionPaint)
        ctx.y += 18f

        val boxTop = ctx.y
        val boxHeight = 130f
        val boxRect = RectF(MARGIN, boxTop, PAGE_WIDTH - MARGIN, boxTop + boxHeight)
        canvas.drawRoundRect(boxRect, 8f, 8f, boxFillPaint)
        canvas.drawRoundRect(boxRect, 8f, 8f, boxBorderPaint)

        val boxPad = MARGIN + 16f
        var by = boxTop + 22f

        val score = data.deviceCheck?.score ?: -1
        val scoreText = if (score >= 0) "$score/100" else "N/A"
        val grade = gradeFor(score)
        val gradePaint = paintForScore(score)

        canvas.drawText("Device Security Score:", boxPad, by, bodyBoldPaint)
        canvas.drawText(
            scoreText,
            boxPad + bodyBoldPaint.measureText("Device Security Score: "),
            by,
            gradePaint
        )
        canvas.drawText(
            "  ($grade)",
            boxPad + bodyBoldPaint.measureText("Device Security Score: ") + gradePaint.measureText(scoreText),
            by,
            bodyPaint
        )
        by += 18f

        canvas.drawText("Apps Scanned:", boxPad, by, bodyBoldPaint)
        canvas.drawText(
            "${data.scanResults.size} total - ${data.highCount} high, ${data.mediumCount} medium, ${data.lowCount} low",
            boxPad + bodyBoldPaint.measureText("Apps Scanned: "),
            by,
            bodyPaint
        )
        by += 18f

        canvas.drawText("Background Alerts:", boxPad, by, bodyBoldPaint)
        canvas.drawText(
            "${data.alertCount} triggered in the last monitoring window",
            boxPad + bodyBoldPaint.measureText("Background Alerts: "),
            by,
            bodyPaint
        )
        by += 18f

        canvas.drawText("Breach Exposure:", boxPad, by, bodyBoldPaint)
        val breachCount = data.breachResults.size
        val breachEmails = data.breachResults.map { it.email }.distinct().size
        canvas.drawText(
            if (breachCount == 0) "No breaches found for checked emails"
            else "$breachCount breach(es) across $breachEmails email account(s)",
            boxPad + bodyBoldPaint.measureText("Breach Exposure: "),
            by,
            if (breachCount > 0) highPaint else lowPaint
        )
        by += 18f

        val overallRisk = overallRiskRating(data)
        canvas.drawText("Overall Risk Rating:", boxPad, by, bodyBoldPaint)
        canvas.drawText(
            overallRisk.first,
            boxPad + bodyBoldPaint.measureText("Overall Risk Rating: "),
            by,
            overallRisk.second
        )

        ctx.y = boxTop + boxHeight + 20f
    }

    private fun drawDeviceSecurity(ctx: PageContext, data: ReportData, dateFormat: SimpleDateFormat) {
        val canvas = ctx.canvas
        canvas.drawText("1. Device Security Audit", MARGIN, ctx.y, sectionPaint)
        ctx.y += 20f

        val check = data.deviceCheck
        if (check == null) {
            canvas.drawText(
                "No security checks have been performed yet. Open the Home tab and run a device scan to populate this section.",
                MARGIN, ctx.y, bodyPaint
            )
            ctx.y += 20f
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

        canvas.drawText("$passed of ${checks.size} checks passed · Score ${check.score}/100 (${gradeFor(check.score)})",
            MARGIN, ctx.y, bodyBoldPaint)
        ctx.y += 18f

        for ((name, ok) in checks) {
            ctx.ensureSpace(18f)
            val icon = if (ok) "\u2713" else "\u2717"
            val iconPaint = if (ok) lowPaint else highPaint
            canvas.drawText(icon, MARGIN + 8f, ctx.y, iconPaint)
            canvas.drawText(name, MARGIN + 28f, ctx.y, bodyPaint)
            val status = if (ok) "OK" else "ACTION REQUIRED"
            canvas.drawText(
                status,
                PAGE_WIDTH - MARGIN - smallPaint.measureText(status),
                ctx.y,
                if (ok) lowPaint else highPaint
            )
            ctx.y += 16f
        }

        ctx.y += 6f
        canvas.drawText(
            "Last checked: ${dateFormat.format(Date(check.checkedAt))}",
            MARGIN, ctx.y, smallPaint
        )
        ctx.y += 22f
    }

    private fun drawScoreHistory(ctx: PageContext, data: ReportData, dateFormat: SimpleDateFormat) {
        val canvas = ctx.canvas
        canvas.drawText("2. Security Score History", MARGIN, ctx.y, sectionPaint)
        ctx.y += 20f

        canvas.drawText("Last ${data.deviceCheckHistory.size} scans (oldest → newest)", MARGIN, ctx.y, smallPaint)
        ctx.y += 16f

        // Simple table: Date | Score | Grade | Delta
        val col1 = MARGIN + 8f
        val col2 = MARGIN + 180f
        val col3 = MARGIN + 240f
        val col4 = MARGIN + 330f

        canvas.drawText("Date", col1, ctx.y, subheadingPaint)
        canvas.drawText("Score", col2, ctx.y, subheadingPaint)
        canvas.drawText("Grade", col3, ctx.y, subheadingPaint)
        canvas.drawText("Change", col4, ctx.y, subheadingPaint)
        ctx.y += 8f
        canvas.drawLine(MARGIN, ctx.y, PAGE_WIDTH - MARGIN, ctx.y, linePaint)
        ctx.y += 14f

        var prev: Int? = null
        for (c in data.deviceCheckHistory) {
            ctx.ensureSpace(16f)
            canvas.drawText(dateFormat.format(Date(c.checkedAt)), col1, ctx.y, bodyPaint)
            canvas.drawText("${c.score}", col2, ctx.y, paintForScore(c.score))
            canvas.drawText(gradeFor(c.score), col3, ctx.y, bodyPaint)
            val delta = prev?.let { c.score - it }
            val deltaText = when {
                delta == null -> "-"
                delta > 0 -> "+$delta"
                delta < 0 -> "$delta"
                else -> "0"
            }
            val deltaPaint = when {
                delta == null || delta == 0 -> bodyPaint
                delta > 0 -> lowPaint
                else -> highPaint
            }
            canvas.drawText(deltaText, col4, ctx.y, deltaPaint)
            prev = c.score
            ctx.y += 15f
        }
        ctx.y += 14f
    }

    private fun drawAppAuditSummary(ctx: PageContext, data: ReportData) {
        val canvas = ctx.canvas
        canvas.drawText("3. Application Audit Summary", MARGIN, ctx.y, sectionPaint)
        ctx.y += 20f

        val total = data.scanResults.size
        canvas.drawText("Total applications scanned: $total", MARGIN, ctx.y, bodyBoldPaint)
        ctx.y += 16f

        val systemCount = data.scanResults.count { it.isSystemApp }
        val userCount = total - systemCount
        canvas.drawText("  · User-installed: $userCount", MARGIN + 8f, ctx.y, bodyPaint)
        ctx.y += 14f
        canvas.drawText("  · System: $systemCount", MARGIN + 8f, ctx.y, bodyPaint)
        ctx.y += 18f

        // Risk breakdown as text bars
        val maxBarWidth = CONTENT_WIDTH - 180f
        val max = maxOf(data.highCount, data.mediumCount, data.lowCount, 1)

        fun drawBar(label: String, count: Int, paint: Paint) {
            ctx.ensureSpace(18f)
            canvas.drawText(label, MARGIN, ctx.y, bodyPaint)
            canvas.drawText("$count", MARGIN + 100f, ctx.y, paint)
            val barX = MARGIN + 140f
            val barWidth = (count.toFloat() / max) * maxBarWidth
            val barHeight = 10f
            val barRect = RectF(barX, ctx.y - 9f, barX + barWidth, ctx.y - 9f + barHeight)
            val fill = Paint(paint).apply { style = Paint.Style.FILL }
            canvas.drawRoundRect(barRect, 3f, 3f, fill)
            ctx.y += 18f
        }

        drawBar("High Risk", data.highCount, highPaint)
        drawBar("Medium Risk", data.mediumCount, mediumPaint)
        drawBar("Low Risk", data.lowCount, lowPaint)
        ctx.y += 8f
    }

    private fun drawPermissionLandscape(ctx: PageContext, data: ReportData) {
        val canvas = ctx.canvas
        canvas.drawText("4. Permission Landscape", MARGIN, ctx.y, sectionPaint)
        ctx.y += 20f

        // Aggregate permission counts across all scanned apps
        val permCounts = mutableMapOf<String, Int>()
        for (app in data.scanResults) {
            val perms = app.permissions.split(",").map { it.trim() }.filter { it.isNotBlank() }
            for (p in perms) {
                permCounts[p] = (permCounts[p] ?: 0) + 1
            }
        }

        if (permCounts.isEmpty()) {
            canvas.drawText("No permission data available.", MARGIN, ctx.y, bodyPaint)
            ctx.y += 20f
            return
        }

        canvas.drawText(
            "Top permissions requested across ${data.scanResults.size} apps:",
            MARGIN, ctx.y, bodyPaint
        )
        ctx.y += 16f

        val top = permCounts.entries.sortedByDescending { it.value }.take(10)
        val maxCount = top.firstOrNull()?.value ?: 1

        for ((perm, count) in top) {
            ctx.ensureSpace(16f)
            canvas.drawText(perm, MARGIN + 8f, ctx.y, bodyPaint)
            val countText = "$count apps"
            canvas.drawText(
                countText,
                PAGE_WIDTH - MARGIN - smallPaint.measureText(countText),
                ctx.y,
                accentPaint
            )
            // Thin progress bar
            val barY = ctx.y + 3f
            val barStartX = MARGIN + 8f + bodyPaint.measureText(perm) + 12f
            val barEndX = PAGE_WIDTH - MARGIN - smallPaint.measureText(countText) - 12f
            val barMaxWidth = (barEndX - barStartX).coerceAtLeast(0f)
            val barWidth = (count.toFloat() / maxCount) * barMaxWidth
            if (barMaxWidth > 20f) {
                val barRect = RectF(barStartX, barY - 3f, barStartX + barWidth, barY + 1f)
                val fill = Paint().apply {
                    color = Color.parseColor("#93C5FD")
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }
                canvas.drawRoundRect(barRect, 2f, 2f, fill)
            }
            ctx.y += 15f
        }
        ctx.y += 8f
    }

    private fun drawBackgroundActivity(ctx: PageContext, data: ReportData) {
        val canvas = ctx.canvas
        canvas.drawText("5. Background Activity Monitor", MARGIN, ctx.y, sectionPaint)
        ctx.y += 20f

        canvas.drawText(
            "Total background permission alerts: ${data.alertCount}",
            MARGIN, ctx.y, bodyBoldPaint
        )
        ctx.y += 18f

        if (data.topDataUsers.isEmpty()) {
            canvas.drawText(
                "No background data usage recorded. Either no apps have triggered an alert yet, or background monitoring is still collecting data.",
                MARGIN, ctx.y, bodyPaint
            )
            ctx.y += 20f
            return
        }

        canvas.drawText("Top background data consumers:", MARGIN, ctx.y, bodyPaint)
        ctx.y += 16f

        for ((i, user) in data.topDataUsers.take(10).withIndex()) {
            ctx.ensureSpace(16f)
            canvas.drawText("${i + 1}. ${user.appName}", MARGIN + 8f, ctx.y, bodyPaint)
            val sizeText = formatBytes(user.totalData)
            canvas.drawText(
                sizeText,
                PAGE_WIDTH - MARGIN - accentPaint.measureText(sizeText),
                ctx.y,
                accentPaint
            )
            ctx.y += 14f
            canvas.drawText(user.packageName, MARGIN + 20f, ctx.y, smallPaint)
            ctx.y += 14f
        }
        ctx.y += 6f
    }

    private fun drawWeeklyActivityHistory(
        ctx: PageContext,
        data: ReportData,
        dateFormat: SimpleDateFormat
    ) {
        val canvas = ctx.canvas
        canvas.drawText("5b. Weekly Activity History", MARGIN, ctx.y, sectionPaint)
        ctx.y += 20f

        canvas.drawText(
            "Snapshot of the Alerts page metrics for each completed week. " +
                "The live counters reset every Monday — the values below are preserved here.",
            MARGIN, ctx.y, smallPaint
        )
        ctx.y += 22f

        val col1 = MARGIN + 8f
        val col2 = MARGIN + 200f
        val col3 = MARGIN + 280f
        val col4 = MARGIN + 360f

        canvas.drawText("Week", col1, ctx.y, subheadingPaint)
        canvas.drawText("Total", col2, ctx.y, subheadingPaint)
        canvas.drawText("Critical", col3, ctx.y, subheadingPaint)
        canvas.drawText("Patterns", col4, ctx.y, subheadingPaint)
        ctx.y += 8f
        canvas.drawLine(MARGIN, ctx.y, PAGE_WIDTH - MARGIN, ctx.y, linePaint)
        ctx.y += 14f

        for (w in data.weeklyStats) {
            ctx.ensureSpace(16f)
            val range = "${dateFormat.format(Date(w.weekStartMs))} – ${dateFormat.format(Date(w.weekEndMs))}"
            canvas.drawText(range, col1, ctx.y, bodyPaint)
            canvas.drawText("${w.totalAlerts}", col2, ctx.y, bodyPaint)
            val critPaint = if (w.criticalAlerts > 0) highPaint else bodyPaint
            canvas.drawText("${w.criticalAlerts}", col3, ctx.y, critPaint)
            canvas.drawText("${w.patternCount}", col4, ctx.y, bodyPaint)
            ctx.y += 15f
        }
        ctx.y += 14f
    }

    private fun drawSmsVerdicts(
        ctx: PageContext,
        data: ReportData,
        dateFormat: SimpleDateFormat
    ) {
        val canvas = ctx.canvas
        canvas.drawText("5c. SMS Scam Verdicts", MARGIN, ctx.y, sectionPaint)
        ctx.y += 20f

        val total = data.smsVerdicts.size
        val scams = data.smsVerdicts.count { it.verdict.equals("SCAM", ignoreCase = true) }
        val suspicious = data.smsVerdicts.count { it.verdict.equals("SUSPICIOUS", ignoreCase = true) }
        canvas.drawText(
            "Classified $total messages — $scams scam, $suspicious suspicious.",
            MARGIN, ctx.y, bodyBoldPaint
        )
        ctx.y += 18f

        for (v in data.smsVerdicts.take(40)) {
            ctx.ensureSpace(34f)
            val verdictPaint = when (v.verdict.uppercase()) {
                "SCAM" -> highPaint
                "SUSPICIOUS" -> mediumPaint
                else -> lowPaint
            }
            canvas.drawText(v.verdict.uppercase(), MARGIN, ctx.y, verdictPaint)
            val sender = v.sender.ifBlank { "(unknown)" }
            canvas.drawText(
                "  $sender · ${dateFormat.format(Date(v.timestamp))}",
                MARGIN + 70f, ctx.y, smallPaint
            )
            ctx.y += 13f
            val truncated = v.messageBody.replace('\n', ' ').take(110)
                .let { if (v.messageBody.length > 110) "$it…" else it }
            canvas.drawText(truncated, MARGIN + 8f, ctx.y, bodyPaint)
            ctx.y += 17f
        }
        if (data.smsVerdicts.size > 40) {
            canvas.drawText(
                "+ ${data.smsVerdicts.size - 40} more verdicts not shown",
                MARGIN, ctx.y, smallPaint
            )
            ctx.y += 14f
        }
        ctx.y += 6f
    }

    private fun drawHighRiskApps(
        ctx: PageContext,
        apps: List<ScanResultEntity>,
        dateFormat: SimpleDateFormat
    ) {
        val canvas = ctx.canvas
        canvas.drawText("6. High-Risk Applications (${apps.size})", MARGIN, ctx.y, sectionPaint)
        ctx.y += 8f
        canvas.drawText(
            "Apps requesting multiple dangerous permissions - review each entry.",
            MARGIN, ctx.y + 12f, smallPaint
        )
        ctx.y += 26f

        for (app in apps) {
            ctx.ensureSpace(90f)
            // Name + system badge
            canvas.drawText(app.appName, MARGIN, ctx.y, accentPaint)
            if (app.isSystemApp) {
                val badge = "[SYSTEM]"
                canvas.drawText(
                    badge,
                    MARGIN + accentPaint.measureText(app.appName) + 8f,
                    ctx.y,
                    smallPaint
                )
            }
            ctx.y += 14f
            canvas.drawText(app.packageName, MARGIN + 8f, ctx.y, smallPaint)
            ctx.y += 14f

            canvas.drawText(
                "Version ${app.versionName} (code ${app.versionCode})",
                MARGIN + 8f, ctx.y, bodyPaint
            )
            ctx.y += 14f

            canvas.drawText(
                "Installed ${dateFormat.format(Date(app.installedDate))} · Updated ${dateFormat.format(Date(app.lastUpdated))}",
                MARGIN + 8f, ctx.y, smallPaint
            )
            ctx.y += 16f

            val perms = app.permissions.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (perms.isNotEmpty()) {
                canvas.drawText("Permissions (${perms.size}):", MARGIN + 8f, ctx.y, bodyBoldPaint)
                ctx.y += 14f
                val permText = perms.joinToString(", ")
                val lines = wrapText(permText, bodyPaint, CONTENT_WIDTH - 16f)
                for (line in lines) {
                    ctx.ensureSpace(14f)
                    canvas.drawText(line, MARGIN + 16f, ctx.y, bodyPaint)
                    ctx.y += 13f
                }
            }
            ctx.y += 10f
            canvas.drawLine(MARGIN, ctx.y, PAGE_WIDTH - MARGIN, ctx.y, linePaint)
            ctx.y += 14f
        }
    }

    private fun drawMediumRiskApps(ctx: PageContext, apps: List<ScanResultEntity>) {
        val canvas = ctx.canvas
        canvas.drawText("7. Medium-Risk Applications (${apps.size})", MARGIN, ctx.y, sectionPaint)
        ctx.y += 20f

        for (app in apps) {
            ctx.ensureSpace(16f)
            val permCount = app.permissions.split(",").count { it.isNotBlank() }
            canvas.drawText("\u2022 ${app.appName}", MARGIN + 8f, ctx.y, bodyPaint)
            val detail = "$permCount permissions · ${app.packageName}"
            canvas.drawText(
                detail,
                PAGE_WIDTH - MARGIN - smallPaint.measureText(detail),
                ctx.y,
                smallPaint
            )
            ctx.y += 15f
        }
        ctx.y += 10f
    }

    private fun drawLowRiskSummary(ctx: PageContext, apps: List<ScanResultEntity>) {
        val canvas = ctx.canvas
        canvas.drawText("8. Low-Risk Applications (${apps.size})", MARGIN, ctx.y, sectionPaint)
        ctx.y += 20f

        canvas.drawText(
            "These apps request few or no dangerous permissions.",
            MARGIN, ctx.y, bodyPaint
        )
        ctx.y += 16f

        val sample = apps.take(15).joinToString(", ") { it.appName }
        val more = if (apps.size > 15) " … and ${apps.size - 15} more." else ""
        val lines = wrapText(sample + more, bodyPaint, CONTENT_WIDTH - 8f)
        for (line in lines) {
            ctx.ensureSpace(14f)
            canvas.drawText(line, MARGIN + 8f, ctx.y, bodyPaint)
            ctx.y += 13f
        }
        ctx.y += 10f
    }

    private fun drawBreachReport(ctx: PageContext, data: ReportData) {
        val canvas = ctx.canvas
        canvas.drawText("9. Data Breach Report", MARGIN, ctx.y, sectionPaint)
        ctx.y += 20f

        val emails = data.breachResults.map { it.email }.distinct()
        canvas.drawText(
            "${data.breachResults.size} breach(es) found across ${emails.size} email(s).",
            MARGIN, ctx.y, bodyBoldPaint
        )
        ctx.y += 20f

        for (email in emails) {
            val breaches = data.breachResults.filter { it.email == email }
                .sortedByDescending { it.breachDate }

            ctx.ensureSpace(30f)
            canvas.drawText(email, MARGIN, ctx.y, accentPaint)
            canvas.drawText(
                "${breaches.size} breach(es)",
                PAGE_WIDTH - MARGIN - smallPaint.measureText("${breaches.size} breach(es)"),
                ctx.y,
                highPaint
            )
            ctx.y += 16f

            for (breach in breaches) {
                ctx.ensureSpace(60f)
                val sevPaint = when (breach.severity.uppercase()) {
                    "HIGH" -> highPaint
                    "MEDIUM" -> mediumPaint
                    else -> lowPaint
                }
                canvas.drawText("\u25CF ${breach.breachName}", MARGIN + 8f, ctx.y, bodyBoldPaint)
                canvas.drawText(
                    "[${breach.severity.uppercase()}]",
                    PAGE_WIDTH - MARGIN - sevPaint.measureText("[${breach.severity.uppercase()}]"),
                    ctx.y,
                    sevPaint
                )
                ctx.y += 14f
                canvas.drawText("Date: ${breach.breachDate}", MARGIN + 20f, ctx.y, smallPaint)
                ctx.y += 12f
                canvas.drawText("Exposed: ${breach.dataExposed}", MARGIN + 20f, ctx.y, smallPaint)
                ctx.y += 12f
                val descLines = wrapText(breach.description, smallPaint, CONTENT_WIDTH - 24f)
                for (line in descLines.take(3)) {
                    ctx.ensureSpace(12f)
                    canvas.drawText(line, MARGIN + 20f, ctx.y, smallPaint)
                    ctx.y += 11f
                }
                ctx.y += 8f
            }
            ctx.y += 6f
            canvas.drawLine(MARGIN, ctx.y, PAGE_WIDTH - MARGIN, ctx.y, linePaint)
            ctx.y += 12f
        }
    }

    private fun drawRecommendations(ctx: PageContext, data: ReportData) {
        val canvas = ctx.canvas
        canvas.drawText("10. Recommendations", MARGIN, ctx.y, sectionPaint)
        ctx.y += 20f

        val recommendations = buildRecommendations(data)
        if (recommendations.isEmpty()) {
            canvas.drawText(
                "No specific recommendations - your device is in good shape.",
                MARGIN, ctx.y, lowPaint
            )
            ctx.y += 20f
            return
        }

        for ((i, rec) in recommendations.withIndex()) {
            ctx.ensureSpace(36f)
            canvas.drawText("${i + 1}. ${rec.first}", MARGIN, ctx.y, bodyBoldPaint)
            ctx.y += 14f
            val lines = wrapText(rec.second, bodyPaint, CONTENT_WIDTH - 16f)
            for (line in lines) {
                ctx.ensureSpace(13f)
                canvas.drawText(line, MARGIN + 16f, ctx.y, bodyPaint)
                ctx.y += 12f
            }
            ctx.y += 10f
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun gradeFor(score: Int): String = when {
        score < 0 -> "Unknown"
        score >= 90 -> "Excellent"
        score >= 70 -> "Good"
        score >= 50 -> "Fair"
        else -> "At Risk"
    }

    private fun paintForScore(score: Int): Paint = when {
        score < 0 -> bodyPaint
        score >= 90 -> lowPaint
        score >= 70 -> accentPaint
        score >= 50 -> mediumPaint
        else -> highPaint
    }

    private fun overallRiskRating(data: ReportData): Pair<String, Paint> {
        val score = data.deviceCheck?.score ?: 0
        val hasBreach = data.breachResults.isNotEmpty()
        val highApps = data.highCount
        return when {
            score < 50 || highApps >= 10 -> "High" to highPaint
            score < 70 || highApps >= 4 || hasBreach -> "Medium" to mediumPaint
            else -> "Low" to lowPaint
        }
    }

    private fun buildRecommendations(data: ReportData): List<Pair<String, String>> {
        val recs = mutableListOf<Pair<String, String>>()
        val check = data.deviceCheck

        if (check != null) {
            if (!check.screenLockEnabled) {
                recs += "Enable Screen Lock" to
                    "Your device does not have a screen lock configured. Set a PIN, pattern, or password in Settings > Security to prevent unauthorized access if your phone is lost or stolen."
            }
            if (!check.biometricEnrolled) {
                recs += "Enroll Biometric Authentication" to
                    "Enrolling a fingerprint or face unlocks the device faster without weakening security, and is required by many banking apps. Add a biometric in Settings > Biometrics."
            }
            if (!check.diskEncrypted) {
                recs += "Verify Disk Encryption" to
                    "Full-disk encryption protects your data at rest. Most modern Android devices enable this by default - if yours is reporting as unencrypted, investigate in Settings > Security."
            }
            if (!check.osUpToDate) {
                recs += "Install Pending OS Updates" to
                    "Security patches address known vulnerabilities and are time-sensitive. Go to Settings > Software update and install any pending updates."
            }
            if (!check.developerOptionsOff) {
                recs += "Disable Developer Options" to
                    "Developer Options expose internal toggles that can weaken security. If you are not actively developing, disable them in Settings > Developer options."
            }
            if (!check.usbDebuggingOff) {
                recs += "Turn Off USB Debugging" to
                    "USB debugging grants any connected computer extensive control over your device. Turn it off when you are not actively debugging."
            }
            if (!check.unknownSourcesOff) {
                recs += "Restrict Unknown Source Installs" to
                    "Allowing installs from unknown sources is the primary vector for sideloaded malware. Revoke this permission for any app that does not strictly need it."
            }
        }

        if (data.highCount >= 3) {
            recs += "Review High-Risk Applications" to
                "${data.highCount} app(s) were classified as high-risk due to the number and sensitivity of permissions they hold. Open the Audit tab and consider revoking permissions or uninstalling apps you no longer use."
        }

        if (data.alertCount > 0) {
            recs += "Investigate Background Activity" to
                "S'CAN detected ${data.alertCount} instance(s) of apps transmitting data while running in the background with sensitive permissions granted. Review the Alerts tab to identify which apps are consuming data unexpectedly."
        }

        if (data.breachResults.isNotEmpty()) {
            recs += "Change Breached Account Passwords" to
                "Your email(s) appear in ${data.breachResults.size} known breach(es). Change the affected passwords immediately, do not reuse them across sites, and enable two-factor authentication where supported."
        }

        if (recs.isEmpty()) {
            recs += "Maintain Current Posture" to
                "Your device passes all standard security checks. Continue to install OS updates promptly, review app permissions when they are first granted, and run a fresh S'CAN audit monthly."
        }

        return recs
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""
        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) <= maxWidth) {
                currentLine = testLine
            } else {
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

    // -------------------------------------------------------------------------
    // PageContext - centralises pagination, page numbering, and footer drawing.
    // -------------------------------------------------------------------------

    private class PageContext(val document: PdfDocument) {
        var pageNumber = 0
        private var currentPage: PdfDocument.Page? = null
        lateinit var canvas: Canvas
        var y: Float = MARGIN

        fun newPage() {
            finishCurrentPage()
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            val page = document.startPage(info)
            currentPage = page
            canvas = page.canvas
            y = MARGIN + 20f
        }

        /** Ensure at least [needed] vertical space below the current y, else start a new page. */
        fun ensureSpace(needed: Float) {
            if (currentPage == null) {
                newPage()
                return
            }
            if (y + needed > PAGE_HEIGHT - 50f) {
                newPage()
            }
        }

        fun finishCurrentPage() {
            val p = currentPage ?: return
            drawFooter()
            document.finishPage(p)
            currentPage = null
        }

        private fun drawFooter() {
            val footerY = PAGE_HEIGHT - 20f
            canvas.drawLine(MARGIN, footerY - 10f, PAGE_WIDTH - MARGIN, footerY - 10f, linePaint)
            canvas.drawText("S'CAN Security Report", MARGIN, footerY, smallPaint)
            val pageText = "Page $pageNumber"
            canvas.drawText(
                pageText,
                PAGE_WIDTH - MARGIN - smallPaint.measureText(pageText),
                footerY,
                smallPaint
            )
        }
    }
}

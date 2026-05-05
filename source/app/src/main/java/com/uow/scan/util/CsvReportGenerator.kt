package com.uow.scan.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CSV sibling of [PdfReportGenerator]. The file contains multiple section blocks separated
 * by `# Section Name` banner rows so spreadsheet apps still parse the bulk of it as
 * tabular while a human can scan the dividers.
 *
 * Only sections enabled via [PdfReportGenerator.ReportFilters] are written.
 */
object CsvReportGenerator {

    private const val REPORTS_SUBDIR = "SCAN Reports"

    fun generate(
        context: Context,
        data: PdfReportGenerator.ReportData
    ): PdfReportGenerator.GeneratedReport {
        val sb = StringBuilder()
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        // Header
        sb.appendLine("# S'CAN Report")
        sb.appendLine("# Generated,${df.format(Date())}")
        sb.appendLine("# Device,${esc(Build.MANUFACTURER)} ${esc(Build.MODEL)} (Android ${Build.VERSION.RELEASE} SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("# Range,${data.filters.rangeStartMs?.let { df.format(Date(it)) + " → now" } ?: "All time"}")
        sb.appendLine()

        // Summary
        sb.appendLine("# Summary")
        sb.appendLine("metric,value")
        sb.appendLine("alerts_total,${data.alertCount}")
        sb.appendLine("apps_high_risk,${data.highCount}")
        sb.appendLine("apps_medium_risk,${data.mediumCount}")
        sb.appendLine("apps_low_risk,${data.lowCount}")
        sb.appendLine()

        if (data.filters.includeScans && data.deviceCheckHistory.isNotEmpty()) {
            sb.appendLine("# Security Score History")
            sb.appendLine("checked_at,score,grade")
            data.deviceCheckHistory.forEach { c ->
                sb.appendLine("${df.format(Date(c.checkedAt))},${c.score},${esc(gradeForScore(c.score))}")
            }
            sb.appendLine()
        }

        if (data.filters.includeFindings) {
            sb.appendLine("# App Audit")
            sb.appendLine("package,name,risk_level,is_system,scanned_at")
            data.scanResults.forEach { s ->
                sb.appendLine(
                    "${esc(s.packageName)},${esc(s.appName)},${esc(s.riskLevel)},${s.isSystemApp},${df.format(Date(s.scannedAt))}"
                )
            }
            sb.appendLine()

            if (data.breachResults.isNotEmpty()) {
                sb.appendLine("# Breach Results")
                sb.appendLine("email,breach_name,breach_date,severity,resolved,checked_at")
                data.breachResults.forEach { b ->
                    sb.appendLine(
                        "${esc(b.email)},${esc(b.breachName)},${esc(b.breachDate)},${esc(b.severity)},${b.resolved},${df.format(Date(b.checkedAt))}"
                    )
                }
                sb.appendLine()
            }
        }

        if (data.filters.includeAlerts && data.alerts.isNotEmpty()) {
            sb.appendLine("# Alert Timeline")
            sb.appendLine("timestamp,package,app_name,permissions,data_used_bytes,bg_duration_ms,is_read")
            data.alerts.forEach { a ->
                sb.appendLine(
                    "${df.format(Date(a.timestamp))},${esc(a.packageName)},${esc(a.appName)}," +
                        "${esc(a.permissions)},${a.dataUsedBytes},${a.backgroundDurationMs},${a.isRead}"
                )
            }
            sb.appendLine()
        }

        if (data.filters.includeAlerts && data.weeklyStats.isNotEmpty()) {
            sb.appendLine("# Weekly Activity History")
            sb.appendLine("week_start,week_end,total_alerts,critical_alerts,pattern_count")
            data.weeklyStats.forEach { w ->
                sb.appendLine(
                    "${df.format(Date(w.weekStartMs))},${df.format(Date(w.weekEndMs))}," +
                        "${w.totalAlerts},${w.criticalAlerts},${w.patternCount}"
                )
            }
            sb.appendLine()
        }

        if (data.filters.includeSms && data.smsVerdicts.isNotEmpty()) {
            sb.appendLine("# SMS Verdicts")
            sb.appendLine("timestamp,sender,verdict,confidence,message_body")
            data.smsVerdicts.forEach { v ->
                sb.appendLine(
                    "${df.format(Date(v.timestamp))},${esc(v.sender)},${esc(v.verdict)}," +
                        "${"%.2f".format(v.confidence)},${esc(v.messageBody)}"
                )
            }
            sb.appendLine()
        }

        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(now)
        val fileName = "SCAN_Report_$stamp.csv"
        val reportsDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            REPORTS_SUBDIR
        ).apply { mkdirs() }
        val file = File(reportsDir, fileName)
        FileOutputStream(file).use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }

        val publicUri = exportToDownloads(context, file, fileName)
        return PdfReportGenerator.GeneratedReport(
            file = file, publicUri = publicUri, displayName = fileName
        )
    }

    /**
     * Standard CSV escape: wrap in quotes if the cell contains a comma, quote, or newline,
     * and double any embedded quotes.
     */
    private fun esc(value: String): String {
        if (value.isEmpty()) return ""
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private fun gradeForScore(score: Int): String = when {
        score >= 80 -> "Excellent"
        score >= 65 -> "Good"
        score >= 50 -> "Fair"
        score >= 30 -> "Poor"
        else -> "Critical"
    }

    private fun exportToDownloads(context: Context, file: File, name: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
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
    }
}

package com.uow.scan.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * JSON sibling of [PdfReportGenerator]. Emits the same [PdfReportGenerator.ReportData]
 * (already filter-aware) as a single pretty-printed `.json` file so a downstream tool can
 * machine-process it. No transformation — the entity classes are dumped via Gson.
 */
object JsonReportGenerator {

    private const val REPORTS_SUBDIR = "SCAN Reports"

    fun generate(
        context: Context,
        data: PdfReportGenerator.ReportData
    ): PdfReportGenerator.GeneratedReport {
        val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        val payload = gson.toJson(toJsonShape(data))

        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(now)
        val fileName = "SCAN_Report_$stamp.json"

        val reportsDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            REPORTS_SUBDIR
        ).apply { mkdirs() }
        val file = File(reportsDir, fileName)
        FileOutputStream(file).use { it.write(payload.toByteArray(Charsets.UTF_8)) }

        val publicUri = exportToDownloads(context, file, fileName)
        return PdfReportGenerator.GeneratedReport(
            file = file, publicUri = publicUri, displayName = fileName
        )
    }

    /**
     * Strips Gson-incompatible noise (e.g. nested entity classes are fine, but we wrap the
     * whole thing in a top-level object that includes generation metadata).
     */
    private fun toJsonShape(data: PdfReportGenerator.ReportData): Map<String, Any?> = mapOf(
        "generatedAt" to System.currentTimeMillis(),
        "generatedBy" to "S'CAN Android",
        "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "androidVersion" to Build.VERSION.RELEASE,
        "androidSdk" to Build.VERSION.SDK_INT,
        "filters" to data.filters,
        "deviceCheck" to data.deviceCheck,
        "deviceCheckHistory" to data.deviceCheckHistory,
        "scanResults" to if (data.filters.includeFindings) data.scanResults else emptyList(),
        "breachResults" to if (data.filters.includeFindings) data.breachResults else emptyList(),
        "alerts" to if (data.filters.includeAlerts) data.alerts else emptyList(),
        "topDataUsers" to if (data.filters.includeAlerts) data.topDataUsers else emptyList(),
        "weeklyStats" to if (data.filters.includeAlerts) data.weeklyStats else emptyList(),
        "smsVerdicts" to if (data.filters.includeSms) data.smsVerdicts else emptyList(),
        "deviceCheckHistoryEnabled" to data.filters.includeScans,
        "summary" to mapOf(
            "alertCount" to data.alertCount,
            "highCount" to data.highCount,
            "mediumCount" to data.mediumCount,
            "lowCount" to data.lowCount,
        ),
    )

    private fun exportToDownloads(context: Context, file: File, name: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
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

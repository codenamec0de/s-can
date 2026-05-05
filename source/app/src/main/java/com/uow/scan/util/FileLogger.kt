package com.uow.scan.util

import android.content.Context
import android.util.Log
import com.uow.scan.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes log lines to a file on internal storage so they survive logcat buffer rotation.
 * Also mirrors to Logcat for real-time viewing.
 * Disabled in release builds via BuildConfig.DEBUG.
 */
object FileLogger {

    private const val TAG = "SCAN_MONITOR"
    private const val FILE_NAME = "scan_monitor.log"
    private const val MAX_SIZE_BYTES = 500 * 1024L // 500 KB cap

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Volatile
    private var logFile: File? = null

    private fun getFile(context: Context): File {
        logFile?.let { return it }
        val file = File(context.filesDir, FILE_NAME)
        logFile = file
        return file
    }

    fun d(context: Context, msg: String) {
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, msg)
        write(context, "D", msg)
    }

    fun w(context: Context, msg: String) {
        if (!BuildConfig.DEBUG) return
        Log.w(TAG, msg)
        write(context, "W", msg)
    }

    fun e(context: Context, msg: String, throwable: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        Log.e(TAG, msg, throwable)
        val full = if (throwable != null) "$msg | ${throwable.stackTraceToString()}" else msg
        write(context, "E", full)
    }

    private fun write(context: Context, level: String, msg: String) {
        try {
            val file = getFile(context)
            // Trim if over max size
            if (file.exists() && file.length() > MAX_SIZE_BYTES) {
                val lines = file.readLines()
                val trimmed = lines.drop(lines.size / 2)
                file.writeText(trimmed.joinToString("\n") + "\n")
            }
            val timestamp = dateFormat.format(Date())
            file.appendText("$timestamp $level: $msg\n")
        } catch (_: Exception) {
            // Don't crash the app over logging
        }
    }
}

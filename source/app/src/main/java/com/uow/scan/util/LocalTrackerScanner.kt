package com.uow.scan.util

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.uow.scan.model.ExodusTrackerResponse
import com.uow.scan.model.TrackerInfo
import dalvik.system.DexFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

/**
 * On-device tracker detection using the bundled Exodus Privacy database.
 *
 * Exodus' per-app `/api/search/<pkg>/details` endpoint now requires authentication,
 * so we ship their tracker definitions (`assets/trackers.json`) and detect locally
 * by matching each tracker's `code_signature` regex against class names from the
 * target APK's DEX files.
 */
object LocalTrackerScanner {

    private const val TAG = "TrackerScanner"
    private const val ASSET_NAME = "trackers.json"

    @Volatile
    private var compiled: List<Compiled>? = null

    /**
     * One tracker entry. Prefer [literals] over [pattern] when set — Exodus' code
     * signatures are nearly always alternations of package prefixes like
     * `com\.facebook\.|com\.fb\.`, which we pre-parse to literal substrings so the
     * hot loop runs on plain [String.contains] instead of regex.
     */
    private data class Compiled(
        val tracker: TrackerInfo,
        val literals: Array<String>?,
        val pattern: Pattern?,
    )

    suspend fun scan(context: Context, packageName: String): List<TrackerInfo> =
        withContext(Dispatchers.IO) {
            val patterns = loadDb(context.applicationContext)
            if (patterns.isEmpty()) return@withContext emptyList()

            val pm = context.packageManager
            val apkPath = runCatching { pm.getApplicationInfo(packageName, 0).sourceDir }
                .getOrNull() ?: return@withContext emptyList()

            val matched = BooleanArray(patterns.size)
            // Reusable matchers for the regex fallbacks. Allocating once avoids the
            // per-class GC churn that stalled the scan on multi-hundred-MB APKs.
            val matchers = Array(patterns.size) {
                patterns[it].pattern?.matcher("")
            }
            var remaining = patterns.size

            val started = System.currentTimeMillis()
            try {
                @Suppress("DEPRECATION")
                val dex = DexFile(apkPath)
                val entries = dex.entries()
                while (entries.hasMoreElements() && remaining > 0) {
                    val cls = entries.nextElement() ?: continue
                    for (i in patterns.indices) {
                        if (matched[i]) continue
                        val cp = patterns[i]
                        val hit = if (cp.literals != null) {
                            var found = false
                            for (lit in cp.literals) {
                                if (cls.contains(lit)) { found = true; break }
                            }
                            found
                        } else {
                            matchers[i]?.reset(cls)?.find() == true
                        }
                        if (hit) {
                            matched[i] = true
                            remaining--
                        }
                    }
                    // 15s budget. Big apps (Google Search etc.) can have ~150k classes,
                    // so even with the fast path we cap the worst case to keep the UI
                    // responsive — we accept the possibility of missing late matches.
                    if (System.currentTimeMillis() - started > 15_000) {
                        Log.i(TAG, "scan time-budget hit for $packageName; partial results")
                        break
                    }
                }
                runCatching { dex.close() }
            } catch (e: Exception) {
                Log.w(TAG, "DEX scan failed for $packageName: ${e.message}")
                return@withContext emptyList()
            }

            patterns.asSequence()
                .filterIndexed { idx, _ -> matched[idx] }
                .map { it.tracker }
                .sortedBy { it.name.lowercase() }
                .toList()
        }

    /**
     * Tries to parse a code-signature regex into a list of literal substrings.
     * Returns null when the signature uses regex metachars beyond `\\.` and `|`,
     * in which case the caller falls back to a compiled Pattern.
     */
    private fun parseLiterals(signature: String): Array<String>? {
        // Anything outside [A-Za-z0-9_./|\\] is a real regex metachar. The `\` is
        // only allowed in the `\.` digraph; bare backslashes elsewhere fall back.
        var i = 0
        val sb = StringBuilder()
        val parts = mutableListOf<String>()
        while (i < signature.length) {
            val c = signature[i]
            when {
                c == '|' -> {
                    if (sb.isNotEmpty()) { parts += sb.toString(); sb.clear() }
                    i++
                }
                c == '\\' -> {
                    if (i + 1 < signature.length && signature[i + 1] == '.') {
                        sb.append('.')
                        i += 2
                    } else return null
                }
                c.isLetterOrDigit() || c == '.' || c == '_' || c == '/' -> {
                    sb.append(c); i++
                }
                else -> return null
            }
        }
        if (sb.isNotEmpty()) parts += sb.toString()
        return parts.filter { it.isNotEmpty() }.toTypedArray().takeIf { it.isNotEmpty() }
    }

    private fun loadDb(context: Context): List<Compiled> {
        compiled?.let { return it }
        synchronized(this) {
            compiled?.let { return it }
            val json = runCatching {
                context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            }.getOrNull() ?: run {
                Log.w(TAG, "tracker DB asset missing")
                compiled = emptyList()
                return emptyList()
            }
            val response = runCatching {
                Gson().fromJson(json, ExodusTrackerResponse::class.java)
            }.getOrNull()
            val list = response?.trackers.orEmpty().values.mapNotNull { d ->
                val sig = d.codeSignature?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val literals = parseLiterals(sig)
                val pat = if (literals == null) {
                    runCatching { Pattern.compile(sig) }.getOrNull() ?: return@mapNotNull null
                } else null
                Compiled(
                    tracker = TrackerInfo(
                        id = d.id ?: 0,
                        name = d.name ?: "Unknown",
                        description = d.description,
                        creationDate = d.creationDate,
                        codeSignature = d.codeSignature,
                        networkSignature = d.networkSignature,
                        website = d.website,
                        categories = d.categories,
                    ),
                    literals = literals,
                    pattern = pat,
                )
            }
            compiled = list
            return list
        }
    }

    /** Total tracker count in the bundled DB. Surfaced on the About screen. */
    fun bundledTrackerCount(context: Context): Int = loadDb(context.applicationContext).size

    /**
     * Strips Exodus' Markdown-flavoured description down to readable text for the
     * detail dialog. Prefers the `## About` section when present.
     */
    fun cleanDescription(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val unified = raw.replace("\r", "")
        val aboutMatch = Regex("##\\s*About\\s*\\n([\\s\\S]+?)(?:\\n##\\s|$)")
            .find(unified)
        val body = aboutMatch?.groupValues?.get(1) ?: unified
        return body
            .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("(?m)^##\\s*"), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}

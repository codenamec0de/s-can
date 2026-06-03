package com.uow.scan.util

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.uow.scan.model.ExodusTrackerResponse
import com.uow.scan.model.TrackerInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Classifies a live destination **hostname** against the bundled Exodus database's
 * `network_signature` patterns (260 of the 432 trackers carry one). This complements
 * [LocalTrackerScanner], which matches the same DB's `code_signature` against an APK's DEX
 * classes for a static scan — here we name the trackers an app actually *talks to* on the wire.
 *
 * Signatures are domain regexes (`doubleclick\.net`, `a4\.tl|ad4screen\.com`, …); we test with
 * find() so they match anywhere in the host. Compiled once and cached; per-host lookups memoized.
 */
object TrackerDomainMatcher {

    private const val TAG = "TrackerDomain"
    private const val ASSET_NAME = "trackers.json"

    private class Sig(val pattern: Pattern, val tracker: TrackerInfo)
    /** Boxed result so a *miss* (null tracker) can also be cached. */
    private class Hit(val tracker: TrackerInfo?)

    @Volatile private var sigs: List<Sig>? = null
    private val cache = ConcurrentHashMap<String, Hit>()

    private fun load(context: Context): List<Sig> {
        sigs?.let { return it }
        synchronized(this) {
            sigs?.let { return it }
            val json = runCatching {
                context.applicationContext.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            }.getOrNull()
            val resp = json?.let {
                runCatching { Gson().fromJson(it, ExodusTrackerResponse::class.java) }.getOrNull()
            }
            val list = resp?.trackers.orEmpty().values.mapNotNull { d ->
                val net = d.networkSignature?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val pat = runCatching { Pattern.compile(net) }.getOrNull() ?: return@mapNotNull null
                Sig(
                    pat,
                    TrackerInfo(
                        id = d.id ?: 0, name = d.name ?: "Unknown", description = d.description,
                        creationDate = d.creationDate, codeSignature = d.codeSignature,
                        networkSignature = net, website = d.website, categories = d.categories,
                    ),
                )
            }
            Log.i(TAG, "compiled ${list.size} network signatures")
            sigs = list
            return list
        }
    }

    /** The tracker whose network signature matches [host], or null. Cached per host. */
    fun match(context: Context, host: String): TrackerInfo? {
        if (host.isBlank()) return null
        val key = host.lowercase()
        cache[key]?.let { return it.tracker }
        val hit = load(context).firstOrNull {
            runCatching { it.pattern.matcher(key).find() }.getOrDefault(false)
        }?.tracker
        cache[key] = Hit(hit)
        return hit
    }

    /** Pre-compile the signatures off the UI thread (e.g. when the tunnel starts). */
    fun warmUp(context: Context) { runCatching { load(context) } }
}

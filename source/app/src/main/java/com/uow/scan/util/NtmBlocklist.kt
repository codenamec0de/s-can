package com.uow.scan.util

import android.content.Context
import android.util.Log

/**
 * Curated ad/tracker hostname blocklist for the NTM DNS sinkhole (Stage 3). Bundled from a
 * reputable, compact list of **dedicated** ad/tracking servers (Peter Lowe / pgl.yoyo.org). It is
 * intentionally separate from [TrackerDomainMatcher]: the matcher *names* trackers and is broad
 * (its Facebook signature even tags `www.facebook.com`), so sinkholing everything it flags would
 * break apps. This list is curated to exclude first-party app domains, so blocking is safe — the
 * same principle Pi-hole / AdAway rely on.
 *
 * Matching is by domain suffix: `a.b.doubleclick.net` is blocked because `doubleclick.net` is
 * listed. Loaded once into a HashSet; a lookup is a handful of O(1) probes up the domain.
 */
object NtmBlocklist {

    private const val TAG = "NtmBlocklist"
    private const val ASSET = "ntm_blocklist.txt"

    @Volatile private var domains: Set<String>? = null

    fun load(context: Context): Set<String> {
        domains?.let { return it }
        synchronized(this) {
            domains?.let { return it }
            val set = runCatching {
                context.applicationContext.assets.open(ASSET).bufferedReader().useLines { lines ->
                    lines.map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toHashSet()
                }
            }.getOrElse {
                Log.w(TAG, "blocklist asset missing/unreadable", it); emptySet()
            }
            Log.i(TAG, "loaded ${set.size} blocklist domains")
            domains = set
            return set
        }
    }

    /** True if [host] — or any parent domain of it — is on the blocklist. */
    fun isBlocked(context: Context, host: String): Boolean {
        if (host.isBlank()) return false
        val set = load(context)
        if (set.isEmpty()) return false
        var h = host.lowercase().trimEnd('.')
        while (h.contains('.')) {
            if (set.contains(h)) return true
            h = h.substringAfter('.')
        }
        return false
    }

    fun warmUp(context: Context) { runCatching { load(context) } }
    fun size(context: Context): Int = load(context).size
}

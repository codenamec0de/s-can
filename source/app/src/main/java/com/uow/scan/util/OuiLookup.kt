package com.uow.scan.util

import android.content.Context

/**
 * BSSID → manufacturer (vendor) resolver, backed by a bundled subset of the IEEE
 * MA-L (OUI) registry shipped as the `oui_vendors.tsv` asset.
 *
 * The asset is a curated set of the Wi-Fi router / access-point / phone vendors a
 * nearby scan is actually likely to surface (~11.5k 24-bit prefixes). Each line is
 * `<6-hex-prefix>\t<Vendor>`. It is loaded once, lazily, into an in-memory map on
 * first lookup — callers ([WifiSecurityAnalyzer.scanNearby]) already run off the main
 * thread, so the one-time parse never touches the UI thread.
 *
 * Anything not in the table — or a locally-administered / randomized MAC, which
 * carries no registered OUI — resolves to null and the UI shows "—". The lookup is
 * deliberately read-only and offline; it never hits the network.
 */
object OuiLookup {

    @Volatile
    private var table: Map<String, String>? = null

    /** Manufacturer for a BSSID (any case, with or without colons), or null if unknown. */
    fun vendorFor(context: Context, bssid: String?): String? {
        val prefix = ouiPrefix(bssid) ?: return null
        return ensureLoaded(context)[prefix]
    }

    /**
     * Normalise a BSSID to its 24-bit OUI prefix (first 3 octets, lowercase hex, no
     * separators), or null when it isn't a usable global OUI. Locally-administered
     * addresses (the 0x02 bit of the first octet set) are randomized / private MACs
     * with no registered vendor, so they are rejected rather than mislabelled.
     */
    private fun ouiPrefix(bssid: String?): String? {
        if (bssid.isNullOrBlank()) return null
        val hex = buildString(12) {
            for (ch in bssid) {
                if (ch.isDigit() || ch in 'a'..'f' || ch in 'A'..'F') append(ch.lowercaseChar())
            }
        }
        if (hex.length < 6) return null
        val firstOctet = hex.substring(0, 2).toIntOrNull(16) ?: return null
        if (firstOctet and 0x02 != 0) return null
        return hex.substring(0, 6)
    }

    private fun ensureLoaded(context: Context): Map<String, String> {
        table?.let { return it }
        return synchronized(this) {
            table ?: load(context).also { table = it }
        }
    }

    private fun load(context: Context): Map<String, String> = try {
        context.applicationContext.assets.open(ASSET_NAME).bufferedReader().useLines { lines ->
            val map = HashMap<String, String>(16384)
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab != 6) continue
                map[line.substring(0, tab)] = line.substring(tab + 1)
            }
            map
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private const val ASSET_NAME = "oui_vendors.tsv"
}

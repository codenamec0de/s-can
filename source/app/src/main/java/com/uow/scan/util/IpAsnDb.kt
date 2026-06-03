package com.uow.scan.util

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer

/**
 * Offline IPv4 → ASN / org / country lookup, backed by bundled gzipped assets built from the
 * public-domain iptoasn.com dataset (`assets/ip2asn_ranges.bin.gz` + `ip2asn_names.tsv.gz`).
 *
 * This is how NTM enriches a destination with the company behind it without any network round-trip
 * or a MaxMind licence: the unified tunnel already sees the resolved IP in each DNS answer, so a
 * binary search over ~447k sorted CIDR ranges gives the owning AS. Loaded once, off the hot path.
 */
object IpAsnDb {

    private const val TAG = "IpAsnDb"
    data class Info(val asn: Int, val org: String, val country: String)

    /** Sorted ranges as a flat buffer: [count u32][ start u32, end u32, asn u32 ]* (big-endian). */
    @Volatile private var ranges: ByteBuffer? = null
    @Volatile private var count = 0
    private val names = HashMap<Int, Pair<String, String>>()   // asn -> (country, org)
    @Volatile private var loaded = false

    fun warmUp(context: Context) { runCatching { load(context) } }

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        val ctx = context.applicationContext
        runCatching {
            ctx.assets.open("ip2asn_ranges.bin").use { raw ->
                val bb = ByteBuffer.wrap(raw.readBytes())   // big-endian by default
                count = bb.int
                ranges = bb
            }
            ctx.assets.open("ip2asn_names.tsv").bufferedReader().useLines { lines ->
                for (ln in lines) {
                    val p = ln.split('\t')
                    if (p.size < 3) continue
                    val asn = p[0].toIntOrNull() ?: continue
                    names[asn] = p[1] to p[2]
                }
            }
            Log.i(TAG, "loaded $count ranges, ${names.size} ASNs")
        }.onFailure { Log.w(TAG, "IP-ASN DB load failed", it); count = 0 }
        loaded = true
    }

    /** Look up the AS owning [ipv4] (4 raw bytes). Null if not v4, not found, or DB unavailable. */
    fun lookup(context: Context, ipv4: ByteArray): Info? {
        if (ipv4.size != 4) return null
        load(context)
        val bb = ranges ?: return null
        if (count == 0) return null
        val ip = ((ipv4[0].toInt() and 0xFF) shl 24) or ((ipv4[1].toInt() and 0xFF) shl 16) or
            ((ipv4[2].toInt() and 0xFF) shl 8) or (ipv4[3].toInt() and 0xFF)
        // binary search for the rightmost range whose start <= ip (unsigned)
        var lo = 0; var hi = count - 1; var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val start = bb.getInt(4 + mid * 12)
            if (ucmp(start, ip) <= 0) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (idx < 0) return null
        val base = 4 + idx * 12
        val end = bb.getInt(base + 4)
        if (ucmp(ip, end) > 0) return null          // ip falls in a gap between ranges
        val asn = bb.getInt(base + 8)
        val nm = names[asn]
        return Info(asn, nm?.second ?: "AS$asn", nm?.first ?: "")
    }

    /** Unsigned 32-bit comparison (IPs above 127.x set the sign bit when read as a signed Int). */
    private fun ucmp(a: Int, b: Int): Int =
        (a.toLong() and 0xFFFFFFFFL).compareTo(b.toLong() and 0xFFFFFFFFL)
}

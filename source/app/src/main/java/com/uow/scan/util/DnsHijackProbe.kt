package com.uow.scan.util

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Tier-A on-device DNS hijack / tamper probe — the real implementation behind the
 * "Test domain returned an unexpected address" finding that [DnsLeakAnalyzer] used to stub.
 *
 * It resolves a small set of **stable anchor domains** two ways and compares them:
 *  - through the **system resolver** — whatever the active network or VPN hands us — via
 *    [InetAddress.getAllByName] (bounded by a hard timeout, see [systemLookup]); and
 *  - through a **trusted public DoH resolver** (Cloudflare, falling back to Google) over
 *    HTTPS, an encrypted channel the local network cannot read or rewrite.
 *
 * The anchors ([one.one.one.one], [dns.google]) return **globally-fixed A records**
 * (1.1.1.1/1.0.0.1 and 8.8.8.8/8.8.4.4). These IPs are *anycast* — the same address is
 * announced from many sites — which is NOT GeoDNS: every resolver worldwide returns the same
 * A-record *values*, so a non-overlapping answer is a genuine rewrite, not CDN/region churn.
 *
 * Two signals raise [Verdict.SUSPECT]:
 *  1. the system resolver returns a **private/captive address** for a public anchor — an
 *     unambiguous redirect that needs no baseline (so it's caught even when the network also
 *     blocks DoH, the classic captive-portal pattern); or
 *  2. the system answer is **wholly disjoint** from the trusted DoH baseline — an answer rewrite.
 *
 * Deliberately conservative: anything it cannot establish is [Verdict.INCONCLUSIVE]; it never
 * guesses interception. Known Tier-A blind spots (the server-backed Tier-B "deep test" covers
 * them): an **IPv6-only** network (we compare A records), and a **public-IP rewrite while DoH is
 * also blocked** (no trusted baseline to compare against). The only thing that leaves the device
 * is a DoH lookup of a public anchor, indistinguishable from ordinary browsing.
 *
 * Blocking; call from [kotlinx.coroutines.Dispatchers.IO].
 */
object DnsHijackProbe {

    enum class Verdict { CLEAN, SUSPECT, INCONCLUSIVE }

    data class Result(
        val verdict: Verdict,
        val controlDomain: String?,
        val systemAnswers: List<String>,
        val dohAnswers: List<String>,
        val reason: String,
    )

    private const val TAG = "DnsHijackProbe"

    /** Stable global anchors with fixed A records — anycast, not GeoDNS, so a mismatch is meaningful. */
    private val CONTROL_DOMAINS = listOf("one.one.one.one", "dns.google")

    private const val CF_DOH = "https://cloudflare-dns.com/dns-query"
    private const val GOOGLE_DOH = "https://dns.google/resolve"

    /** Per-call ceiling for each DoH request and each system lookup. */
    private const val PER_CALL_MS = 1500L

    /** Shared client so repeated scans reuse the connection pool / TLS sessions / DNS cache. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PER_CALL_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PER_CALL_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PER_CALL_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Daemon-threaded so a hung native DNS lookup can never block process exit. */
    private val lookupExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "dns-probe-lookup").apply { isDaemon = true }
    }

    /**
     * Runs the probe and returns on the **first anchor that yields a usable signal** — the common
     * (non-hijacked) case is one system lookup plus one DoH round-trip. [budgetMs] is an overall
     * wall-clock ceiling; every individual call is also bounded by [PER_CALL_MS], and a call is
     * only started when at least that much budget remains, so the total stays bounded even when a
     * network silently drops DNS or DoH.
     */
    fun run(budgetMs: Long = 4000L): Result {
        if (budgetMs <= 0L) return inconclusive("Invalid budget")
        val deadline = System.currentTimeMillis() + budgetMs

        for (domain in CONTROL_DOMAINS) {
            if (!budgetRemains(deadline)) break

            val sys = systemLookup(domain)
            if (sys.isEmpty()) continue   // system couldn't resolve this anchor → nothing to compare

            // (1) Unambiguous interception — needs NO trusted baseline. A public anchor must never
            // resolve to a private/captive address; this also catches portals that block DoH.
            sys.firstOrNull { isPrivateV4(it) }?.let { hit ->
                return Result(
                    Verdict.SUSPECT, domain, sys, emptyList(),
                    "System resolver returned a private address ($hit) for $domain",
                )
            }

            // (2) Answer rewrite — judged only against the trusted encrypted baseline.
            if (!budgetRemains(deadline)) break
            val doh = dohLookup(domain, deadline)
            if (doh.isEmpty()) continue   // no trusted baseline → can't compare public answers

            if (sys.none { it in doh }) {
                return Result(
                    Verdict.SUSPECT, domain, sys, doh,
                    "System resolver answer for $domain ($sys) is disjoint from the encrypted baseline ($doh)",
                )
            }
            return Result(Verdict.CLEAN, domain, sys, doh, "$domain resolved to its expected address")
        }
        return inconclusive("Could not establish a trusted baseline")
    }

    private fun budgetRemains(deadline: Long): Boolean =
        System.currentTimeMillis() + PER_CALL_MS <= deadline

    private fun inconclusive(reason: String) =
        Result(Verdict.INCONCLUSIVE, null, emptyList(), emptyList(), reason)

    /**
     * IPv4 A-record answers from the system (active-network) resolver, bounded by [PER_CALL_MS].
     * [InetAddress.getAllByName] has no timeout of its own, so it runs on a daemon thread and we
     * wait at most [PER_CALL_MS]; on timeout we abandon it (it can't stall the scan) and report empty.
     */
    private fun systemLookup(domain: String): List<String> {
        val future: Future<List<String>> = lookupExecutor.submit(Callable {
            try {
                InetAddress.getAllByName(domain)
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress }
            } catch (_: Exception) {
                emptyList()
            }
        })
        return try {
            future.get(PER_CALL_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            future.cancel(true)
            emptyList()
        }
    }

    /** IPv4 A-record answers from a trusted public DoH resolver (Cloudflare → Google fallback). */
    private fun dohLookup(domain: String, deadline: Long): List<String> {
        val name = URLEncoder.encode(domain, "UTF-8")
        dohJson("$CF_DOH?name=$name&type=A").let { if (it.isNotEmpty()) return it }
        if (!budgetRemains(deadline)) return emptyList()
        return dohJson("$GOOGLE_DOH?name=$name&type=A")
    }

    /** Cloudflare and Google both speak RFC 8484 JSON: {"Status":0,"Answer":[{"type":1,"data":"1.2.3.4"}]}. */
    private fun dohJson(url: String): List<String> = try {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful || body.isNullOrBlank()) return emptyList()
            val json = JSONObject(body)
            // Only NOERROR is a trustworthy baseline; SERVFAIL/NXDOMAIN (+ any forged answers) → none.
            if (json.optInt("Status", -1) != 0) return emptyList()
            // "Answer" is null on NXDOMAIN and an array otherwise; both collapse to an empty A-set.
            val answers = json.optJSONArray("Answer") ?: return emptyList()
            (0 until answers.length()).mapNotNull { i ->
                val a = answers.optJSONObject(i) ?: return@mapNotNull null
                if (a.optInt("type") == 1) a.optString("data").takeIf { isV4(it) } else null  // type 1 = A; skip CNAME(5) etc.
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "DoH lookup failed: ${e.javaClass.simpleName}")
        emptyList()
    }

    private fun isV4(s: String): Boolean {
        val parts = s.split(".")
        return parts.size == 4 && parts.all { (it.toIntOrNull() ?: -1) in 0..255 }
    }

    /** RFC1918 / loopback / link-local / any-local / CGNAT (100.64.0.0/10) — a non-public answer. */
    private fun isPrivateV4(ip: String): Boolean = try {
        val a = InetAddress.getByName(ip)   // literal IP → parsed, no DNS lookup
        val b = a.address
        a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress || a.isSiteLocalAddress ||
            (b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xFF) in 64..127)
    } catch (_: Exception) {
        false
    }
}

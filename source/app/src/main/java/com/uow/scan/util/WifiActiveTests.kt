package com.uow.scan.util

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/**
 * Active Wi-Fi safety probes — the layer on top of the passive scan that tests the **current
 * network for tampering**, on-device, in real time. Everything here is real (brief §5):
 *
 *  - **DNS integrity** — reuses [DnsHijackProbe]: resolves control domains and compares the
 *    network's answers against an encrypted (DoH) baseline. A rewrite/redirect → FAIL.
 *  - **TLS integrity** — opens a real TLS handshake to a stable, publicly-trusted host using the
 *    app's *default* trust store. On Android 7+ apps do **not** trust user-installed CAs, so an
 *    intercepting proxy's certificate fails validation → FAIL. A clean validated chain → PASS.
 *  - **Captive / injection** — requests the fixed `generate_204` and checks it returns **204 with
 *    an empty body**. A redirect = captive portal (inconclusive); a 200/body = injected → FAIL.
 *
 * All three are blocking network calls — run them off the main thread.
 */
object WifiActiveTests {

    enum class Result { PASS, FAIL, INCONCLUSIVE }

    data class Report(val dns: Result, val tls: Result, val captive: Result) {
        val anyFail: Boolean get() = dns == Result.FAIL || tls == Result.FAIL || captive == Result.FAIL

        /** How far to dock the passive safety score for *tested* tampering. The DNS-hijack penalty
         *  only applies when **not shielded** — the Shield's DoH encrypts lookups, so a redirecting
         *  network can no longer reach you. TLS interception and HTTP injection ride normal traffic,
         *  which the DNS-only Shield does not tunnel, so those penalties always apply. */
        fun scoreDock(shielded: Boolean): Int {
            var d = 0
            if (dns == Result.FAIL && !shielded) d += 40
            if (tls == Result.FAIL) d += 45
            if (captive == Result.FAIL) d += 20
            return d
        }

        /** Positive credit for *verified* protection — the real-time value the passive crypto scan
         *  can't see, so it can **raise** the score, not just avoid docking it. We only reward a test
         *  that genuinely PASSed (an INCONCLUSIVE earns nothing), plus the Shield's encrypted DNS.
         *  Max +15: dns 2 + tls 3 + captive 2 + shield 8. */
        fun activeCredit(shielded: Boolean): Int {
            var c = 0
            if (dns == Result.PASS) c += 2          // confirmed the resolver isn't rewriting answers
            if (tls == Result.PASS) c += 3          // confirmed no intercepting proxy in the chain
            if (captive == Result.PASS) c += 2      // confirmed no portal / content injection
            if (shielded) c += 8                    // Shield (DoH) encrypts DNS — a real added layer
            return c
        }

        /** A failing test that isn't neutralised by the current Shield state (drives the score floor). */
        fun actionableFail(shielded: Boolean): Boolean =
            (dns == Result.FAIL && !shielded) || tls == Result.FAIL || captive == Result.FAIL

        /** Threat keys to surface as findings (parallels the design's threat map). */
        val threats: List<String>
            get() = buildList {
                if (dns == Result.FAIL) add("dnsHijack")
                if (tls == Result.FAIL) add("tlsIntercept")
                if (captive == Result.FAIL) add("captiveInject")
            }
    }

    private const val TIMEOUT_MS = 4000

    /** [networkForDns] = the underlying (non-VPN) network to resolve over, so the DNS probe always
     *  tests the *real network* — even while the Shield's DoH tunnel is up (it only routes port 53). */
    fun run(networkForDns: android.net.Network? = null): Report =
        Report(dns = testDns(networkForDns), tls = testTls(), captive = testCaptive())

    fun testDns(network: android.net.Network? = null): Result = when (DnsHijackProbe.run(4000L, network).verdict) {
        DnsHijackProbe.Verdict.CLEAN -> Result.PASS
        DnsHijackProbe.Verdict.SUSPECT -> Result.FAIL
        DnsHijackProbe.Verdict.INCONCLUSIVE -> Result.INCONCLUSIVE
    }

    fun testTls(): Result {
        var conn: HttpsURLConnection? = null
        return try {
            conn = (URL("https://cloudflare-dns.com/").openConnection() as HttpsURLConnection).apply {
                connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS
                requestMethod = "HEAD"; instanceFollowRedirects = false
            }
            conn.connect()                                   // performs + validates the TLS handshake
            val validated = conn.serverCertificates.isNotEmpty()
            if (validated) Result.PASS else Result.INCONCLUSIVE
        } catch (e: SSLException) {
            Result.FAIL                                      // cert didn't validate → interception
        } catch (e: IOException) {
            Result.INCONCLUSIVE                              // couldn't reach host (offline / captive)
        } finally {
            conn?.disconnect()
        }
    }

    fun testCaptive(): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("http://connectivitycheck.gstatic.com/generate_204").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false; useCaches = false
            }
            val code = conn.responseCode
            val len = conn.contentLength
            when {
                code == 204 && len <= 0 -> Result.PASS
                code in 300..399 -> Result.INCONCLUSIVE      // captive portal redirect
                else -> Result.FAIL                          // 200 / injected body
            }
        } catch (e: IOException) {
            Result.INCONCLUSIVE
        } finally {
            conn?.disconnect()
        }
    }
}

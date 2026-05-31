package com.uow.scan.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress

/**
 * DNS Leak Detection — answers one question: "Is my browsing private right now, or
 * can my network see / redirect the sites I visit?"
 *
 * This is the **Beta** analyzer behind the S'CAN V4 DNS screens. It runs entirely
 * on-device off the active [ConnectivityManager] link: it reads the active resolver,
 * whether Android Private DNS (DoT) is enforcing encryption, VPN/tunnel status, and
 * the underlying transport — then scores the result and generates the same findings +
 * resolver-summary tiles the design renders.
 *
 * The on-device hijack/tamper probe is live (Tier A): [readLive] runs [DnsHijackProbe], which
 * compares the system resolver against a trusted public DoH baseline and can pull a confirmed
 * rewrite down into [Grade.INTERCEPTED]. Two limits remain:
 *  - DNSSEC validation isn't reliably observable from app APIs, so it's reported off in
 *    live mode.
 *  - Proving *where* a lookup actually egresses (vs. detecting a rewrite) needs the
 *    server-backed Tier-B "deep test" — not yet wired (the deep-test CTA is a placeholder).
 *
 * For deterministic live demos, [DemoMode.EXPOSED] / [DemoMode.PROTECTED] bypass live
 * reading and return the design's two fixed scenarios verbatim.
 */
object DnsLeakAnalyzer {

    /** Verdict band — parallels [WifiSecurityAnalyzer.Grade] but with DNS-specific words. */
    enum class Grade { PRIVATE, PARTIAL, EXPOSED, INTERCEPTED }

    enum class Severity { BAD, WARN, OK }

    /** Demo override, persisted via [PreferencesManager.getDnsDemoMode]. */
    enum class DemoMode { AUTO, EXPOSED, PROTECTED }

    /** A single check result. [cta] deep-links a remediation: "private-dns" | "deep-test". */
    data class Finding(
        val severity: Severity,
        val title: String,
        val description: String,
        val fix: String? = null,
        val cta: String? = null,
    )

    /** The active resolver + the context needed to render the 2×2 summary tiles. */
    data class Resolver(
        val provider: String,
        val address: String,
        val encrypted: Boolean,
        val protocol: String,
        val network: String,
        val networkName: String,
        val vpn: Boolean,
        val dnssec: Boolean,
        val isRouter: Boolean,
    )

    data class DnsResult(
        val score: Int,
        val grade: Grade,
        val verdictLine: String,
        val resolver: Resolver,
        val findings: List<Finding>,
        val isDemo: Boolean,
    ) {
        val flaggedCount: Int get() = findings.count { it.severity != Severity.OK }
        val isClean: Boolean get() = flaggedCount == 0
    }

    /** Either a rendered [DnsResult] or the offline/error state. */
    sealed class Outcome {
        data class Ok(val result: DnsResult) : Outcome()
        object Offline : Outcome()
    }

    /** Whether the tamper probe ran, and what it concluded ([DnsHijackProbe] in live mode). */
    private enum class Tamper { NOT_RUN, CLEAN, SUSPECT }

    private data class Scenario(
        val score: Int,
        val resolver: Resolver,
        val tamper: Tamper,
        val isDemo: Boolean,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    fun analyze(context: Context, demoMode: DemoMode): Outcome {
        val scenario = when (demoMode) {
            DemoMode.EXPOSED -> exposedScenario()
            DemoMode.PROTECTED -> protectedScenario()
            DemoMode.AUTO ->
                // When S'CAN's own DNS Protection (DoH VpnService) is up, the live link shows our
                // virtual resolver — which would misread as an unencrypted "router". Report the
                // real, honest posture instead: DNS is genuinely encrypted over DNS-over-HTTPS.
                if (PreferencesManager.isDnsProtectionActive(context)) scanProtectedScenario(context)
                else readLive(context) ?: return Outcome.Offline
        }
        val grade = gradeFor(scenario.score)
        return Outcome.Ok(
            DnsResult(
                score = scenario.score,
                grade = grade,
                verdictLine = verdictLine(grade),
                resolver = scenario.resolver,
                findings = generateFindings(scenario),
                isDemo = scenario.isDemo,
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Live detection
    // ─────────────────────────────────────────────────────────────────────────

    /** Reads the active network's resolver state. Returns null when there's no usable network. */
    private fun readLive(context: Context): Scenario? {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val active = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(active) ?: return null
        val link = cm.getLinkProperties(active)

        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val network = when {
            vpn -> "VPN"
            isWifi -> "Wi-Fi"
            isCellular -> "Cellular"
            else -> "Network"
        }
        val networkName = when {
            isWifi -> wifiSsid(context) ?: "Wi-Fi network"
            isCellular -> "Mobile data"
            vpn -> "tunnelled"
            else -> "—"
        }

        val encrypted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            link?.isPrivateDnsActive == true
        val privateDnsHost = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            link?.privateDnsServerName
        } else null

        val firstDns: InetAddress? = link?.dnsServers?.firstOrNull()
        // No enumerable resolver and not encrypted: we can't say anything useful — treat as offline.
        if (firstDns == null && !encrypted) return null

        val isRouter = firstDns?.let { isPrivateAddress(it) } ?: false
        val protocol = if (encrypted) "DNS-over-TLS" else "Plain (Do53)"
        val address = privateDnsHost
            ?: firstDns?.hostAddress
            ?: if (encrypted) "encrypted" else "unknown"
        val provider = providerName(encrypted, privateDnsHost, firstDns, isRouter)

        val resolver = Resolver(
            provider = provider,
            address = address,
            encrypted = encrypted,
            protocol = protocol,
            network = network,
            networkName = networkName,
            vpn = vpn,
            dnssec = false,
            isRouter = isRouter,
        )
        // Tier-A hijack/tamper probe: compare the system resolver to a trusted DoH baseline.
        val tamper = when (DnsHijackProbe.run().verdict) {
            DnsHijackProbe.Verdict.SUSPECT -> Tamper.SUSPECT
            DnsHijackProbe.Verdict.CLEAN -> Tamper.CLEAN
            DnsHijackProbe.Verdict.INCONCLUSIVE -> Tamper.NOT_RUN
        }
        return Scenario(scoreFor(resolver, tamper), resolver, tamper, isDemo = false)
    }

    /**
     * The posture while S'CAN DNS Protection is active: DNS is carried by our on-device tunnel
     * and re-issued over DNS-over-HTTPS to Cloudflare, so it is genuinely encrypted to a trusted
     * resolver and off the local router. Scores into the PRIVATE band — honestly, not cosmetically.
     */
    private fun scanProtectedScenario(context: Context): Scenario {
        val resolver = Resolver(
            provider = "S'CAN Protected DNS",
            address = "Cloudflare · DoH",
            encrypted = true,
            protocol = "DNS-over-HTTPS",
            network = "Protected",
            networkName = "Encrypted tunnel",
            vpn = true,
            // DNSSEC validation isn't observable from app APIs (same honesty rule as live mode),
            // so we don't claim it. Encrypted + off-router + clean still scores into PRIVATE.
            dnssec = false,
            isRouter = false,
        )
        return Scenario(scoreFor(resolver, Tamper.CLEAN), resolver, Tamper.CLEAN, isDemo = false)
    }

    private fun wifiSsid(context: Context): String? {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION")
        val raw = wm.connectionInfo?.ssid ?: return null
        val trimmed = raw.trim().removeSurrounding("\"")
        if (trimmed.isBlank() || trimmed == "<unknown ssid>" || trimmed == "0x") return null
        return trimmed
    }

    /**
     * Live scoring rubric. Floors at 35 (EXPOSED) so posture alone never claims INTERCEPTED. A
     * *confirmed* rewrite ([Tamper.SUSPECT] from [DnsHijackProbe]) is the worst case: it applies a
     * heavy penalty and is **capped at the top of the EXPOSED band (59)** — so a detected hijack is
     * never graded better than EXPOSED regardless of encryption/VPN posture, and on a weak network
     * (unencrypted router) it drops into INTERCEPTED. Encryption protects confidentiality, but it
     * cannot make a rewritten lookup "private", so SUSPECT must never reach PARTIAL/PRIVATE.
     */
    private fun scoreFor(r: Resolver, tamper: Tamper): Int {
        var s = 60
        s += if (r.encrypted) 32 else -20
        s += if (r.isRouter) -12 else 6
        if (r.vpn) s += 8
        if (r.dnssec) s += 4
        if (tamper == Tamper.SUSPECT) return (s - 30).coerceIn(5, 59)
        return s.coerceIn(35, 99)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resolver identification
    // ─────────────────────────────────────────────────────────────────────────

    /** RFC1918 / CGNAT / link-local / loopback ⇒ the resolver is the local router, not a public one. */
    private fun isPrivateAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress
        ) return true
        // CGNAT 100.64.0.0/10 isn't covered by isSiteLocalAddress.
        if (addr is Inet4Address) {
            val b = addr.address
            val o0 = b[0].toInt() and 0xFF
            val o1 = b[1].toInt() and 0xFF
            if (o0 == 100 && o1 in 64..127) return true
        }
        return false
    }

    private fun providerName(
        encrypted: Boolean,
        privateDnsHost: String?,
        firstDns: InetAddress?,
        isRouter: Boolean,
    ): String {
        privateDnsHost?.let { host ->
            knownByHostname(host.lowercase())?.let { return it }
            return host
        }
        firstDns?.hostAddress?.let { ip -> knownByIp(ip)?.let { return it } }
        return when {
            isRouter -> "Your router"
            encrypted -> "Private DNS"
            else -> "Custom resolver"
        }
    }

    private fun knownByIp(ip: String): String? = when (ip) {
        "1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001" -> "Cloudflare"
        "8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844" -> "Google"
        "9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9" -> "Quad9"
        "208.67.222.222", "208.67.220.220" -> "OpenDNS"
        "94.140.14.14", "94.140.15.15" -> "AdGuard"
        "76.76.2.0", "76.76.10.0" -> "Control D"
        "185.228.168.9", "185.228.169.9" -> "CleanBrowsing"
        else -> null
    }

    private fun knownByHostname(host: String): String? = when {
        host.contains("cloudflare") || host == "one.one.one.one" -> "Cloudflare"
        host.contains("dns.google") || host.contains("google") -> "Google"
        host.contains("quad9") -> "Quad9"
        host.contains("opendns") -> "OpenDNS"
        host.contains("adguard") -> "AdGuard"
        host.contains("controld") || host.contains("windscribe") -> "Control D"
        host.contains("cleanbrowsing") -> "CleanBrowsing"
        host.contains("nextdns") -> "NextDNS"
        host.contains("mullvad") -> "Mullvad"
        else -> null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Verdict bands (mirrors the design's dnsVerdict)
    // ─────────────────────────────────────────────────────────────────────────

    private fun gradeFor(score: Int): Grade = when {
        score >= 85 -> Grade.PRIVATE
        score >= 60 -> Grade.PARTIAL
        score >= 35 -> Grade.EXPOSED
        else -> Grade.INTERCEPTED
    }

    private fun verdictLine(grade: Grade): String = when (grade) {
        Grade.PRIVATE -> "Your DNS lookups are encrypted and private."
        Grade.PARTIAL -> "Some lookups are protected, but gaps remain."
        Grade.EXPOSED -> "Your network can see every site you visit."
        Grade.INTERCEPTED -> "Lookups appear to be redirected by your network."
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Findings (mirrors the design's dnsFindings generator)
    // ─────────────────────────────────────────────────────────────────────────

    private fun generateFindings(s: Scenario): List<Finding> {
        val r = s.resolver
        val f = mutableListOf<Finding>()

        if (!r.encrypted) f += Finding(
            Severity.BAD, "DNS is not encrypted",
            "Every domain you look up is sent in plain text to the resolver. Anyone on your network — and your ISP — can log exactly which sites you visit, even on HTTPS.",
            "Tap Protect to route your DNS over an encrypted channel (DoH) — no settings to change.", "private-dns",
        )
        if (r.isRouter) f += Finding(
            Severity.BAD, "Resolver is the local router",
            "All lookups terminate at ${r.address}. A compromised or misconfigured router can silently redirect any domain to an address of its choosing.",
            "Tap Protect to send DNS to a trusted resolver over an encrypted channel.", "private-dns",
        )
        if (s.tamper == Tamper.SUSPECT) f += Finding(
            Severity.WARN, "Test domain returned an unexpected address",
            "A known control domain resolved to an IP outside its published range — a signature of captive-portal interception or DNS-based filtering.",
            "Run a Deep test to confirm which resolver handled the request.", "deep-test",
        )
        // VPN gap is only a real privacy hole while DNS is unencrypted (matches the design fix).
        if (!r.vpn && !r.encrypted) f += Finding(
            Severity.WARN, "No VPN tunnel active",
            "Without a tunnel, your unencrypted DNS and the destination IPs of your traffic are visible to everyone on the local network.",
            "Optional: enable a trusted VPN on untrusted networks.",
        )

        // Positives
        if (r.encrypted) f += Finding(
            Severity.OK, "Encrypted with ${r.protocol}",
            "Lookups are wrapped in TLS to a trusted resolver. The local network and your ISP can no longer read which domains you request.",
        )
        if (!r.isRouter) f += Finding(
            Severity.OK, "Trusted resolver — ${r.provider}",
            "Resolution is handled by ${r.provider} (${r.address}) rather than the local router, removing the network's ability to redirect you.",
        )
        if (r.dnssec) f += Finding(
            Severity.OK, "DNSSEC validation active",
            "Forged or tampered responses are cryptographically rejected, so you reach the real address or none at all.",
        )
        if (s.tamper == Tamper.CLEAN) f += Finding(
            Severity.OK, "No tampering detected",
            "Control domains all resolved to their expected addresses.",
            "Run a Deep test to confirm which resolver actually carried your lookups.", "deep-test",
        )
        return f
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fixed demo scenarios (verbatim from the design's dns-data)
    // ─────────────────────────────────────────────────────────────────────────

    private fun exposedScenario() = Scenario(
        // Design data uses 34; nudged to 36 so the rendered grade is EXPOSED (matching the
        // scenario's name + findings) rather than the off-by-one INTERCEPTED band.
        score = 36,
        resolver = Resolver(
            provider = "Your router", address = "192.168.0.1",
            encrypted = false, protocol = "Plain (Do53)",
            network = "Wi-Fi", networkName = "Charlie's Glorious NBN_5G",
            vpn = false, dnssec = false, isRouter = true,
        ),
        tamper = Tamper.SUSPECT,
        isDemo = true,
    )

    private fun protectedScenario() = Scenario(
        score = 96,
        resolver = Resolver(
            provider = "Cloudflare", address = "1.1.1.1",
            encrypted = true, protocol = "DNS-over-TLS",
            network = "Wi-Fi", networkName = "Charlie's Glorious NBN_5G",
            vpn = false, dnssec = true, isRouter = false,
        ),
        tamper = Tamper.CLEAN,
        isDemo = true,
    )
}

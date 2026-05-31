package com.uow.scan.util

import com.uow.scan.api.DnsLeakProbeClient
import com.uow.scan.api.DnsLeakProbeService.ProbeResultResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/**
 * Tier-B "deep test" — the server-backed egress probe.
 *
 * Mints a one-time random token, resolves `<token>.dnsprobe.scan-ai.xyz` through the **system
 * resolver** (plain DNS via the OS resolver, never DoH) so the query travels the real network/VPN
 * path, then polls our `/result` API for the resolver that actually carried it. This proves
 * *where* DNS exits — the half the on-device Tier A can't observe.
 *
 * Demo modes return canned egress scenarios so a presentation is deterministic, consistent with
 * the DNS screen's long-press override. Only the resolver is ever observed, never the device.
 */
object DnsLeakProbe {

    data class EgressResult(
        val found: Boolean,
        val headline: String,   // "Your DNS exited via <owner> — <city>, <cc> (AS####)."
        val verdict: String,    // plain-language assessment
        val isDemo: Boolean,
    )

    private const val ZONE = "dnsprobe.scan-ai.xyz"
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    suspend fun run(demoMode: DnsLeakAnalyzer.DemoMode, vpnActive: Boolean): EgressResult {
        when (demoMode) {
            DnsLeakAnalyzer.DemoMode.EXPOSED -> return demoExposed()
            DnsLeakAnalyzer.DemoMode.PROTECTED -> return demoProtected()
            DnsLeakAnalyzer.DemoMode.AUTO -> Unit  // live path below
        }

        val token = mintToken()
        withContext(Dispatchers.IO) { triggerLookup("$token.$ZONE") }
        val r = poll(token) ?: return EgressResult(false, "", "", isDemo = false)
        return live(r, vpnActive)
    }

    private fun mintToken(): String {
        val rnd = SecureRandom()
        val sb = StringBuilder(24)
        repeat(24) { sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    /** Force the system resolver to look up the token host so the query egresses. Best-effort, bounded. */
    private fun triggerLookup(host: String) {
        SystemDnsLookup.resolve(host, 2500L)
    }

    /**
     * Poll /result until our server reports the resolver that asked, or we give up. Budgeted
     * generously (~14s of polling under a hard 16s ceiling) because an intercontinental NS plus a
     * slow mobile resolver can take several seconds before the query becomes visible to the API.
     */
    private suspend fun poll(token: String): ProbeResultResponse? = withTimeoutOrNull(16_000L) {
        val delays = longArrayOf(600, 800, 1000, 1200, 1500, 2000, 2500, 2500)
        for (d in delays) {
            delay(d)
            try {
                val resp = DnsLeakProbeClient.getApi().result(token)
                val body = resp.body()
                if (resp.isSuccessful && body != null && body.status == "ok") return@withTimeoutOrNull body
            } catch (_: Exception) {
                // network/timeout — keep polling within budget
            }
        }
        null
    }

    private fun live(r: ProbeResultResponse, vpnActive: Boolean): EgressResult {
        val owner = r.org ?: r.resolver_ip ?: "an unidentified resolver"
        val loc = listOfNotNull(r.city, r.country).joinToString(", ")
        val headline = buildString {
            append("Your DNS exited via ").append(owner)
            if (loc.isNotBlank()) append(" — ").append(loc)
            if (r.asn != null) append(" (AS").append(r.asn).append(")")
            append(".")
        }
        val verdict = when {
            // While a VPN / S'CAN DNS Protection is active, the ONLY real leak is DNS still
            // escaping to the ISP. Exiting via a public resolver (e.g. Cloudflare over DoH) or the
            // VPN itself is the intended, protected path — not a leak.
            vpnActive && r.kind == "isp" ->
                "⚠ A VPN is active, but your DNS is still exiting through your ISP — a DNS leak."
            vpnActive && (r.kind == "vpn" || r.kind == "hosting") ->
                "✓ Your VPN is carrying your DNS — no leak detected."
            vpnActive ->  // public_resolver / unclassified — anything that isn't the ISP
                "✓ Your DNS is exiting via $owner, not your ISP — no leak detected."
            r.kind == "public_resolver" ->
                "Your DNS is handled by a public resolver, not your local network."
            r.kind == "vpn" ->
                "Your DNS exits through a VPN / anonymity provider."
            r.kind == null || r.kind == "unknown" ->
                "We couldn't classify $owner. Turn on Private DNS for stronger control over where your lookups go."
            else ->
                "Your DNS exits through your network provider."
        }
        return EgressResult(true, headline, verdict, isDemo = false)
    }

    // Egress story for the "bad" demo pole — an unencrypted router with no VPN (DnsLeakAnalyzer's
    // exposedScenario). The deep test confirms what the on-device check warned: lookups leave via
    // the ISP, which can see and redirect them. No VPN is presumed — the screen shows none.
    private fun demoExposed() = EgressResult(
        true,
        "Your DNS exited via Telstra Limited — Sydney, AU (AS1221).",
        "⚠ Your DNS is leaving through your ISP, Telstra — not a private resolver. Your provider can see, log, and redirect every site you visit.",
        isDemo = true,
    )

    // Egress story for the "good" demo pole — encrypted DNS-over-TLS to Cloudflare, no VPN
    // (DnsLeakAnalyzer's protectedScenario). The deep test confirms egress via Cloudflare, matching
    // the resolver shown on screen rather than a phantom VPN provider.
    private fun demoProtected() = EgressResult(
        true,
        "Your DNS exited via Cloudflare — Sydney, AU (AS13335).",
        "✓ Your DNS is exiting via Cloudflare over an encrypted channel, not your ISP — no leak detected.",
        isDemo = true,
    )
}

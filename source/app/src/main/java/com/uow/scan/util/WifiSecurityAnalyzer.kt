package com.uow.scan.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Parcelable
import androidx.core.content.ContextCompat
import kotlinx.parcelize.Parcelize
import java.net.InetAddress

/**
 * Wi-Fi security posture analyzer.
 *
 * Serious, multi-signal analysis of the currently connected Wi-Fi network. Combines:
 *
 *  - Authentication / key-management (Open, WEP, WPA, WPA2-PSK, WPA2-EAP, WPA3-SAE,
 *    WPA3-Enterprise, OWE Enhanced Open) - parsed from [ScanResult.capabilities] and,
 *    on API 31+, verified against [WifiInfo.getCurrentSecurityType].
 *  - Cipher suite (CCMP/AES vs TKIP) - TKIP is a KRACK/RC4-adjacent legacy cipher.
 *  - Protected Management Frames (802.11w) - [MFPR] (required) vs [MFPC] (capable) vs none.
 *    Defends against deauth/disassoc flood attacks and hardens WPA2 against KRACK.
 *  - Band / frequency (2.4 / 5 / 6 GHz) - 6 GHz implies Wi-Fi 6E and mandatory WPA3.
 *  - 802.11 standard (a/b/g/n/ac/ax/be) - older standards often pair with weaker crypto.
 *  - Signal strength (RSSI) - very weak signals are easier to jam / spoof.
 *  - MAC randomization - whether the *client* is revealing its factory MAC.
 *  - Hidden SSID - non-broadcast networks force clients to probe, leaking preferred-network
 *    list.
 *  - Evil-twin heuristic - multiple BSSIDs advertising the same SSID with *different*
 *    security profiles, or suspicious signal-strength clustering.
 *  - Captive portal - traffic is being intercepted by a gateway page (common in public Wi-Fi,
 *    and also a vector for credential harvesting).
 *  - DNS configuration - whether DNS is being pushed by the AP, and whether it resolves
 *    through cleartext :53 (vs DoT / DoH / VPN).
 *  - WPS enabled - exposes PIN-brute-force surface on many consumer APs.
 *
 * The analyzer degrades gracefully when some signals are unavailable (e.g., location /
 * NEARBY_WIFI_DEVICES permission not granted → scan-result heuristics skipped).
 *
 * Result is a composite 0-100 [score] plus a structured list of [findings] that the UI
 * can render directly.
 */
object WifiSecurityAnalyzer {

    // ─────────────────────────────────────────────────────────────────────────
    // Public model
    // ─────────────────────────────────────────────────────────────────────────

    enum class AuthType {
        OPEN,               // No encryption whatsoever
        OWE,                // Enhanced Open - encrypted but unauthenticated
        WEP,                // Broken since 2001
        WPA_PERSONAL,       // WPA/TKIP era - legacy only
        WPA2_PERSONAL,      // PSK + CCMP
        WPA2_ENTERPRISE,    // 802.1X / EAP
        WPA3_PERSONAL,      // SAE
        WPA3_ENTERPRISE,    // 802.1X with 192-bit suite
        WPA2_WPA3_MIXED,    // Transition mode - WPA2 fallback is still exposed
        UNKNOWN
    }

    enum class Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO, OK }

    enum class Grade { EXCELLENT, GOOD, FAIR, POOR, CRITICAL }

    @Parcelize
    data class Finding(
        val id: String,
        val severity: Severity,
        val title: String,
        val description: String,
        val recommendation: String? = null
    ) : Parcelable

    data class WifiSecurityResult(
        /** true when the device is not connected to any Wi-Fi network. */
        val notConnected: Boolean,

        /** Best-effort SSID; may be "<unknown ssid>" without location permission. */
        val ssid: String?,
        val bssid: String?,
        val authType: AuthType,
        val rawCapabilities: String?,
        val cipher: String?,                  // CCMP / TKIP / MIXED / null
        val pmfRequired: Boolean,
        val pmfCapable: Boolean,
        val wpsEnabled: Boolean,
        val hiddenSsid: Boolean,
        val isEnterprise: Boolean,

        /** 2400 / 5000 / 6000 MHz buckets. */
        val bandMhz: Int?,
        val wifiStandard: String?,            // "Wi-Fi 4 / 5 / 6 / 6E / 7"
        val rssiDbm: Int?,                    // signal strength
        val signalQuality: Int,               // 0-100

        val macRandomized: Boolean?,          // null = unknown
        val captivePortal: Boolean,
        val internetValidated: Boolean,
        val dnsServers: List<String>,
        val apparentEvilTwin: Boolean,        // same SSID, multiple BSSIDs, differing security (after trust)
        val apparentEvilTwinRaw: Boolean,     // raw heuristic result, before the trusted-BSSID override
        val trusted: Boolean,                 // user has marked this BSSID as trusted
        val nearbySameSsidCount: Int,

        val findings: List<Finding>,

        /** Composite 0-100 safety score. */
        val score: Int
    ) {
        val grade: Grade
            get() = when {
                score >= 90 -> Grade.EXCELLENT
                score >= 75 -> Grade.GOOD
                score >= 55 -> Grade.FAIR
                score >= 30 -> Grade.POOR
                else -> Grade.CRITICAL
            }

        /** Summary label to display next to the score (e.g. "WPA3-Personal · 5GHz"). */
        val summaryLabel: String
            get() = buildString {
                append(authType.display)
                bandMhz?.let {
                    append(" · ")
                    append(bandLabel(it))
                }
            }

        /** Whether this network is "safe enough" to count toward the device audit score. */
        val safeEnough: Boolean get() = notConnected || score >= 70
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun analyze(context: Context): WifiSecurityResult {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager

        @Suppress("DEPRECATION")
        val info = wifi?.connectionInfo

        // A real connection reports a non-zero network id; the phantom "disconnected"
        // WifiInfo uses -1 and a placeholder BSSID of 02:00:00:00:00:00.
        val connected = info != null &&
            info.networkId != -1 &&
            info.bssid != null &&
            info.bssid != "02:00:00:00:00:00"

        if (!connected || info == null) {
            return disconnectedResult()
        }

        val ssidRaw = info.ssid
        val bssid = info.bssid?.lowercase()
        val ssid = cleanSsid(ssidRaw)

        val scanAllowed = hasScanPermission(context)
        val scanResults: List<ScanResult> = if (scanAllowed) {
            try {
                @Suppress("DEPRECATION")
                (wifi.scanResults ?: emptyList())
            } catch (_: SecurityException) {
                emptyList()
            }
        } else emptyList()

        val matchedScan = scanResults.firstOrNull {
            it.BSSID != null && bssid != null && it.BSSID.equals(bssid, ignoreCase = true)
        }
        val capabilities = matchedScan?.capabilities
        val caps = CapabilitiesParser.parse(capabilities)

        // Prefer the API-31 security enum when available - it resolves mixed-mode
        // ambiguity that the capabilities string alone can't.
        val authType = resolveAuthType(info, caps)
        val hiddenSsid = info.hiddenSSID
        val wifiStandard = wifiStandardLabel(info)
        val bandMhz = bucketBand(info.frequency)
        val rssi = info.rssi
        val signalQuality = rssiToQuality(rssi)
        val macRandomized = isMacRandomized(info)

        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps2 = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val link = activeNetwork?.let { cm.getLinkProperties(it) }
        val captivePortal = caps2?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
        val internetValidated = caps2?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val dnsServers: List<String> = link?.dnsServers
            ?.mapNotNull { it.hostAddress }
            .orEmpty()

        // Evil-twin heuristic:
        //   If the same SSID shows up from multiple BSSIDs and the security profile
        //   changes between them (e.g. one open, one WPA2), that's a classic evil-twin
        //   fingerprint. A legitimate mesh / multi-AP deployment would advertise the
        //   same security across BSSIDs.
        val (rawEvilTwin, sameSsidCount) = detectEvilTwin(scanResults, ssid, caps)

        // A BSSID the user has explicitly trusted is never treated as an evil twin,
        // even when another AP nearby copies its SSID (the design's "add the legitimate
        // BSSID to a trusted list" recommendation).
        val trusted = bssid != null && PreferencesManager.isWifiBssidTrusted(context, bssid)
        val effectiveEvilTwin = rawEvilTwin && !trusted

        // Composite scoring + findings — shared with the per-network (nearby) path so a
        // nearby AP and the connected AP with identical capabilities score identically.
        val outcome = scoreFrom(
            caps = caps,
            authType = authType,
            bandMhz = bandMhz,
            signalQuality = signalQuality,
            hiddenSsid = hiddenSsid,
            evilTwin = effectiveEvilTwin,
            sameSsidCount = sameSsidCount,
            ssidForMessages = ssid,
            isConnected = true,
            macRandomized = macRandomized,
            captivePortal = captivePortal,
            internetValidated = internetValidated,
            dnsServers = dnsServers,
            link = link,
            trusted = trusted
        )

        return WifiSecurityResult(
            notConnected = false,
            ssid = ssid,
            bssid = bssid,
            authType = authType,
            rawCapabilities = capabilities,
            cipher = caps.cipherSummary(),
            pmfRequired = caps.pmfRequired,
            pmfCapable = caps.pmfCapable,
            wpsEnabled = caps.wpsEnabled,
            hiddenSsid = hiddenSsid,
            isEnterprise = authType == AuthType.WPA2_ENTERPRISE || authType == AuthType.WPA3_ENTERPRISE,
            bandMhz = bandMhz,
            wifiStandard = wifiStandard,
            rssiDbm = rssi,
            signalQuality = signalQuality,
            macRandomized = macRandomized,
            captivePortal = captivePortal,
            internetValidated = internetValidated,
            dnsServers = dnsServers,
            apparentEvilTwin = effectiveEvilTwin,
            apparentEvilTwinRaw = rawEvilTwin,
            trusted = trusted,
            nearbySameSsidCount = sameSsidCount,
            findings = outcome.findings,
            score = outcome.score
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Capabilities parser
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses the ScanResult capabilities string. Format is bracket-delimited tokens,
     * each listing a key-management + cipher group, e.g.
     *
     *   [WPA2-PSK-CCMP][WPA2-PSK+SAE-CCMP][MFPC][MFPR][ESS][WPS]
     *
     * Docs: https://developer.android.com/reference/android/net/wifi/ScanResult#capabilities
     */
    internal data class Capabilities(
        val hasWpa: Boolean,
        val hasWpa2: Boolean,
        val hasWpa3: Boolean,     // RSN + SAE
        val hasSae: Boolean,
        val hasOwe: Boolean,
        val hasWep: Boolean,
        val hasEap: Boolean,
        val hasPsk: Boolean,
        val hasCcmp: Boolean,
        val hasTkip: Boolean,
        val hasGcmp: Boolean,     // WPA3 192-bit
        val pmfRequired: Boolean,
        val pmfCapable: Boolean,
        val wpsEnabled: Boolean,
        val raw: String?
    ) {
        fun cipherSummary(): String? = when {
            hasCcmp && hasTkip -> "CCMP+TKIP (mixed)"
            hasGcmp -> "GCMP-256"
            hasCcmp -> "CCMP (AES)"
            hasTkip -> "TKIP (legacy)"
            hasWep -> "WEP (broken)"
            else -> null
        }

        fun isOpen(): Boolean = !hasWpa && !hasWpa2 && !hasWpa3 && !hasSae &&
            !hasOwe && !hasWep && !hasEap && !hasPsk
    }

    internal object CapabilitiesParser {
        fun parse(caps: String?): Capabilities {
            val raw = caps ?: return empty(null)
            val upper = raw.uppercase()
            return Capabilities(
                // WPA2 / WPA3 live under RSN in the IE. Android historically writes
                // "WPA2" vs "RSN"; we normalise both to WPA2 except when SAE/WPA3 is present.
                hasWpa = upper.contains("[WPA-") || upper.contains("WPA-EAP") || upper.contains("WPA-PSK"),
                hasWpa2 = upper.contains("WPA2-") || upper.contains("RSN-"),
                hasWpa3 = upper.contains("WPA3-") || upper.contains("SAE"),
                hasSae = upper.contains("SAE"),
                hasOwe = upper.contains("OWE"),
                hasWep = upper.contains("WEP"),
                hasEap = upper.contains("EAP"),
                hasPsk = upper.contains("PSK"),
                hasCcmp = upper.contains("CCMP"),
                hasTkip = upper.contains("TKIP"),
                hasGcmp = upper.contains("GCMP"),
                pmfRequired = upper.contains("MFPR"),
                pmfCapable = upper.contains("MFPC") || upper.contains("MFPR"),
                wpsEnabled = upper.contains("WPS"),
                raw = raw
            )
        }

        fun empty(raw: String?) = Capabilities(
            hasWpa = false, hasWpa2 = false, hasWpa3 = false,
            hasSae = false, hasOwe = false, hasWep = false,
            hasEap = false, hasPsk = false,
            hasCcmp = false, hasTkip = false, hasGcmp = false,
            pmfRequired = false, pmfCapable = false, wpsEnabled = false,
            raw = raw
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Evaluation helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun resolveAuthType(info: WifiInfo, caps: Capabilities): AuthType {
        // Use the API-31 enum when available - it's definitive.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val secType = try { info.currentSecurityType } catch (_: Throwable) { -1 }
            when (secType) {
                WifiInfo.SECURITY_TYPE_OPEN ->
                    return if (caps.hasOwe) AuthType.OWE else AuthType.OPEN
                WifiInfo.SECURITY_TYPE_WEP -> return AuthType.WEP
                WifiInfo.SECURITY_TYPE_PSK -> {
                    return when {
                        caps.hasSae && caps.hasPsk -> AuthType.WPA2_WPA3_MIXED
                        caps.hasWpa2 -> AuthType.WPA2_PERSONAL
                        else -> AuthType.WPA_PERSONAL
                    }
                }
                WifiInfo.SECURITY_TYPE_EAP -> return AuthType.WPA2_ENTERPRISE
                WifiInfo.SECURITY_TYPE_SAE -> return AuthType.WPA3_PERSONAL
                WifiInfo.SECURITY_TYPE_OWE -> return AuthType.OWE
                WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE,
                WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> return AuthType.WPA3_ENTERPRISE
            }
        }

        // Fall back to parsing capabilities (shared with the nearby-AP path).
        return resolveAuthTypeFromCaps(caps)
    }

    /** Resolve auth type from a parsed capabilities string alone (no WifiInfo — nearby APs). */
    private fun resolveAuthTypeFromCaps(caps: Capabilities): AuthType = when {
        caps.hasSae && caps.hasPsk -> AuthType.WPA2_WPA3_MIXED
        caps.hasSae -> AuthType.WPA3_PERSONAL
        caps.hasOwe -> AuthType.OWE
        caps.hasEap && caps.hasWpa2 -> AuthType.WPA2_ENTERPRISE
        caps.hasWpa2 && caps.hasPsk -> AuthType.WPA2_PERSONAL
        caps.hasWpa && caps.hasPsk -> AuthType.WPA_PERSONAL
        caps.hasWep -> AuthType.WEP
        caps.isOpen() -> AuthType.OPEN
        else -> AuthType.UNKNOWN
    }

    private fun evaluateAuthType(type: AuthType): Pair<Int, Finding> =
        when (type) {
            AuthType.WPA3_ENTERPRISE -> 44 to Finding(
                "auth", Severity.OK,
                "WPA3-Enterprise",
                "Strongest consumer Wi-Fi profile. 802.1X authentication plus SAE handshake, and with 192-bit GCMP on top.",
            )
            AuthType.WPA3_PERSONAL -> 42 to Finding(
                "auth", Severity.OK,
                "WPA3-Personal (SAE)",
                "SAE handshake resists offline dictionary attacks that break WPA2. Forward secrecy per session.",
            )
            AuthType.WPA2_ENTERPRISE -> 37 to Finding(
                "auth", Severity.OK,
                "WPA2-Enterprise",
                "802.1X with per-user credentials. Strong, though the underlying handshake is still vulnerable to offline brute-force if RADIUS credentials leak.",
            )
            AuthType.WPA2_WPA3_MIXED -> 34 to Finding(
                "auth", Severity.LOW,
                "WPA2 / WPA3 transition",
                "Mixed-mode network. Your device likely negotiated WPA3 (SAE), but the AP still accepts WPA2, which keeps a downgrade path open.",
                "Consider a WPA3-only SSID where possible."
            )
            AuthType.WPA2_PERSONAL -> 30 to Finding(
                "auth", Severity.LOW,
                "WPA2-Personal (PSK)",
                "Still widely acceptable, but vulnerable to offline dictionary attack if an attacker captures the 4-way handshake. A long, high-entropy password is essential.",
                "Prefer WPA3 when both the router and phone support it. Ensure the password is >16 characters."
            )
            AuthType.WPA_PERSONAL -> 15 to Finding(
                "auth", Severity.HIGH,
                "Legacy WPA (TKIP era)",
                "Pre-WPA2 protocol. TKIP is deprecated and the MIC key can be recovered in hours.",
                "Upgrade the router to WPA2 or WPA3."
            )
            AuthType.OWE -> 25 to Finding(
                "auth", Severity.MEDIUM,
                "Enhanced Open (OWE)",
                "Unauthenticated but encrypted - better than a traditional open network, but still vulnerable to active MITM since there is no identity for the AP.",
                "Use a VPN when on OWE networks for any sensitive traffic."
            )
            AuthType.WEP -> 0 to Finding(
                "auth", Severity.CRITICAL,
                "WEP - broken",
                "WEP can be cracked in under 5 minutes. Encryption is cosmetic at best.",
                "Treat this as an open network. Do not enter passwords."
            )
            AuthType.OPEN -> 0 to Finding(
                "auth", Severity.CRITICAL,
                "Open network - no encryption",
                "There is no link-layer encryption. Any device nearby can read your traffic.",
                "Use a VPN before doing anything sensitive."
            )
            AuthType.UNKNOWN -> 12 to Finding(
                "auth", Severity.MEDIUM,
                "Security type could not be determined",
                "The capabilities string could not be parsed. This is usually due to missing Wi-Fi scan permission or an unusual AP configuration.",
                "Grant Location / Nearby-devices permission so we can analyze the access point directly."
            )
        }

    private fun evaluateCipher(caps: Capabilities): Pair<Int, Finding?> {
        if (caps.isOpen()) return 0 to null
        return when {
            caps.hasGcmp -> 13 to Finding(
                "cipher", Severity.OK,
                "GCMP-256 cipher",
                "Strongest Wi-Fi cipher; required for the 192-bit WPA3-Enterprise suite.",
            )
            caps.hasCcmp && !caps.hasTkip -> 13 to Finding(
                "cipher", Severity.OK,
                "CCMP (AES) cipher",
                "AES-CCMP is the modern Wi-Fi cipher and is not affected by KRACK's most damaging variants.",
            )
            caps.hasCcmp && caps.hasTkip -> 7 to Finding(
                "cipher", Severity.MEDIUM,
                "Mixed CCMP+TKIP",
                "The AP accepts both AES and the deprecated TKIP cipher. A downgrade attacker can force TKIP on your device.",
                "Ask the network owner to disable TKIP on the router."
            )
            caps.hasTkip -> 3 to Finding(
                "cipher", Severity.HIGH,
                "TKIP cipher",
                "TKIP is deprecated by the Wi-Fi Alliance and has known practical attacks.",
                "Upgrade to a router that enforces AES/CCMP only."
            )
            else -> 0 to null
        }
    }

    private fun evaluatePmf(caps: Capabilities, type: AuthType): Pair<Int, Finding?> {
        return when {
            type == AuthType.OPEN || type == AuthType.WEP -> 0 to null  // no handshake to protect
            caps.pmfRequired -> 17 to Finding(
                "pmf", Severity.OK,
                "Protected Management Frames (required)",
                "802.11w is enforced, which blocks deauth/disassoc flooding and hardens the 4-way handshake against KRACK-style attacks.",
            )
            type == AuthType.WPA3_PERSONAL || type == AuthType.WPA3_ENTERPRISE -> 17 to Finding(
                "pmf", Severity.OK,
                "PMF implied by WPA3",
                "WPA3 mandates PMF even if the capabilities string does not advertise the MFPR token."
            )
            caps.pmfCapable -> 11 to Finding(
                "pmf", Severity.LOW,
                "Protected Management Frames (optional)",
                "The AP supports 802.11w but does not require it, so a legacy client on the same BSS weakens the protection for everyone.",
                "Enable PMF-required on the router (sometimes called \"Management Frame Protection: Mandatory\")."
            )
            // Partial credit: most WPA2 routers don't advertise the MFPR token even when they apply
            // 802.11w. We can't confirm it, so we neither award full marks nor zero it out.
            else -> 6 to Finding(
                "pmf", Severity.LOW,
                "Management Frame Protection not advertised",
                "This network does not advertise 802.11w. Many WPA2 routers still apply it; without the flag we can't confirm it, so a deauth / MITM path can't be fully ruled out.",
                "If this is your router, enable PMF (Management Frame Protection) and prefer WPA3 where possible."
            )
        }
    }

    private fun evaluateBand(mhz: Int?): Pair<Int, Finding?> {
        return when (mhz) {
            6000 -> 8 to Finding(
                "band", Severity.OK,
                "6 GHz (Wi-Fi 6E)",
                "The 6 GHz band only admits WPA3 clients and has a much less crowded spectrum.",
            )
            5000 -> 6 to Finding(
                "band", Severity.OK,
                "5 GHz",
                "Less congested than 2.4 GHz and usually less reach, which mildly limits who can see your traffic.",
            )
            2400 -> 3 to Finding(
                "band", Severity.INFO,
                "2.4 GHz",
                "Longest range and highest congestion. Also the band with the widest legacy-client support, which historically slows security upgrades.",
            )
            null -> 0 to null
            else -> 0 to null
        }
    }

    private fun evaluateDns(servers: List<String>, link: LinkProperties?): Finding? {
        if (servers.isEmpty()) {
            return Finding(
                "dns_none", Severity.LOW,
                "No DNS servers reported",
                "We could not enumerate DNS servers for this network. DNS hijacking by the AP cannot be ruled out.",
            )
        }
        val pushedPublic = servers.any { it == "8.8.8.8" || it == "1.1.1.1" || it == "9.9.9.9" }
        val privateOnly = servers.all { it.startsWith("10.") || it.startsWith("192.168.") || it.startsWith("172.") }
        val usesPrivateDns = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            link?.isPrivateDnsActive == true

        return when {
            usesPrivateDns -> Finding(
                "dns_ok", Severity.OK,
                "Private DNS active",
                "Android Private DNS (DoT) is enforcing encrypted name resolution that the AP cannot snoop or rewrite.",
            )
            pushedPublic -> Finding(
                "dns_public", Severity.INFO,
                "AP is pushing public DNS",
                "DNS queries go directly to a public resolver (8.8.8.8 / 1.1.1.1 / 9.9.9.9) rather than the AP itself. The AP still sees *which* resolver you use and can still intercept cleartext :53 traffic.",
                "Enable Android Private DNS (Settings → Network → Private DNS) for encrypted resolution."
            )
            privateOnly -> Finding(
                "dns_ap", Severity.LOW,
                "AP is acting as DNS",
                "All DNS queries terminate at the router (${servers.joinToString()}). A malicious or compromised router can silently redirect any domain.",
                "Enable Private DNS on Android to bypass the router's resolver."
            )
            else -> null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Heuristics
    // ─────────────────────────────────────────────────────────────────────────

    private fun detectEvilTwin(
        scanResults: List<ScanResult>,
        ssid: String?,
        currentCaps: Capabilities
    ): Pair<Boolean, Int> {
        if (ssid.isNullOrBlank()) return false to 0
        @Suppress("DEPRECATION")
        val sameName = scanResults.filter { cleanSsid(it.SSID) == ssid }
        if (sameName.size <= 1) return false to sameName.size

        // Compare the *security shape* of every BSSID sharing this SSID.
        // Any divergence (e.g. one AP open, one AP WPA2) is a strong evil-twin signal.
        val shapes = sameName.mapNotNull { it.capabilities }.map { shapeOf(it) }.toSet()
        val open = sameName.any { CapabilitiesParser.parse(it.capabilities).isOpen() }
        val heterogeneousAuth = shapes.size > 1
        val downgradeVariant = open && !currentCaps.isOpen()

        return (heterogeneousAuth || downgradeVariant) to sameName.size
    }

    private fun shapeOf(capabilities: String): String {
        val c = CapabilitiesParser.parse(capabilities)
        return buildString {
            if (c.hasWpa3 || c.hasSae) append("3")
            if (c.hasWpa2) append("2")
            if (c.hasWpa) append("1")
            if (c.hasOwe) append("O")
            if (c.hasWep) append("E")
            if (c.isOpen()) append("0")
            if (c.hasEap) append("x")
            if (c.hasPsk) append("p")
            if (c.pmfRequired) append("R") else if (c.pmfCapable) append("c")
        }
    }

    private fun isMacRandomized(info: WifiInfo): Boolean? {
        // Android doesn't expose a direct API to check the randomization setting per-SSID
        // without MODIFY_PHONE_STATE. We use a heuristic: a locally-administered MAC
        // (second-least-significant bit of the first octet is 1) is almost certainly
        // the randomized one - factory MACs are globally administered.
        val mac = info.macAddress ?: return null
        if (mac == "02:00:00:00:00:00") return null // permission denied / not available
        val firstOctet = mac.split(":").firstOrNull()
            ?.toIntOrNull(16) ?: return null
        return (firstOctet and 0x02) != 0
    }

    private fun wifiStandardLabel(info: WifiInfo): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return when (info.wifiStandard) {
            ScanResult.WIFI_STANDARD_LEGACY -> "802.11 a/b/g"
            ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4"
            ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5"
            ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6"
            ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7"
            else -> null
        }
    }

    private fun bucketBand(freqMhz: Int): Int? = when {
        freqMhz in 2400..2500 -> 2400
        freqMhz in 4900..5900 -> 5000
        freqMhz in 5925..7125 -> 6000
        freqMhz <= 0 -> null
        else -> freqMhz
    }

    private fun rssiToQuality(rssi: Int): Int {
        // Canonical Android mapping from RSSI (dBm) to 0-100 quality.
        // -50 dBm and stronger → 100; -100 dBm and weaker → 0.
        @Suppress("DEPRECATION")
        return WifiManager.calculateSignalLevel(rssi, 101).coerceIn(0, 100)
    }

    private fun cleanSsid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
            raw.substring(1, raw.length - 1)
        } else raw
        if (trimmed == "<unknown ssid>" || trimmed == "0x") return null
        return trimmed
    }

    private fun hasScanPermission(context: Context): Boolean {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasNearby = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        return hasFineLocation || hasNearby
    }

    private fun disconnectedResult(): WifiSecurityResult = WifiSecurityResult(
        notConnected = true,
        ssid = null, bssid = null,
        authType = AuthType.UNKNOWN,
        rawCapabilities = null,
        cipher = null,
        pmfRequired = false, pmfCapable = false,
        wpsEnabled = false, hiddenSsid = false, isEnterprise = false,
        bandMhz = null, wifiStandard = null,
        rssiDbm = null, signalQuality = 0,
        macRandomized = null,
        captivePortal = false, internetValidated = false,
        dnsServers = emptyList(),
        apparentEvilTwin = false, apparentEvilTwinRaw = false, trusted = false,
        nearbySameSsidCount = 0,
        findings = listOf(
            Finding(
                "offline",
                Severity.OK,
                "Not connected to Wi-Fi",
                "Cellular and offline connections are not analyzed here. Connect to a Wi-Fi network to see its security posture.",
            )
        ),
        score = 100 // not on Wi-Fi → no Wi-Fi exposure
    )

    internal val AuthType.display: String
        get() = when (this) {
            AuthType.OPEN -> "Open"
            AuthType.OWE -> "Enhanced Open"
            AuthType.WEP -> "WEP"
            AuthType.WPA_PERSONAL -> "WPA"
            AuthType.WPA2_PERSONAL -> "WPA2-Personal"
            AuthType.WPA2_ENTERPRISE -> "WPA2-Enterprise"
            AuthType.WPA3_PERSONAL -> "WPA3-Personal"
            AuthType.WPA3_ENTERPRISE -> "WPA3-Enterprise"
            AuthType.WPA2_WPA3_MIXED -> "WPA2/WPA3"
            AuthType.UNKNOWN -> "Unknown"
        }

    internal fun bandLabel(mhz: Int): String = when (mhz) {
        2400 -> "2.4 GHz"
        5000 -> "5 GHz"
        6000 -> "6 GHz"
        else -> "${mhz} MHz"
    }

    /** Short band string for a row meta line: "2.4" / "5" / "6". */
    fun bandShort(mhz: Int?): String = when (mhz) {
        2400 -> "2.4"
        5000 -> "5"
        6000 -> "6"
        null -> "—"
        else -> "${mhz / 1000}"
    }

    /** Short security label for the overview chip (mirrors the design's SecChip). */
    fun authShortLabel(t: AuthType): String = when (t) {
        AuthType.OPEN -> "OPEN"
        AuthType.OWE -> "OWE"
        AuthType.WEP -> "WEP"
        AuthType.WPA_PERSONAL -> "WPA"
        AuthType.WPA2_PERSONAL -> "WPA2"
        AuthType.WPA2_ENTERPRISE -> "WPA2-E"
        AuthType.WPA3_PERSONAL -> "WPA3"
        AuthType.WPA3_ENTERPRISE -> "WPA3-E"
        AuthType.WPA2_WPA3_MIXED -> "WPA2/3"
        AuthType.UNKNOWN -> "?"
    }

    /** Full security label for the detail "Security" row (e.g. "WPA2-Personal"). */
    fun authLabel(t: AuthType): String = t.display

    enum class SecRisk { BAD, WARN, OK }

    /** Risk band that colours the security chip (bad = red, warn = amber, ok = green). */
    fun securityRisk(t: AuthType): SecRisk = when (t) {
        AuthType.OPEN, AuthType.WEP -> SecRisk.BAD
        AuthType.WPA_PERSONAL, AuthType.WPA2_PERSONAL, AuthType.UNKNOWN -> SecRisk.WARN
        AuthType.OWE, AuthType.WPA2_ENTERPRISE, AuthType.WPA3_PERSONAL,
        AuthType.WPA3_ENTERPRISE, AuthType.WPA2_WPA3_MIXED -> SecRisk.OK
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared scoring core — used by both analyze() (connected) and scanNearby()
    // (per-AP), so a nearby AP and the connected AP with identical capabilities
    // produce the SAME 0-100 score, findings, and grade.
    // ─────────────────────────────────────────────────────────────────────────

    internal data class ScoreOutcome(val score: Int, val findings: List<Finding>)

    internal fun scoreFrom(
        caps: Capabilities,
        authType: AuthType,
        bandMhz: Int?,
        signalQuality: Int,
        hiddenSsid: Boolean,
        evilTwin: Boolean,
        sameSsidCount: Int,
        ssidForMessages: String?,
        isConnected: Boolean,
        macRandomized: Boolean? = null,
        captivePortal: Boolean = false,
        internetValidated: Boolean = true,
        dnsServers: List<String> = emptyList(),
        link: LinkProperties? = null,
        trusted: Boolean = false
    ): ScoreOutcome {
        val findings = mutableListOf<Finding>()
        var score = 0

        // 1. Auth type (weight 44)
        val (authPts, authFinding) = evaluateAuthType(authType)
        score += authPts
        findings += authFinding

        // 2. Cipher (weight 13)
        val (cipherPts, cipherFinding) = evaluateCipher(caps)
        score += cipherPts
        cipherFinding?.let { findings += it }

        // 3. PMF (weight 17, partial credit when not advertised)
        val (pmfPts, pmfFinding) = evaluatePmf(caps, authType)
        score += pmfPts
        pmfFinding?.let { findings += it }

        // 4. Band (weight 8)
        val (bandPts, bandFinding) = evaluateBand(bandMhz)
        score += bandPts
        bandFinding?.let { findings += it }

        // 5. Signal (weight 4)
        score += (signalQuality * 4) / 100

        // 6. MAC randomization (weight 7) — only meaningful for the *connected* client.
        // Android 10+ hides the client MAC from apps (getMacAddress → 02:00:..), so detection is
        // usually inconclusive. Since the OS randomizes the MAC per-network *by default*, we credit
        // that default when we can't read it, rather than leaving 7 points permanently unattainable
        // (which would silently cap every modern device's score at 93).
        if (isConnected) {
            when (macRandomized) {
                true -> {
                    score += 7
                    findings += Finding(
                        "mac_random",
                        Severity.OK,
                        "MAC randomization active",
                        "Your device is presenting a random MAC to this network, which prevents persistent tracking across visits.",
                    )
                }
                false -> findings += Finding(
                    "mac_random",
                    Severity.LOW,
                    "MAC address not randomized",
                    "Your device is exposing its factory MAC address to this AP - that allows the network owner to track you across sessions.",
                    "In Wi-Fi settings for this network, enable MAC randomization."
                )
                null -> {
                    score += 7
                    findings += Finding(
                        "mac_random",
                        Severity.INFO,
                        "MAC randomization (Android default)",
                        "Android hides the Wi-Fi MAC from apps, so we can't read it directly. Modern Android randomizes the MAC per network by default - which is what we assume here.",
                    )
                }
            }
        }

        // 7. Hidden SSID
        if (hiddenSsid) {
            findings += Finding(
                "hidden_ssid",
                Severity.LOW,
                "Hidden network",
                "This network is configured to not broadcast its SSID. This offers no real security and forces your phone to continually probe for it, leaking your preferred-network list to everyone within range.",
                "Ask the network owner to broadcast the SSID - hiding it is not a security feature."
            )
        }

        // 8. WPS
        if (caps.wpsEnabled) {
            findings += Finding(
                "wps",
                Severity.MEDIUM,
                "WPS is enabled",
                "Wi-Fi Protected Setup (WPS) is advertised on this AP. The WPS PIN mechanism is brute-forceable in roughly 10 hours using tools like Reaver, effectively bypassing a strong WPA2 password.",
                "Disable WPS in the router's admin page."
            )
        } else {
            score += 7
        }

        // 9. Evil-twin
        if (evilTwin) {
            score -= 24
            findings += Finding(
                "evil_twin",
                Severity.CRITICAL,
                "Possible evil-twin AP detected",
                "Multiple access points within range are broadcasting the SSID \"$ssidForMessages\" but with different security configurations. This is a classic fingerprint for a rogue AP attempting to impersonate the legitimate network and harvest credentials.",
                "Disconnect immediately. Verify with the network owner. If you often connect here, consider adding the legitimate BSSID to a trusted list."
            )
        } else if (sameSsidCount > 1) {
            findings += Finding(
                "multi_bssid",
                Severity.INFO,
                "$sameSsidCount access points share this SSID",
                "This is normal for enterprise or mesh deployments (roaming between APs). Their security profile is consistent, so we do not flag this as suspicious.",
            )
        }

        // 9b. Trusted BSSID — the user vouched for this AP, so suppress evil-twin alarms.
        if (trusted) {
            findings += Finding(
                "trusted",
                Severity.OK,
                "Trusted access point",
                "You marked this BSSID as trusted, so S'CAN will not flag it as an evil twin even when another nearby access point copies its network name.",
            )
        }

        // 10. Captive portal — only observable on the connected network
        if (isConnected) {
            if (captivePortal) {
                findings += Finding(
                    "captive",
                    Severity.MEDIUM,
                    "Captive portal intercepting traffic",
                    "This network is routing you through a login / terms-of-service page. Until you complete it, all outbound traffic is redirected through the gateway - avoid entering real passwords for unrelated services while the portal is active.",
                    "Complete the portal only if you trust the venue. Prefer logging in with throwaway credentials."
                )
            } else if (!internetValidated) {
                findings += Finding(
                    "no_internet",
                    Severity.INFO,
                    "Internet not validated",
                    "The Android connectivity check did not confirm full internet access on this network. This can be benign (slow DHCP, DNS hiccup) or indicate a constrained network.",
                )
            }
        }

        // 11. Open / OWE public network → remind about MITM
        if (authType == AuthType.OPEN) {
            findings += Finding(
                "open_mitm",
                Severity.HIGH,
                "Unencrypted network - MITM risk",
                "All traffic between your phone and the AP is sent in the clear. Anyone within radio range can passively capture DNS, cleartext HTTP, metadata from TLS handshakes (SNI), and inject ARP / DNS spoofing attacks.",
                "Avoid entering credentials. Use a VPN before accessing email, banking, or cloud accounts."
            )
        }

        // 12. WEP is permanently broken
        if (authType == AuthType.WEP) {
            findings += Finding(
                "wep_broken",
                Severity.CRITICAL,
                "WEP encryption is broken",
                "WEP can be cracked in minutes with publicly available tools. Treat this network as equivalent to an open network.",
                "Do not send credentials or sensitive data. Encourage the network owner to upgrade to WPA2/WPA3."
            )
        }

        // 13. WPA2 transition mode warning
        if (authType == AuthType.WPA2_WPA3_MIXED) {
            findings += Finding(
                "transition",
                Severity.LOW,
                "WPA2/WPA3 transition mode",
                "This AP accepts both WPA2 and WPA3. A downgrade attacker in range can force your device to fall back to WPA2, losing the dictionary-attack protection of SAE.",
                "If your device supports WPA3 exclusively, prefer WPA3-only networks."
            )
        }

        // 14. DNS hygiene — only observable on the connected network
        if (isConnected) {
            evaluateDns(dnsServers, link)?.let { findings += it }
        }

        score = score.coerceIn(0, 100)
        return ScoreOutcome(score, findings.sortedBy { it.severity.ordinal })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Nearby networks (overview list) — real cached scan results only
    // ─────────────────────────────────────────────────────────────────────────

    data class NearbyResult(
        /** The connected network, or null when not on Wi-Fi. */
        val connected: WifiNetwork?,
        /** Nearby access points (real cached scan results only), evil-twins flagged. */
        val nearby: List<WifiNetwork>
    )

    /**
     * Builds the overview model: the connected network (live) plus the nearby list.
     * The nearby list is the device's cached scan results — real networks only. When
     * those are empty or scan permission is missing, the nearby list is empty and the
     * overview shows its empty state (no mock/sample data).
     */
    @SuppressLint("MissingPermission")
    fun scanNearby(context: Context): NearbyResult {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager

        // Connected network — reuse the full connected analysis, then map to WifiNetwork.
        val connectedResult = analyze(context)
        @Suppress("DEPRECATION")
        val connectedFreq = wifi?.connectionInfo?.frequency ?: 0
        val connected: WifiNetwork? = if (connectedResult.notConnected) null else WifiNetwork(
            ssid = connectedResult.ssid ?: "Unknown network",
            bssid = connectedResult.bssid ?: "",
            connected = true,
            authType = connectedResult.authType,
            cipher = connectedResult.cipher,
            pmfRequired = connectedResult.pmfRequired,
            pmfCapable = connectedResult.pmfCapable,
            wpsEnabled = connectedResult.wpsEnabled,
            hiddenSsid = connectedResult.hiddenSsid,
            bandMhz = connectedResult.bandMhz,
            channel = freqToChannel(connectedFreq),
            wifiStandard = connectedResult.wifiStandard,
            vendor = OuiLookup.vendorFor(context, connectedResult.bssid),
            rssiDbm = connectedResult.rssiDbm ?: 0,
            signalQuality = connectedResult.signalQuality,
            macRandomized = connectedResult.macRandomized,
            captivePortal = connectedResult.captivePortal,
            internetValidated = connectedResult.internetValidated,
            dnsServers = connectedResult.dnsServers,
            evilTwin = connectedResult.apparentEvilTwin,
            evilTwinRaw = connectedResult.apparentEvilTwinRaw,
            trusted = connectedResult.trusted,
            sameSsidCount = connectedResult.nearbySameSsidCount,
            score = connectedResult.score,
            findings = connectedResult.findings
        )

        // Nearby networks — read cached scan results (guarded by permission).
        val scanAllowed = hasScanPermission(context)
        val scanResults: List<ScanResult> = if (scanAllowed && wifi != null) {
            try {
                @Suppress("DEPRECATION")
                (wifi.scanResults ?: emptyList())
            } catch (_: SecurityException) {
                emptyList()
            }
        } else emptyList()

        val connectedBssid = connected?.bssid?.lowercase()
        val nearby = scanResults.mapNotNull { sr ->
            val bssid = sr.BSSID?.lowercase() ?: return@mapNotNull null
            if (bssid == "02:00:00:00:00:00") return@mapNotNull null
            if (connectedBssid != null && bssid == connectedBssid) return@mapNotNull null
            @Suppress("DEPRECATION")
            val ssid = cleanSsid(sr.SSID)
            networkFromScan(context, sr, ssid, bssid, scanResults)
        }.distinctBy { it.bssid }

        // Real networks only — no mock/sample fallback. When the scan is empty or scan
        // permission is missing, the nearby list is genuinely empty and the overview
        // shows its empty state.
        return NearbyResult(connected, nearby)
    }

    private fun networkFromScan(
        context: Context,
        sr: ScanResult,
        ssid: String?,
        bssid: String,
        all: List<ScanResult>
    ): WifiNetwork {
        val caps = CapabilitiesParser.parse(sr.capabilities)
        val authType = resolveAuthTypeFromCaps(caps)
        val bandMhz = bucketBand(sr.frequency)
        val signalQuality = rssiToQuality(sr.level)
        val hidden = ssid.isNullOrBlank()
        val (rawEvilTwin, sameSsidCount) = detectEvilTwin(all, ssid, caps)
        val trusted = PreferencesManager.isWifiBssidTrusted(context, bssid)
        val effectiveEvilTwin = rawEvilTwin && !trusted
        val outcome = scoreFrom(
            caps = caps,
            authType = authType,
            bandMhz = bandMhz,
            signalQuality = signalQuality,
            hiddenSsid = hidden,
            evilTwin = effectiveEvilTwin,
            sameSsidCount = sameSsidCount,
            ssidForMessages = ssid,
            isConnected = false,
            trusted = trusted
        )
        return WifiNetwork(
            ssid = ssid ?: "— Hidden network —",
            bssid = bssid,
            connected = false,
            authType = authType,
            cipher = caps.cipherSummary(),
            pmfRequired = caps.pmfRequired,
            pmfCapable = caps.pmfCapable,
            wpsEnabled = caps.wpsEnabled,
            hiddenSsid = hidden,
            bandMhz = bandMhz,
            channel = freqToChannel(sr.frequency),
            wifiStandard = scanResultStandard(sr),
            vendor = OuiLookup.vendorFor(context, bssid),
            rssiDbm = sr.level,
            signalQuality = signalQuality,
            macRandomized = null,
            captivePortal = false,
            internetValidated = true,
            dnsServers = emptyList(),
            evilTwin = effectiveEvilTwin,
            evilTwinRaw = rawEvilTwin,
            trusted = trusted,
            sameSsidCount = sameSsidCount,
            score = outcome.score,
            findings = outcome.findings
        )
    }

    private fun freqToChannel(freqMhz: Int): Int? = when {
        freqMhz <= 0 -> null
        freqMhz == 2484 -> 14
        freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
        freqMhz in 5000..5895 -> (freqMhz - 5000) / 5
        freqMhz in 5925..7125 -> (freqMhz - 5950) / 5   // 6 GHz
        else -> null
    }

    private fun scanResultStandard(sr: ScanResult): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return when (sr.wifiStandard) {
            ScanResult.WIFI_STANDARD_LEGACY -> "802.11 a/b/g"
            ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4"
            ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5"
            ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6"
            ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7"
            else -> null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Trust toggle — recompute a network's evil-twin flag, findings and score for a
    // new trust state without needing fresh scan results. Used by the detail screen
    // as a fallback when the network is no longer in range (or is sample data).
    // ─────────────────────────────────────────────────────────────────────────

    fun recompute(net: WifiNetwork, trusted: Boolean): WifiNetwork {
        val caps = capsOf(net)
        val effectiveEvilTwin = net.evilTwinRaw && !trusted
        val outcome = scoreFrom(
            caps = caps,
            authType = net.authType,
            bandMhz = net.bandMhz,
            signalQuality = net.signalQuality,
            hiddenSsid = net.hiddenSsid,
            evilTwin = effectiveEvilTwin,
            sameSsidCount = net.sameSsidCount,
            ssidForMessages = net.ssid,
            isConnected = net.connected,
            macRandomized = net.macRandomized,
            captivePortal = net.captivePortal,
            internetValidated = net.internetValidated,
            dnsServers = net.dnsServers,
            link = null,
            trusted = trusted
        )
        return net.copy(
            evilTwin = effectiveEvilTwin,
            trusted = trusted,
            score = outcome.score,
            findings = outcome.findings
        )
    }

    /** Best-effort reconstruction of a [Capabilities] from an already-analysed network. */
    private fun capsOf(net: WifiNetwork): Capabilities {
        val cipher = net.cipher?.uppercase().orEmpty()
        val t = net.authType
        return Capabilities(
            hasWpa = t == AuthType.WPA_PERSONAL,
            hasWpa2 = t == AuthType.WPA2_PERSONAL || t == AuthType.WPA2_ENTERPRISE || t == AuthType.WPA2_WPA3_MIXED,
            hasWpa3 = t == AuthType.WPA3_PERSONAL || t == AuthType.WPA3_ENTERPRISE || t == AuthType.WPA2_WPA3_MIXED,
            hasSae = t == AuthType.WPA3_PERSONAL || t == AuthType.WPA3_ENTERPRISE || t == AuthType.WPA2_WPA3_MIXED,
            hasOwe = t == AuthType.OWE,
            hasWep = t == AuthType.WEP || cipher.contains("WEP"),
            hasEap = t == AuthType.WPA2_ENTERPRISE || t == AuthType.WPA3_ENTERPRISE,
            hasPsk = t == AuthType.WPA_PERSONAL || t == AuthType.WPA2_PERSONAL || t == AuthType.WPA2_WPA3_MIXED,
            hasCcmp = cipher.contains("CCMP"),
            hasTkip = cipher.contains("TKIP"),
            hasGcmp = cipher.contains("GCMP"),
            pmfRequired = net.pmfRequired,
            pmfCapable = net.pmfCapable,
            wpsEnabled = net.wpsEnabled,
            raw = null
        )
    }
}

/**
 * A single Wi-Fi network (connected or nearby) for the overview list + detail screen.
 *
 * Parcelable so the overview can hand a fully-analysed network to the detail Activity
 * without re-scanning — scan results differ between Activities, and sample-fallback
 * networks are not in the live scan at all. Findings are carried with the model so the
 * detail screen shows exactly the findings that produced the score.
 */
@Parcelize
data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val connected: Boolean,
    val authType: WifiSecurityAnalyzer.AuthType,
    val cipher: String?,
    val pmfRequired: Boolean,
    val pmfCapable: Boolean,
    val wpsEnabled: Boolean,
    val hiddenSsid: Boolean,
    val bandMhz: Int?,
    val channel: Int?,
    val wifiStandard: String?,
    val vendor: String?,
    val rssiDbm: Int,
    val signalQuality: Int,
    val macRandomized: Boolean?,
    val captivePortal: Boolean,
    val internetValidated: Boolean,
    val dnsServers: List<String>,
    val evilTwin: Boolean,                 // effective: raw detection AND not trusted
    val sameSsidCount: Int,
    val score: Int,
    val findings: List<WifiSecurityAnalyzer.Finding>,
    val evilTwinRaw: Boolean = false,      // raw heuristic, before the trusted override
    val trusted: Boolean = false           // user marked this BSSID as trusted
) : Parcelable {

    /** Grade band — identical thresholds to WifiSecurityResult.grade. */
    val grade: WifiSecurityAnalyzer.Grade
        get() = when {
            score >= 90 -> WifiSecurityAnalyzer.Grade.EXCELLENT
            score >= 75 -> WifiSecurityAnalyzer.Grade.GOOD
            score >= 55 -> WifiSecurityAnalyzer.Grade.FAIR
            score >= 30 -> WifiSecurityAnalyzer.Grade.POOR
            else -> WifiSecurityAnalyzer.Grade.CRITICAL
        }

    /** A network counts as a threat if it is an evil twin, open, or WEP. */
    val isThreat: Boolean
        get() = evilTwin ||
            authType == WifiSecurityAnalyzer.AuthType.OPEN ||
            authType == WifiSecurityAnalyzer.AuthType.WEP
}

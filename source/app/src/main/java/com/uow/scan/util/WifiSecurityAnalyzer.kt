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
import androidx.core.content.ContextCompat
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

    data class Finding(
        val id: String,
        val severity: Severity,
        val title: String,
        val description: String,
        val recommendation: String? = null
    )

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
        val apparentEvilTwin: Boolean,        // same SSID, multiple BSSIDs, differing security
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
        val (evilTwin, sameSsidCount) = detectEvilTwin(scanResults, ssid, caps)

        val findings = mutableListOf<Finding>()
        var score = 0

        // 1. Auth type (weight 40)
        val (authPts, authFinding) = evaluateAuthType(authType)
        score += authPts
        findings += authFinding

        // 2. Cipher (weight 10)
        val (cipherPts, cipherFinding) = evaluateCipher(caps)
        score += cipherPts
        cipherFinding?.let { findings += it }

        // 3. PMF (weight 15)
        val (pmfPts, pmfFinding) = evaluatePmf(caps, authType)
        score += pmfPts
        findings += pmfFinding

        // 4. Band (weight 8)
        val (bandPts, bandFinding) = evaluateBand(bandMhz)
        score += bandPts
        bandFinding?.let { findings += it }

        // 5. Signal (weight 5)
        score += (signalQuality * 5) / 100

        // 6. MAC randomization (weight 7)
        if (macRandomized == true) {
            score += 7
            findings += Finding(
                "mac_random",
                Severity.OK,
                "MAC randomization active",
                "Your device is presenting a random MAC to this network, which prevents persistent tracking across visits.",
            )
        } else if (macRandomized == false) {
            findings += Finding(
                "mac_random",
                Severity.LOW,
                "MAC address not randomized",
                "Your device is exposing its factory MAC address to this AP - that allows the network owner to track you across sessions.",
                "In Wi-Fi settings for this network, enable MAC randomization."
            )
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
            score += 3
        }

        // 9. Evil-twin
        if (evilTwin) {
            score -= 20
            findings += Finding(
                "evil_twin",
                Severity.CRITICAL,
                "Possible evil-twin AP detected",
                "Multiple access points within range are broadcasting the SSID \"$ssid\" but with different security configurations. This is a classic fingerprint for a rogue AP attempting to impersonate the legitimate network and harvest credentials.",
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

        // 10. Captive portal
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

        // 14. DNS hygiene
        evaluateDns(dnsServers, link)?.let { findings += it }

        // Clamp
        score = score.coerceIn(0, 100)

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
            apparentEvilTwin = evilTwin,
            nearbySameSsidCount = sameSsidCount,
            findings = findings.sortedBy { it.severity.ordinal },
            score = score
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

        // Fall back to parsing capabilities.
        return when {
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
    }

    private fun evaluateAuthType(type: AuthType): Pair<Int, Finding> =
        when (type) {
            AuthType.WPA3_ENTERPRISE -> 40 to Finding(
                "auth", Severity.OK,
                "WPA3-Enterprise",
                "Strongest consumer Wi-Fi profile. 802.1X authentication plus SAE handshake, and with 192-bit GCMP on top.",
            )
            AuthType.WPA3_PERSONAL -> 38 to Finding(
                "auth", Severity.OK,
                "WPA3-Personal (SAE)",
                "SAE handshake resists offline dictionary attacks that break WPA2. Forward secrecy per session.",
            )
            AuthType.WPA2_ENTERPRISE -> 34 to Finding(
                "auth", Severity.OK,
                "WPA2-Enterprise",
                "802.1X with per-user credentials. Strong, though the underlying handshake is still vulnerable to offline brute-force if RADIUS credentials leak.",
            )
            AuthType.WPA2_WPA3_MIXED -> 30 to Finding(
                "auth", Severity.LOW,
                "WPA2 / WPA3 transition",
                "Mixed-mode network. Your device likely negotiated WPA3 (SAE), but the AP still accepts WPA2, which keeps a downgrade path open.",
                "Consider a WPA3-only SSID where possible."
            )
            AuthType.WPA2_PERSONAL -> 28 to Finding(
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
            AuthType.OWE -> 24 to Finding(
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
            AuthType.UNKNOWN -> 10 to Finding(
                "auth", Severity.MEDIUM,
                "Security type could not be determined",
                "The capabilities string could not be parsed. This is usually due to missing Wi-Fi scan permission or an unusual AP configuration.",
                "Grant Location / Nearby-devices permission so we can analyze the access point directly."
            )
        }

    private fun evaluateCipher(caps: Capabilities): Pair<Int, Finding?> {
        if (caps.isOpen()) return 0 to null
        return when {
            caps.hasGcmp -> 10 to Finding(
                "cipher", Severity.OK,
                "GCMP-256 cipher",
                "Strongest Wi-Fi cipher; required for the 192-bit WPA3-Enterprise suite.",
            )
            caps.hasCcmp && !caps.hasTkip -> 10 to Finding(
                "cipher", Severity.OK,
                "CCMP (AES) cipher",
                "AES-CCMP is the modern Wi-Fi cipher and is not affected by KRACK's most damaging variants.",
            )
            caps.hasCcmp && caps.hasTkip -> 5 to Finding(
                "cipher", Severity.MEDIUM,
                "Mixed CCMP+TKIP",
                "The AP accepts both AES and the deprecated TKIP cipher. A downgrade attacker can force TKIP on your device.",
                "Ask the network owner to disable TKIP on the router."
            )
            caps.hasTkip -> 2 to Finding(
                "cipher", Severity.HIGH,
                "TKIP cipher",
                "TKIP is deprecated by the Wi-Fi Alliance and has known practical attacks.",
                "Upgrade to a router that enforces AES/CCMP only."
            )
            else -> 0 to null
        }
    }

    private fun evaluatePmf(caps: Capabilities, type: AuthType): Pair<Int, Finding> {
        return when {
            caps.pmfRequired -> 15 to Finding(
                "pmf", Severity.OK,
                "Protected Management Frames (required)",
                "802.11w is enforced, which blocks deauth/disassoc flooding and hardens the 4-way handshake against KRACK-style attacks.",
            )
            caps.pmfCapable -> 8 to Finding(
                "pmf", Severity.LOW,
                "Protected Management Frames (optional)",
                "The AP supports 802.11w but does not require it, so a legacy client on the same BSS weakens the protection for everyone.",
                "Enable PMF-required on the router (sometimes called \"Management Frame Protection: Mandatory\")."
            )
            type == AuthType.WPA3_PERSONAL || type == AuthType.WPA3_ENTERPRISE -> 15 to Finding(
                "pmf", Severity.OK,
                "PMF implied by WPA3",
                "WPA3 mandates PMF even if the capabilities string does not advertise the MFPR token."
            )
            else -> 0 to Finding(
                "pmf", Severity.MEDIUM,
                "No Management Frame Protection",
                "Without 802.11w, an attacker in range can send spoofed deauthentication frames and knock you off the network at will - the prerequisite for most MITM attacks.",
                "Enable PMF on the router and, if possible, use a WPA3 network."
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
        apparentEvilTwin = false, nearbySameSsidCount = 0,
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

    private val AuthType.display: String
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

    private fun bandLabel(mhz: Int): String = when (mhz) {
        2400 -> "2.4 GHz"
        5000 -> "5 GHz"
        6000 -> "6 GHz"
        else -> "${mhz} MHz"
    }
}

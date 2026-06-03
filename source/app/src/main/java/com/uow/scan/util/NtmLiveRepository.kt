package com.uow.scan.util

import android.content.Context
import com.uow.scan.model.TrackerInfo
import com.uow.scan.util.NtmDemoData.AggStats
import com.uow.scan.util.NtmDemoData.Dest
import com.uow.scan.util.NtmDemoData.Finding
import com.uow.scan.util.NtmDemoData.FindingIcon
import com.uow.scan.util.NtmDemoData.NtmApp
import com.uow.scan.util.NtmDemoData.Posture
import com.uow.scan.util.NtmDemoData.Tone
import com.uow.scan.util.NtmDemoData.Tracker

/**
 * Live NTM data source. Merges:
 *  - real per-app data usage from [DataUsageHelper] (NetworkStats `querySummary` — works cross-UID
 *    on Samsung/OneUI, unlike the fg/bg `queryDetails` split, which is privacy-locked there), and
 *  - the domains each app actually resolved on the unified tunnel ([NtmStore]),
 *  - classified into trackers by [TrackerDomainMatcher] (Exodus network signatures),
 *
 * into the same screen shapes [NtmDemoData] produces. Honesty (DNS-only, Stages 0–3): per-app
 * bytes, the resolved domains, and which are trackers are REAL; per-connection encryption, ports,
 * ASN/geo and per-destination bytes are left unset (proto = "", bytes = 0) and rendered in a
 * DNS-only style until the Stage-4 capture forwarder fills them. Nothing is fabricated.
 *
 * The app list is computed once per instance (lazy); create a fresh instance to refresh.
 */
class NtmLiveRepository(private val context: Context) : NtmDataSource {

    private val pm = context.packageManager
    private val now = System.currentTimeMillis()
    private val windowStart = now - WINDOW_MS

    private val appList: List<NtmApp> by lazy { build() }

    override fun apps(): List<NtmApp> = appList

    /** The live app by id (package), or a REAL empty placeholder — never demo data, so opening a
     *  detail for an app with no captured activity shows the real (empty) app, not a sample. */
    fun appById(appId: String?): NtmApp =
        appList.firstOrNull { it.id == appId } ?: run {
            val pkg = appId ?: "—"
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                .getOrDefault(pkg.substringAfterLast('.'))
            NtmApp(
                id = pkg, name = label, pkg = pkg, mono = monogram(label), brand = brandColor(pkg),
                fg = 0L, bg = 0L, wifi = 0L, mobile = 0L, conns = 0, cleartext = false, idleBg = 0L,
                dests = emptyList(),
            )
        }

    override fun agg(blocking: Boolean): AggStats {
        var conns = 0; var data = 0L; var wifi = 0L; var mobile = 0L; var phoning = 0; var clear = 0
        var blockedTrackers = 0
        for (a in appList) {
            conns += a.conns; data += a.fg + a.bg; wifi += a.wifi; mobile += a.mobile
            // Split this app's tracker endpoints into blocked vs still-reachable, so both tiles react
            // the instant you block one: "Phoning home" counts an app only while it still has a
            // tracker we're NOT blocking; "Trackers blocked" counts the endpoints we ARE blocking.
            val trackers = a.dests.filter { it.trk != null }
            val unblocked = trackers.count { !isHostBlocked(it.host, blocking) }
            blockedTrackers += trackers.size - unblocked
            if (unblocked > 0) phoning++
            if (a.cleartext) clear++
        }
        return AggStats(
            trackersBlocked = blockedTrackers,
            phoningHome = phoning, connections = conns,
            dataKb = data, wifiKb = wifi, mobileKb = mobile, cleartextApps = clear,
        )
    }

    /** Mirrors the service's shouldBlock / the detail's isBlockedNow: an explicit allow wins, then
     *  an explicit user-block, then the curated list while the global blocking toggle is on. */
    private fun isHostBlocked(host: String, blocking: Boolean): Boolean {
        if (PreferencesManager.isNetMonAllowed(context, host)) return false
        if (PreferencesManager.isNetMonUserBlocked(context, host)) return true
        return blocking && NtmBlocklist.isBlocked(context, host)
    }

    override fun posture(blocking: Boolean): Posture {
        val phoning = appList.count { NtmDemoData.appStats(it).trackerDests.isNotEmpty() }
        val clear = appList.count { it.cleartext }
        var score = 92 - clear * 8 - if (blocking) 0 else phoning.coerceAtMost(8) * 4
        score = score.coerceIn(8, 100)
        return when {
            score >= 80 -> Posture(score, "GUARDED", Tone.OK, "Traffic is monitored and trackers are blocked.")
            score >= 60 -> Posture(score, "WATCHFUL", Tone.ACCENT, "Monitoring is on; some apps are contacting trackers.")
            score >= 40 -> Posture(score, "LEAKY", Tone.WARN, "Several apps are talking to tracking companies.")
            else -> Posture(score, "EXPOSED", Tone.BAD, "Trackers are being contacted freely from this device.")
        }
    }

    override fun findings(blocking: Boolean): List<Finding> {
        val out = mutableListOf<Finding>()
        val ranked = appList.map { it to NtmDemoData.appStats(it).trackerDests.size }
            .filter { it.second > 0 }.maxByOrNull { it.second }
        if (ranked != null) {
            val companies = NtmDemoData.appStats(ranked.first).trackerCompanies.size
            out += Finding(
                Tone.WARN, FindingIcon.TRACKERS,
                "${ranked.first.name} contacted ${ranked.second} tracking endpoints",
                "Across $companies tracking ${if (companies == 1) "company" else "companies"} seen on the network.",
                ranked.first.id, "See trackers",
            )
        }
        val blocked = NtmStore.totalBlocked.get()
        if (blocking && blocked > 0) out += Finding(
            Tone.OK, FindingIcon.BLOCK,
            "$blocked tracker connections blocked",
            "S'CAN sinkholed these DNS lookups before they left your device.",
            NtmDemoData.FINDING_BLOCKED, "See which trackers",
        )
        if (out.isEmpty()) out += Finding(
            Tone.OK, FindingIcon.BLOCK,
            "No trackers seen yet",
            "Keep using your apps — S'CAN flags tracker connections as they happen.",
            null, null,
        )
        return out
    }

    // ───────────────────────── build ─────────────────────────

    private fun build(): List<NtmApp> {
        val usage = runCatching { DataUsageHelper.getAllAppsDataUsage(context, windowStart, now) }
            .getOrNull().orEmpty()
        val store = NtmStore.snapshot().associateBy { it.uid }
        val out = ArrayList<NtmApp>()
        for (uid in (usage.keys + store.keys)) {
            val pkg = runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull() ?: continue
            if (pkg == context.packageName) continue                     // hide ourselves
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg.substringAfterLast('.'))

            val u = usage[uid]
            val totalKb = (u?.totalBytes ?: 0L) / 1024
            val wifiKb = ((u?.wifiRxBytes ?: 0L) + (u?.wifiTxBytes ?: 0L)) / 1024
            val mobileKb = ((u?.mobileRxBytes ?: 0L) + (u?.mobileTxBytes ?: 0L)) / 1024

            val st = store[uid]
            val hosts = st?.hosts?.values?.sortedByDescending { it.queries }.orEmpty()
            if (totalKb <= 0 && hosts.isEmpty()) continue               // no usage and no tunnel activity

            val dests = hosts.map { h ->
                val trk = TrackerDomainMatcher.match(context, h.host)
                val enr = NtmStore.enrichmentFor(h.host)               // real org/ASN/country from the resolved IP
                // Stage-4b capture fills port/proto/enc/bytes on the HostStat; DNS-only leaves them
                // unset (proto "" → the detail renders a lean row, no fabricated cleartext/bytes).
                Dest(
                    host = h.host,
                    org = enr?.org ?: trk?.name ?: "",
                    asn = enr?.asn.orEmpty(),
                    geo = enr?.country.orEmpty(),
                    port = h.port,
                    proto = h.proto,
                    enc = h.enc ?: true,                                 // real when captured; else assume encrypted
                    trk = trk?.let { registerLiveTracker(it) },
                    bytesKb = h.bytes / 1024,
                )
            }
            val cleartext = dests.any { it.proto.isNotEmpty() && !it.enc } // only from observed capture

            out += NtmApp(
                id = pkg, name = label, pkg = pkg, mono = monogram(label), brand = brandColor(pkg),
                fg = totalKb, bg = 0L, wifi = wifiKb, mobile = mobileKb,
                conns = hosts.size, cleartext = cleartext, idleBg = 0L, dests = dests,
            )
        }
        return out.sortedByDescending { it.fg + it.bg }.take(MAX_APPS)
    }

    /** Register an Exodus tracker into [NtmDemoData]'s runtime registry so the screens can resolve
     *  it by key (mirrors the curated TRACKERS map). Returns the synthesized key. */
    private fun registerLiveTracker(ti: TrackerInfo): String {
        val key = "x${ti.id}"
        NtmDemoData.registerTracker(
            Tracker(
                key = key,
                name = ti.name,
                cat = mapCategory(ti.categories?.firstOrNull()),
                owner = ti.name,
                domain = ti.networkSignature?.substringBefore('|')?.replace("\\", "").orEmpty(),
                site = ti.website.orEmpty(),
                desc = LocalTrackerScanner.cleanDescription(ti.description),
            )
        )
        return key
    }

    /** Map Exodus categories onto the demo vocabulary the detail screen colours by. */
    private fun mapCategory(c: String?): String = when (c) {
        "Advertisement" -> "Advertising"
        null, "" -> "Tracker"
        else -> c
    }

    private fun monogram(label: String): String =
        label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

    private fun brandColor(pkg: String): String = PALETTE[pkg.hashCode().mod(PALETTE.size)]

    companion object {
        private const val WINDOW_MS = 24L * 60 * 60 * 1000   // last 24h of usage
        private const val MAX_APPS = 60
        private val PALETTE = listOf(
            "#22C55E", "#38BDF8", "#A855F7", "#FB923C", "#F472B6",
            "#FACC15", "#2DD4BF", "#60A5FA", "#F87171", "#34D399",
        )
    }
}

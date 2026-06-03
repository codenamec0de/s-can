package com.uow.scan.util

/**
 * Network Traffic Monitor — demo dataset (S'CAN V4).
 *
 * One source of truth for the NTM Overview (Screen A) + App Detail (Screen B) screens while
 * the tool is design-first: real-world app names, a subset of the tracker DB with
 * org / ASN / geo / protocol destinations, plus derived findings, aggregate counters and a
 * posture readout. When the real on-device tunnel lands (NTM Stage 0+), the screens swap this
 * source for live data; the screen-facing shapes here are deliberately close to what the
 * tunnel will produce (per-app bytes, per-destination metadata, tracker matches).
 */
object NtmDemoData : NtmDataSource {

    /** Semantic tone → mapped to a v4 colour resource by the screens. */
    enum class Tone { OK, ACCENT, WARN, BAD }

    data class Tracker(
        val key: String, val name: String, val cat: String, val owner: String,
        val domain: String, val site: String, val desc: String,
    )

    data class Dest(
        val host: String, val org: String, val asn: String, val geo: String,
        val port: Int, val proto: String, val enc: Boolean, val trk: String?, val bytesKb: Long,
        val preBlocked: Boolean = false,
    ) {
        val cat: String? get() = trk?.let { tracker(it)?.cat }
    }

    data class NtmApp(
        val id: String, val name: String, val pkg: String, val mono: String, val brand: String,
        val fg: Long, val bg: Long, val wifi: Long, val mobile: Long, val conns: Int,
        val cleartext: Boolean = false, val idleBg: Long = 0,
        val dests: List<Dest>,
    )

    data class AppStats(
        val total: Long, val trackerDests: List<Dest>,
        val trackerCompanies: List<String>, val cleartextCount: Int,
    )

    data class AggStats(
        val trackersBlocked: Int, val phoningHome: Int, val connections: Int,
        val dataKb: Long, val wifiKb: Long, val mobileKb: Long, val cleartextApps: Int,
    )

    data class Posture(val score: Int, val grade: String, val tone: Tone, val line: String)

    enum class FindingIcon { WARN, TRACKERS, BACKGROUND, BLOCK }
    data class Finding(
        val tone: Tone, val icon: FindingIcon, val title: String, val desc: String,
        val appId: String?, val cta: String?,
    )

    /** Sentinel [Finding.appId] meaning "open the blocked-trackers breakdown", not an app detail. */
    const val FINDING_BLOCKED = "__ntm_blocked__"

    /** KB → human size, matching the design's fmtBytes. */
    fun fmtBytes(kb: Long): String = when {
        kb >= 1024L * 1024L -> String.format("%.2f GB", kb / 1024.0 / 1024.0)
        kb >= 1024L -> String.format("%.1f MB", kb / 1024.0)
        else -> "$kb KB"
    }

    // ───── Tracker company database (subset of the 432-entry Exodus DB) ─────
    val TRACKERS: Map<String, Tracker> = listOf(
        Tracker("ga", "Google Analytics", "Analytics", "Google LLC", "google-analytics.com", "analytics.google.com",
            "Measures app usage and user behaviour, tied to Google’s advertising graph."),
        Tracker("dclk", "DoubleClick", "Advertising", "Google LLC", "doubleclick.net", "marketingplatform.google.com",
            "Google’s ad-serving and retargeting network — profiles users across apps and sites."),
        Tracker("meta", "Meta Audience", "Advertising", "Meta Platforms", "graph.facebook.com", "facebook.com/business",
            "Sends app events to Meta to build ad-targeting profiles, even without a Facebook account."),
        Tracker("appsf", "AppsFlyer", "Attribution", "AppsFlyer Ltd", "appsflyer.com", "appsflyer.com",
            "Mobile attribution SDK that links installs and in-app events to ad campaigns."),
        Tracker("adjust", "Adjust", "Attribution", "Adjust GmbH", "adjust.com", "adjust.com",
            "Measures which ad drove an install and tracks downstream events."),
        Tracker("pangle", "Pangle", "Advertising", "ByteDance Ltd", "pangle.io", "pangle.io",
            "ByteDance’s ad network — collects device and usage signals to serve targeted ads."),
        Tracker("unity", "Unity Ads", "Advertising", "Unity Technologies", "unityads.unity3d.com", "unity.com",
            "In-game ad network; gathers device identifiers and gameplay signals."),
        Tracker("ironsrc", "ironSource", "Advertising", "Unity (ironSource)", "ironsrc.com", "is.com",
            "Mobile ad-mediation platform aggregating multiple ad networks."),
        Tracker("amp", "Amplitude", "Analytics", "Amplitude Inc", "amplitude.com", "amplitude.com",
            "Product-analytics SDK recording detailed in-app event streams."),
        Tracker("branch", "Branch", "Attribution", "Branch Metrics", "branch.io", "branch.io",
            "Deep-linking and attribution; fingerprints devices to match sessions."),
        Tracker("crash", "Crashlytics", "Diagnostics", "Google LLC", "crashlytics.com", "firebase.google.com",
            "Crash reporting. Lower risk, but still phones home with device metadata."),
        Tracker("inmobi", "InMobi", "Advertising", "InMobi Pte", "inmobi.com", "inmobi.com",
            "Ad network with a history of aggressive location collection."),
    ).associateBy { it.key }

    /** Trackers discovered at runtime by the live matcher (Exodus network sigs), keyed by a
     *  synthesized id so the screens resolve them the same way as the curated [TRACKERS]. */
    private val runtimeTrackers = java.util.concurrent.ConcurrentHashMap<String, Tracker>()

    fun registerTracker(t: Tracker) { runtimeTrackers[t.key] = t }

    /** Resolve a tracker key from the curated set first, then the runtime (live) set. */
    fun tracker(key: String): Tracker? = TRACKERS[key] ?: runtimeTrackers[key]

    private fun d(host: String, org: String, asn: String, geo: String, port: Int, proto: String,
                  enc: Boolean, trk: String?, bytesKb: Long, blocked: Boolean = false) =
        Dest(host, org, asn, geo, port, proto, enc, trk, bytesKb, blocked)

    // ───── Apps observed on the tunnel. bytes in KB; fg/bg + wifi/mobile splits. ─────
    val NTM_APPS: List<NtmApp> = listOf(
        NtmApp("tiktok", "TikTok", "com.zhiliaoapp.musically", "d", "#FE2C55",
            196608, 122880, 268000, 51488, 48, dests = listOf(
                d("v19-web.tiktokcdn.com", "TikTok Pte. Ltd.", "AS138699", "Singapore", 443, "HTTPS", true, null, 211000),
                d("log.byteoversea.com", "ByteDance Ltd.", "AS396986", "Hong Kong", 443, "HTTPS", true, "pangle", 38400),
                d("mon.tiktokv.com", "ByteDance Ltd.", "AS396986", "Singapore", 443, "HTTPS", true, "pangle", 9200),
                d("analytics.tiktok.com", "TikTok Pte. Ltd.", "AS138699", "Singapore", 443, "HTTPS", true, "ga", 3100),
                d("graph.facebook.com", "Meta Platforms", "AS32934", "Menlo Park, US", 443, "HTTPS", true, "meta", 1800),
                d("app.adjust.com", "Adjust GmbH", "AS16509", "Frankfurt, DE", 443, "HTTPS", true, "adjust", 940),
                d("init.appsflyer.com", "AppsFlyer Ltd.", "AS16509", "Dublin, IE", 443, "HTTPS", true, "appsf", 720),
            )),
        NtmApp("facebook", "Facebook", "com.facebook.katana", "f", "#1877F2",
            92160, 143360, 180000, 55520, 39, idleBg = 40960, dests = listOf(
                d("graph.facebook.com", "Meta Platforms", "AS32934", "Menlo Park, US", 443, "HTTPS", true, "meta", 96000),
                d("edge-mqtt.facebook.com", "Meta Platforms", "AS32934", "Menlo Park, US", 443, "HTTPS", true, null, 41000),
                d("scontent.xx.fbcdn.net", "Meta Platforms", "AS32934", "Dublin, IE", 443, "HTTPS", true, null, 38000),
                d("an.facebook.com", "Meta Platforms", "AS32934", "Menlo Park, US", 443, "HTTPS", true, "meta", 7600),
                d("www.google-analytics.com", "Google LLC", "AS15169", "Mountain View, US", 443, "HTTPS", true, "ga", 2400),
            )),
        NtmApp("instagram", "Instagram", "com.instagram.android", "IG", "#E1306C",
            138240, 30720, 150000, 18960, 27, dests = listOf(
                d("i.instagram.com", "Meta Platforms", "AS32934", "Dublin, IE", 443, "HTTPS", true, null, 121000),
                d("graph.instagram.com", "Meta Platforms", "AS32934", "Menlo Park, US", 443, "HTTPS", true, "meta", 14000),
                d("graph.facebook.com", "Meta Platforms", "AS32934", "Menlo Park, US", 443, "HTTPS", true, "meta", 4200),
            )),
        NtmApp("weather", "Weather Home", "com.weather.home.live", "W", "#38BDF8",
            8192, 18432, 12000, 14624, 31, cleartext = true, dests = listOf(
                d("ads.weatherhome.io", "Hetzner Online", "AS24940", "Helsinki, FI", 80, "HTTP", false, "inmobi", 9800),
                d("sdk.inmobi.com", "InMobi Pte.", "AS17439", "Bengaluru, IN", 80, "HTTP", false, "inmobi", 4200),
                d("track.appsflyer.com", "AppsFlyer Ltd.", "AS16509", "Dublin, IE", 443, "HTTPS", true, "appsf", 1900),
                d("api.weatherhome.io", "Hetzner Online", "AS24940", "Helsinki, FI", 80, "HTTP", false, null, 2600),
                d("pubads.g.doubleclick.net", "Google LLC", "AS15169", "Mountain View, US", 443, "HTTPS", true, "dclk", 3400),
                d("geo.weatherhome.io", "Hetzner Online", "AS24940", "Helsinki, FI", 80, "HTTP", false, "inmobi", 1200),
            )),
        NtmApp("bubble", "Bubble Blast", "com.casualjoy.bubbleblast", "B", "#A855F7",
            24576, 6144, 20000, 10720, 36, cleartext = true, dests = listOf(
                d("unityads.unity3d.com", "Unity Technologies", "AS54113", "San Francisco, US", 443, "HTTPS", true, "unity", 14200),
                d("init.ironsrc.com", "Unity (ironSource)", "AS16509", "Tel Aviv, IL", 80, "HTTP", false, "ironsrc", 8600),
                d("cdn.ads.bubbleblast.io", "DigitalOcean", "AS14061", "Frankfurt, DE", 80, "HTTP", false, "unity", 5100),
                d("events.adjust.com", "Adjust GmbH", "AS16509", "Frankfurt, DE", 443, "HTTPS", true, "adjust", 1600),
                d("config.unityads.unity3d.com", "Unity Technologies", "AS54113", "San Francisco, US", 443, "HTTPS", true, "unity", 2200),
            )),
        NtmApp("spotify", "Spotify", "com.spotify.music", "S", "#1DB954",
            76800, 12288, 84000, 5088, 22, dests = listOf(
                d("audio-fa.scdn.co", "Spotify AB", "AS8403", "Stockholm, SE", 443, "HTTPS", true, null, 78000),
                d("api.spotify.com", "Spotify AB", "AS8403", "Stockholm, SE", 443, "HTTPS", true, null, 6400),
                d("t.appsflyer.com", "AppsFlyer Ltd.", "AS16509", "Dublin, IE", 443, "HTTPS", true, "appsf", 1100),
                d("api2.amplitude.com", "Amplitude Inc.", "AS16509", "San Francisco, US", 443, "HTTPS", true, "amp", 820),
            )),
        NtmApp("chrome", "Chrome", "com.android.chrome", "C", "#4285F4",
            110592, 4096, 100000, 14688, 54, dests = listOf(
                d("www.googleapis.com", "Google LLC", "AS15169", "Mountain View, US", 443, "HTTPS", true, null, 64000),
                d("safebrowsing.googleapis.com", "Google LLC", "AS15169", "Mountain View, US", 443, "HTTPS", true, null, 28000),
                d("www.google-analytics.com", "Google LLC", "AS15169", "Mountain View, US", 443, "HTTPS", true, "ga", 5200),
                d("update.googleapis.com", "Google LLC", "AS15169", "Mountain View, US", 443, "HTTPS", true, null, 8600),
            )),
        NtmApp("whatsapp", "WhatsApp", "com.whatsapp", "W", "#25D366",
            41984, 8192, 46000, 4176, 11, dests = listOf(
                d("g.whatsapp.net", "Meta Platforms", "AS32934", "Dublin, IE", 443, "HTTPS", true, null, 44000),
                d("mmg.whatsapp.net", "Meta Platforms", "AS32934", "Dublin, IE", 443, "HTTPS", true, null, 6100),
                d("crashlogs.whatsapp.net", "Meta Platforms", "AS32934", "Menlo Park, US", 443, "HTTPS", true, "crash", 240),
            )),
        NtmApp("gmail", "Gmail", "com.google.android.gm", "M", "#EA4335",
            36864, 20480, 52000, 5344, 18, dests = listOf(
                d("mail.google.com", "Google LLC", "AS15169", "Mountain View, US", 443, "HTTPS", true, null, 49000),
                d("firebaselogging-pa.googleapis.com", "Google LLC", "AS15169", "Mountain View, US", 443, "HTTPS", true, "crash", 3200),
                d("play.googleapis.com", "Google LLC", "AS15169", "Mountain View, US", 443, "HTTPS", true, null, 4100),
            )),
    )

    fun appById(id: String?): NtmApp = NTM_APPS.firstOrNull { it.id == id } ?: NTM_APPS.first()

    fun appStats(a: NtmApp): AppStats {
        val trackerDests = a.dests.filter { it.trk != null }
        val companies = trackerDests.mapNotNull { it.trk }.distinct()
        return AppStats(a.fg + a.bg, trackerDests, companies, a.dests.count { !it.enc })
    }

    fun aggStats(blocking: Boolean): AggStats {
        var conns = 0; var total = 0L; var phoning = 0; var wifi = 0L; var mobile = 0L
        for (a in NTM_APPS) {
            val s = appStats(a)
            conns += a.conns; total += s.total; wifi += a.wifi; mobile += a.mobile
            if (s.trackerCompanies.isNotEmpty()) phoning++
        }
        return AggStats(
            trackersBlocked = if (blocking) 142 else 0,
            phoningHome = phoning, connections = conns,
            dataKb = total, wifiKb = wifi, mobileKb = mobile,
            cleartextApps = NTM_APPS.count { it.cleartext },
        )
    }

    override fun apps(): List<NtmApp> = NTM_APPS

    override fun agg(blocking: Boolean): AggStats = aggStats(blocking)

    /** Honest composite: starts high, subtracts for cleartext apps and un-blocked trackers. */
    override fun posture(blocking: Boolean): Posture {
        val s = aggStats(blocking)
        var score = 92 - s.cleartextApps * 8
        if (!blocking) score -= 26
        score = score.coerceIn(8, 100)
        return when {
            score >= 80 -> Posture(score, "GUARDED", Tone.OK, "Traffic is monitored and trackers are blocked.")
            score >= 60 -> Posture(score, "WATCHFUL", Tone.ACCENT, "Monitoring is on, but some apps are leaking.")
            score >= 40 -> Posture(score, "LEAKY", Tone.WARN, "Several apps are sending data in the clear.")
            else -> Posture(score, "EXPOSED", Tone.BAD, "Trackers are flowing freely from this device.")
        }
    }

    override fun findings(blocking: Boolean): List<Finding> {
        val out = mutableListOf<Finding>()
        val cleartextApps = NTM_APPS.filter { it.cleartext }
        if (cleartextApps.isNotEmpty()) out += Finding(
            Tone.BAD, FindingIcon.WARN,
            "${cleartextApps.size} apps sent data over unencrypted HTTP",
            "${cleartextApps.joinToString(", ") { it.name }} transmitted data in cleartext — readable by anyone on the network.",
            cleartextApps.first().id, "Review connections",
        )
        val ranked = NTM_APPS.map { it to appStats(it).trackerDests.size }.maxByOrNull { it.second }
        if (ranked != null && ranked.second > 0) out += Finding(
            Tone.WARN, FindingIcon.TRACKERS,
            "${ranked.first.name} contacted ${ranked.second} tracking endpoints",
            "Across ${appStats(ranked.first).trackerCompanies.size} different tracking companies in this session.",
            ranked.first.id, "See trackers",
        )
        NTM_APPS.firstOrNull { it.idleBg > 0 }?.let { fb ->
            out += Finding(
                Tone.WARN, FindingIcon.BACKGROUND,
                "${fb.name} used ${fmtBytes(fb.idleBg)} in the background while idle",
                "The app moved data with no screen activity — typical of passive profiling.",
                fb.id, "View detail",
            )
        }
        if (blocking) out += Finding(
            Tone.OK, FindingIcon.BLOCK,
            "142 tracker connections blocked today",
            "S’CAN dropped these before they left your device. Nothing was sent.",
            FINDING_BLOCKED, "See which trackers",
        )
        return out
    }
}

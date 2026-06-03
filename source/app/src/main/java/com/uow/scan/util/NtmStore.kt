package com.uow.scan.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory, thread-safe aggregation of everything the unified tunnel observes, keyed by app UID.
 *
 * Deliberately **not persisted** — it lives for the duration of a monitoring session and is
 * [reset] when the tunnel starts. That avoids a Room v7→v8 migration (which would trigger
 * `fallbackToDestructiveMigration` and wipe user data) and keeps the demo safe. It is also
 * **bounded**, so a busy device can never grow it without limit.
 *
 * The store is intentionally "dumb": it records hosts / counts / bytes per UID off the packet
 * hot-path. Package-name resolution and tracker classification happen at read time in
 * [NtmLiveRepository] (which has a Context), so the VPN worker stays cheap.
 */
object NtmStore {

    private const val MAX_HOSTS_PER_APP = 256

    /** Per-destination-host counters. [enc]/[port]/[proto] stay unset on a DNS-only tunnel
     *  (null/0/"") and are filled only by the Stage-4 full-capture forwarder. */
    class HostStat(val host: String) {
        @Volatile var queries = 0
        @Volatile var blocked = 0
        @Volatile var bytes = 0L
        @Volatile var port = 0
        @Volatile var proto = ""
        @Volatile var enc: Boolean? = null
        @Volatile var lastSeen = 0L
    }

    class AppStat(val uid: Int) {
        val hosts = ConcurrentHashMap<String, HostStat>()
        @Volatile var queries = 0
        @Volatile var blocked = 0
        @Volatile var bytes = 0L
        @Volatile var firstSeen = 0L
        @Volatile var lastSeen = 0L
    }

    private val apps = ConcurrentHashMap<Int, AppStat>()
    val totalQueries = AtomicLong(0)
    val totalBlocked = AtomicLong(0)

    @Volatile var sessionStart = 0L
        private set

    /** Per-host ASN/org/country, derived from DNS-answer IPs by [IpAsnDb]. Host-keyed (same host →
     *  same owning org across apps), populated off the DNS path; read by the live repo. */
    class Enrichment(val org: String, val asn: String, val country: String)
    private val enrichment = ConcurrentHashMap<String, Enrichment>()

    fun enrichHost(host: String, org: String, asn: String, country: String) {
        if (host.isNotBlank()) enrichment[host.lowercase()] = Enrichment(org, asn, country)
    }

    fun enrichmentFor(host: String): Enrichment? = enrichment[host.lowercase()]

    /** Clear everything and stamp a fresh session start. Called when the tunnel comes up. */
    fun reset() {
        apps.clear()
        enrichment.clear()
        totalQueries.set(0)
        totalBlocked.set(0)
        sessionStart = System.currentTimeMillis()
    }

    private fun app(uid: Int): AppStat =
        apps.getOrPut(uid) { AppStat(uid).also { it.firstSeen = System.currentTimeMillis() } }

    private fun AppStat.host(host: String): HostStat? {
        hosts[host]?.let { return it }
        if (hosts.size >= MAX_HOSTS_PER_APP) return null   // bounded: stop adding new hosts
        return hosts.getOrPut(host) { HostStat(host) }
    }

    /** Record a DNS lookup seen on the tunnel. [host] should be lowercased, no trailing dot. */
    fun recordDns(uid: Int, host: String, blocked: Boolean) {
        val now = System.currentTimeMillis()
        val a = app(uid)
        a.queries++; a.lastSeen = now
        totalQueries.incrementAndGet()
        if (blocked) { a.blocked++; totalBlocked.incrementAndGet() }
        val h = a.host(host) ?: return
        h.queries++; h.lastSeen = now
        if (blocked) h.blocked++
    }

    /** Record an observed connection (Stage-4 capture): fills port / proto / enc / bytes. */
    fun recordFlow(uid: Int, host: String, port: Int, proto: String, enc: Boolean, bytes: Long) {
        val now = System.currentTimeMillis()
        val a = app(uid)
        a.lastSeen = now; a.bytes += bytes
        val h = a.host(host) ?: return
        h.port = port; h.proto = proto; h.enc = enc; h.bytes += bytes; h.lastSeen = now
    }

    fun snapshot(): List<AppStat> = apps.values.toList()
    fun forUid(uid: Int): AppStat? = apps[uid]
}

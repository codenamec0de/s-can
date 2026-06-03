package com.uow.scan.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import com.uow.scan.MainActivity
import com.uow.scan.R
import com.uow.scan.util.IpAsnDb
import com.uow.scan.util.NtmBlocklist
import com.uow.scan.util.NtmStore
import com.uow.scan.util.PreferencesManager
import com.uow.scan.vpn.capture.CaptureEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * S'CAN unified local [VpnService]. One tunnel, composable modes (so DNS Protection and the
 * Network Traffic Monitor never fight Android's single-VPN slot):
 *
 *  - **encrypt** — re-issue DNS over DNS-over-HTTPS to Cloudflare ([DohClient]). This is the
 *    original DNS Protection feature, behaviour-preserved.
 *  - **monitor** — attribute each DNS query to the owning app ([android.net.ConnectivityManager.getConnectionOwnerUid])
 *    and record it in [NtmStore]; resolution still happens (DoH if encrypt, else the real system
 *    resolver), so monitoring is transparent and does not silently change the user's DNS provider.
 *  - **block** — sinkhole tracker-domain lookups locally (NXDOMAIN / 0.0.0.0) — wired in Stage 3.
 *  - **capture** — full-traffic userspace forwarder for SNI/cleartext/bytes — wired in Stage 4.
 *
 * For stages 0–3 only DNS is tunnelled (route to our two virtual resolver IPs); full routing is
 * added with the Stage-4 forwarder. The app is deliberately NOT excluded from the tunnel: it is a
 * DNS-only split tunnel and [DohClient] reaches Cloudflare on :443 via hardcoded bootstrap IPs (no
 * :53 lookup, and 1.1.1.1 isn't in the routes), so there is no loopback — and including the app
 * keeps the Tier-B deep test honest (it sees the real protected egress, not a false ISP leak).
 *
 * While encrypt is up, [com.uow.scan.util.DnsLeakAnalyzer] reads [PreferencesManager.isDnsProtectionActive]
 * to honestly report the posture as encrypted; [PreferencesManager.isNetMonActive] mirrors monitoring.
 */
class ScanDnsVpnService : VpnService() {

    /** Composable tunnel modes. Absent intent extras default to the running value (merge). */
    data class Config(
        val encrypt: Boolean,
        val monitor: Boolean,
        val block: Boolean,
        val capture: Boolean,
    )

    // A handler so a stray exception in a worker is logged, never crashing the whole app process
    // (which would take the tunnel down with it).
    private val errHandler = CoroutineExceptionHandler { _, e -> Log.e(TAG, "VPN worker crashed", e) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errHandler)
    private val doh = DohClient()
    private val inflight = Semaphore(64)
    private val writeLock = Any()
    private val cm by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false
    @Volatile private var config = Config(encrypt = true, monitor = false, block = false, capture = false)
    /** The underlying network's real DNS servers, captured before establish() so monitor mode can
     *  forward to them transparently (the VPN otherwise masks them with our virtual resolver). */
    @Volatile private var systemDns: List<InetAddress> = emptyList()
    @Volatile private var captureEngine: CaptureEngine? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action} running=$running")
        if (intent?.action == ACTION_STOP) {
            teardown()
            return START_NOT_STICKY
        }
        val requested = if (intent != null) readConfig(intent) else config
        // Satisfy the startForegroundService() ~5s contract IMMEDIATELY — before establish() — so a
        // failed bringUp() can never trigger ForegroundServiceDidNotStartInTimeException.
        createChannel()
        startForeground(NOTIF_ID, buildNotification(requested))
        if (running) {
            // Block/encrypt toggle live (the loop reads the volatile config). Capture flips the
            // ROUTING, so it only changes on a fresh bring-up → preserve the running capture here.
            config = requested.copy(capture = config.capture)
            applyFlags(config)
            Log.i(TAG, "reconfigured in place: $config")
            return START_STICKY
        }
        config = requested
        return if (bringUp()) START_STICKY else { teardown(); START_NOT_STICKY }
    }

    private fun readConfig(i: Intent): Config = Config(
        encrypt = i.getBooleanExtra(EXTRA_ENCRYPT, config.encrypt),
        monitor = i.getBooleanExtra(EXTRA_MONITOR, config.monitor),
        block = i.getBooleanExtra(EXTRA_BLOCK, config.block),
        capture = i.getBooleanExtra(EXTRA_CAPTURE, config.capture),
    )

    private fun bringUp(): Boolean {
        captureSystemDns()
        if (config.monitor || config.block || config.capture) NtmStore.reset()
        scope.launch { NtmBlocklist.warmUp(applicationContext) }
        scope.launch { IpAsnDb.warmUp(applicationContext) }

        val builder = Builder()
            .setSession(SESSION)
            .setMtu(MTU)
            .setBlocking(true)              // blocking reads → clean loop, no busy-spin
            .addAddress(V4_LOCAL, 32)
            .addDnsServer(V4_DNS)
        if (config.capture) {
            // Stage-4b full capture: route ALL IPv4 through the userspace forwarder, and exclude
            // ourselves so our own DoH + the forwarder's upstream sockets bypass the tunnel (no
            // loop). IPv6 is left UNrouted so it bypasses and keeps working — a forwarder bug can
            // never break IPv6 connectivity.
            builder.addRoute("0.0.0.0", 0)
            runCatching { builder.addDisallowedApplication(packageName) }
        } else {
            builder.addRoute(V4_DNS, 32)    // DNS-only: route ONLY our resolver IPs
            // IPv6 is best-effort + DNS-only here; a device rejecting our ULA must not drop IPv4.
            runCatching {
                builder.addAddress(V6_LOCAL, 128).addDnsServer(V6_DNS).addRoute(V6_DNS, 128)
            }.onFailure { Log.w(TAG, "IPv6 tunnel config skipped (IPv4 stays up)", it) }
        }

        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish() threw — tunnel NOT up", e)
            null
        }
        if (pfd == null) {
            Log.e(TAG, "establish() returned null — no tun (consent missing / params rejected)")
            return false
        }

        tun = pfd
        running = true
        tunnelUp = true
        applyFlags(config)
        Log.i(TAG, "tunnel ESTABLISHED fd=${pfd.fd}; config=$config; systemDns=${systemDns.size}")
        if (config.capture) {
            captureEngine = CaptureEngine(
                vpn = this, context = applicationContext, tun = pfd,
                dnsRespond = { d -> dnsRespond(d, config) },
                attribute = { proto, sip, sport, dip, dport -> attributeUid(proto, sip, sport, dip, dport) },
            ).also { it.start() }
        } else {
            scope.launch { readLoop(pfd) }
        }
        return true
    }

    /** Reflect the active modes into the shared prefs the analyzers + UI read. */
    private fun applyFlags(c: Config) {
        PreferencesManager.setDnsProtectionActive(this, c.encrypt)
        PreferencesManager.setNetMonActive(this, c.monitor || c.block || c.capture)
    }

    /** Snapshot the real upstream DNS servers from the active (non-VPN) network. */
    private fun captureSystemDns() {
        systemDns = runCatching {
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun readLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(MAX_PKT)
        while (running) {
            val n = try { input.read(buf) } catch (_: Exception) { break }
            if (n < 0) break          // EOF: fd closed by teardown → exit (never busy-spin)
            if (n == 0) continue
            // DNS is overwhelmingly UDP; we parse only UDP and drop anything else (incl. TCP/53).
            // A copy is taken per packet so the shared read buffer can be reused immediately.
            val datagram = DnsPacket.parseUdp(buf, n) ?: continue
            if (datagram.dstPort != DNS_PORT || datagram.payload.isEmpty()) continue
            val cfg = config
            scope.launch {
                inflight.withPermit {
                    val reply = dnsRespond(datagram, cfg) ?: return@withPermit
                    synchronized(writeLock) { runCatching { output.write(reply); output.flush() } }
                }
            }
        }
        // Do NOT close input/output: they wrap the PFD's own (un-dup'd) fd, which teardown()
        // closes exactly once. Double-closing could close a reused, unrelated socket.
    }

    /** Resolve or sinkhole one DNS datagram, attribute + record it, and return the reply IP packet
     *  (or null to drop). Shared by the DNS-only readLoop and the Stage-4 capture engine. */
    private fun dnsRespond(d: DnsPacket.UdpDatagram, cfg: Config): ByteArray? {
        val q = DnsMessage.parseQuestion(d.payload)
        val host = q?.qname
        val uid = if (cfg.monitor || cfg.block) attribute(d) else INVALID_UID

        if (q != null && host != null && shouldBlock(host, cfg)) {
            if (cfg.monitor) record(uid, host, blocked = true)
            return DnsPacket.buildUdpReply(d, DnsMessage.buildBlockResponse(d.payload, q))
        }

        val answer = resolveUpstream(d.payload, cfg) ?: return null
        if (cfg.monitor && host != null) {
            record(uid, host, blocked = false)
            enrich(host, answer)
        }
        return DnsPacket.buildUdpReply(d, answer)
    }

    /** Enrich the destination with ASN/org/country from the resolved IP in the DNS answer. */
    private fun enrich(host: String, answer: ByteArray) {
        val ip = DnsMessage.firstAnswerIp(answer) ?: return
        val info = IpAsnDb.lookup(this, ip) ?: return
        NtmStore.enrichHost(host, info.org, "AS${info.asn}", info.country)
    }

    /** Record a DNS event into [NtmStore] and log it (tag [TAG_ATTR]) so attribution is visible. */
    private fun record(uid: Int, host: String, blocked: Boolean) {
        if (DEBUG_ATTR) Log.d(TAG_ATTR, "${uidName(uid)} → $host${if (blocked) "  [BLOCKED]" else ""}")
        NtmStore.recordDns(uid, host, blocked)
    }

    /** Cached UID → package name (first package sharing the UID), for logs + later attribution. */
    private val uidNames = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private fun uidName(uid: Int): String {
        if (uid <= 0) return "uid$uid"
        return uidNames.getOrPut(uid) {
            runCatching { packageManager.getPackagesForUid(uid)?.firstOrNull() }.getOrNull() ?: "uid$uid"
        }
    }

    private fun resolveUpstream(query: ByteArray, cfg: Config): ByteArray? =
        if (cfg.encrypt) doh.resolve(query)
        else forwardToSystemDns(query) ?: doh.resolve(query)   // transparent, with a safe DoH fallback

    /** Forward [query] to the real system resolver over a protected socket (so it doesn't loop
     *  back into our own tunnel). Returns null on any failure → caller falls back to DoH. */
    private fun forwardToSystemDns(query: ByteArray): ByteArray? {
        val server = systemDns.firstOrNull() ?: return null
        return runCatching {
            DatagramSocket().use { sock ->
                if (!protect(sock)) return null
                sock.soTimeout = UPSTREAM_TIMEOUT_MS
                sock.send(DatagramPacket(query, query.size, server, DNS_PORT))
                val rbuf = ByteArray(MAX_PKT)
                val rp = DatagramPacket(rbuf, rbuf.size)
                sock.receive(rp)
                rbuf.copyOf(rp.length)
            }
        }.getOrNull()
    }

    /** Owning UID of a connection via the kernel connection table (API 29+; else unknown). Used for
     *  DNS attribution and, by the capture engine, per-flow TCP/UDP attribution. */
    private fun attributeUid(proto: Int, srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return INVALID_UID
        return runCatching {
            cm.getConnectionOwnerUid(
                proto,
                InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort),
                InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort),
            )
        }.getOrDefault(INVALID_UID)
    }

    private fun attribute(d: DnsPacket.UdpDatagram): Int =
        attributeUid(OsConstants.IPPROTO_UDP, d.srcIp, d.srcPort, d.dstIp, d.dstPort)

    /** Sinkhole decision: user-allow wins; then an explicit per-app user-block always blocks; else
     *  the curated ad/tracker list, but only while the global "Block trackers & ads" toggle is on.
     *  Naming uses the broad Exodus matcher; auto-blocking uses the safe curated list ([NtmBlocklist])
     *  so first-party app domains are never sinkholed automatically. */
    private fun shouldBlock(host: String, cfg: Config): Boolean {
        if (PreferencesManager.isNetMonAllowed(this, host)) return false
        if (PreferencesManager.isNetMonUserBlocked(this, host)) return true
        return cfg.block && NtmBlocklist.isBlocked(this, host)
    }

    private fun teardown() {
        Log.i(TAG, "teardown — clearing flags, closing tun", Throwable("teardown caller"))
        running = false
        tunnelUp = false
        runCatching { captureEngine?.stop() }
        captureEngine = null
        applyFlags(Config(encrypt = false, monitor = false, block = false, capture = false))
        // Cancel in-flight workers but KEEP the scope alive (cancelling the scope itself would make
        // a same-instance restart silently never read the tunnel). Closing the fd unblocks the loop.
        runCatching { scope.coroutineContext.cancelChildren() }
        runCatching { tun?.close() }
        tun = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /** The OS calls this when another VPN displaces us, or the user revokes consent. */
    override fun onRevoke() {
        Log.w(TAG, "onRevoke — OS revoked our VPN (displaced or consent withdrawn)")
        teardown()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        running = false
        tunnelUp = false
        runCatching { captureEngine?.stop() }
        captureEngine = null
        applyFlags(Config(encrypt = false, monitor = false, block = false, capture = false))
        runCatching { scope.coroutineContext.cancelChildren() }
        runCatching { tun?.close() }
        tun = null
        runCatching { scope.cancel() }
        super.onDestroy()
    }

    // ── notification ──

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "S'CAN VPN", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while S'CAN's local VPN is active (DNS Protection / Traffic Monitor)"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(c: Config): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val monitoring = c.monitor || c.block || c.capture
        val title = if (monitoring) getString(R.string.ntm_title) else getString(R.string.dns_protect_notif_title)
        val text = if (monitoring) getString(R.string.ntm_subtitle_live) else getString(R.string.dns_protect_notif_text)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ScanDnsVpn"
        private const val TAG_ATTR = "NtmAttr"
        private const val DEBUG_ATTR = false   // per-query attribution logging — diagnostics only, off by default
        const val ACTION_STOP = "com.uow.scan.DNS_PROTECT_STOP"

        /** Process-global liveness: true only between a successful establish and teardown/destroy.
         *  Resets to false in a fresh process, so the UI can tell a live tunnel from a stale
         *  `isNetMonActive` pref left behind by a killed/reinstalled service (no phantom LIVE). */
        @Volatile var tunnelUp = false
            private set

        const val EXTRA_ENCRYPT = "encrypt"
        const val EXTRA_MONITOR = "monitor"
        const val EXTRA_BLOCK = "block"
        const val EXTRA_CAPTURE = "capture"

        private const val INVALID_UID = -1
        private const val SESSION = "S'CAN VPN"
        private const val CHANNEL_ID = "scan_dns_vpn_channel"
        private const val NOTIF_ID = 1100
        private const val MTU = 4096   // fit large DoH answers (DNSSEC/TXT/long chains) in one datagram
        private const val MAX_PKT = 32767
        private const val DNS_PORT = 53
        private const val UPSTREAM_TIMEOUT_MS = 3000

        // Virtual addresses that exist only inside the tun. Every IPv6 hextet must be valid hex —
        // the original "fd00:0:0:dns::x" literals were rejected by parseNumericAddress ('n','s' are
        // not hex digits) so establish() threw and the tunnel never came up. These are valid ULA.
        private const val V4_LOCAL = "10.111.222.1"
        private const val V4_DNS = "10.111.222.2"
        private const val V6_LOCAL = "fd00:5ca0::1"
        private const val V6_DNS = "fd00:5ca0::2"

        /** DNS Protection (encrypt-only). Merges onto any running config, preserving NTM modes. */
        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, ScanDnsVpnService::class.java).putExtra(EXTRA_ENCRYPT, true)
            )
        }

        /** Network Traffic Monitor. Brings the tunnel up in monitor mode with the chosen options. */
        fun startMonitor(context: Context, block: Boolean, encrypt: Boolean, capture: Boolean) {
            context.startForegroundService(
                Intent(context, ScanDnsVpnService::class.java)
                    .putExtra(EXTRA_MONITOR, true)
                    .putExtra(EXTRA_BLOCK, block)
                    .putExtra(EXTRA_ENCRYPT, encrypt)
                    .putExtra(EXTRA_CAPTURE, capture)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScanDnsVpnService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}

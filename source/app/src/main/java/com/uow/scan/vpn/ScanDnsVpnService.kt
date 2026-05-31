package com.uow.scan.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.uow.scan.MainActivity
import com.uow.scan.R
import com.uow.scan.util.PreferencesManager
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

/**
 * S'CAN DNS Protection — a DNS-only local [VpnService]. It captures the device's DNS
 * queries and re-issues every one of them encrypted over DNS-over-HTTPS (Cloudflare via
 * [DohClient]), so DNS no longer travels in plaintext to the ISP or router.
 *
 * Only DNS is tunnelled: we add a route to our two virtual resolver addresses only, so all
 * other traffic is untouched (lower battery, smaller blast radius). The app excludes itself
 * from the tunnel so its own DoH calls don't loop back through it.
 *
 * While this is up, [com.uow.scan.util.DnsLeakAnalyzer] honestly reports the posture as
 * encrypted (the real leak score climbs into the PRIVATE band) — keyed off the pref this
 * service flips on [establish] success and clears on stop/revoke/destroy.
 */
class ScanDnsVpnService : VpnService() {

    // A handler so a stray exception in a DNS worker is logged, never crashing the whole app
    // process (which would take the tunnel down with it).
    private val errHandler = CoroutineExceptionHandler { _, e -> Log.e(TAG, "DNS worker crashed", e) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errHandler)
    private val doh = DohClient()
    private val inflight = Semaphore(64)
    private val writeLock = Any()

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action} running=$running")
        if (intent?.action == ACTION_STOP) {
            teardown()
            return START_NOT_STICKY
        }
        // Satisfy the startForegroundService() ~5s contract IMMEDIATELY — before establish() —
        // so a failed bringUp() can never trigger ForegroundServiceDidNotStartInTimeException.
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        if (running) return START_STICKY
        return if (bringUp()) START_STICKY else { teardown(); START_NOT_STICKY }
    }

    private fun bringUp(): Boolean {
        // IPv4 is the required core of the DNS-only tunnel.
        val builder = Builder()
            .setSession(SESSION)
            .setMtu(MTU)
            .setBlocking(true)         // blocking reads → clean loop, no busy-spin
            .addAddress(V4_LOCAL, 32)
            .addDnsServer(V4_DNS)
            .addRoute(V4_DNS, 32)       // route ONLY our resolver IPs → DNS-only tunnel
        // IPv6 is best-effort: if a device/network rejects our ULA config it must never take the
        // working IPv4 DNS tunnel down with it (a malformed V6 literal previously threw here and
        // killed the whole tunnel — see V6_LOCAL/V6_DNS).
        runCatching {
            builder.addAddress(V6_LOCAL, 128).addDnsServer(V6_DNS).addRoute(V6_DNS, 128)
        }.onFailure { Log.w(TAG, "IPv6 tunnel config skipped (IPv4 stays up)", it) }
        // NB: we deliberately DO NOT addDisallowedApplication(self). This is a DNS-only split
        // tunnel (it routes only V4_DNS/V6_DNS), and DohClient reaches Cloudflare on :443 via
        // hardcoded bootstrap IPs — no :53 lookup, and 1.1.1.1 isn't in the tunnel's routes — so
        // there is no loopback. Including the app means S'CAN's OWN DNS is protected too, so the
        // Tier-B deep test (which resolves in-process) reports the real protected egress
        // (Cloudflare) instead of a false "ISP leak" from the one otherwise-exempt app.

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
        PreferencesManager.setDnsProtectionActive(this, true)
        Log.i(TAG, "tunnel ESTABLISHED fd=${pfd.fd}; protection flag set true")
        scope.launch { readLoop(pfd) }
        return true
    }

    private fun readLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(MAX_PKT)
        while (running) {
            val n = try { input.read(buf) } catch (_: Exception) { break }
            if (n < 0) break          // EOF: fd closed by teardown → exit (never busy-spin)
            if (n == 0) continue
            // DNS is overwhelmingly UDP; we parse only UDP and silently drop anything else (incl.
            // TCP/53). We never set the truncation (TC) bit on replies, so a client is never asked
            // to retry over TCP — a rare TCP-first client is the one known gap. DoH answers are
            // forwarded whole: one larger than the 4096 MTU (big DNSSEC/TXT) would be dropped by
            // the link rather than fragmented. Both are acceptable for a DNS-only beta tunnel.
            val datagram = DnsPacket.parseUdp(buf, n) ?: continue
            if (datagram.dstPort != DNS_PORT || datagram.payload.isEmpty()) continue
            scope.launch {
                inflight.withPermit {
                    val answer = doh.resolve(datagram.payload) ?: return@withPermit
                    val reply = DnsPacket.buildUdpReply(datagram, answer)
                    synchronized(writeLock) {
                        runCatching { output.write(reply); output.flush() }
                    }
                }
            }
        }
        // NB: do NOT close input/output here — they wrap the PFD's own (un-dup'd) file
        // descriptor, which teardown() closes exactly once. Closing them too would
        // double-close the fd and risk closing a reused, unrelated socket.
    }

    private fun teardown() {
        Log.i(TAG, "teardown — clearing protection flag, closing tun", Throwable("teardown caller"))
        running = false
        PreferencesManager.setDnsProtectionActive(this, false)
        // Cancel in-flight DNS workers but KEEP the scope alive (cancelling the scope itself
        // would make a same-instance restart silently never read the tunnel). Closing the
        // fd unblocks the read loop.
        runCatching { scope.coroutineContext.cancelChildren() }
        runCatching { tun?.close() }
        tun = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /** The OS calls this when another VPN displaces us, or the user revokes consent. */
    override fun onRevoke() {
        Log.w(TAG, "onRevoke — OS revoked our VPN (displaced by another VPN or consent withdrawn)")
        teardown()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        running = false
        PreferencesManager.setDnsProtectionActive(this, false)
        runCatching { scope.coroutineContext.cancelChildren() }
        runCatching { tun?.close() }
        tun = null
        runCatching { scope.cancel() }
        super.onDestroy()
    }

    // ── notification ──

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "DNS Protection", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while S'CAN is encrypting your DNS"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.dns_protect_notif_title))
            .setContentText(getString(R.string.dns_protect_notif_text))
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ScanDnsVpn"
        const val ACTION_STOP = "com.uow.scan.DNS_PROTECT_STOP"

        private const val SESSION = "S'CAN DNS Protection"
        private const val CHANNEL_ID = "scan_dns_vpn_channel"
        private const val NOTIF_ID = 1100
        private const val MTU = 4096   // fit large DoH answers (DNSSEC/TXT/long chains) in one datagram
        private const val MAX_PKT = 32767
        private const val DNS_PORT = 53

        // Virtual addresses that exist only inside the tun. NB: every IPv6 hextet must be valid
        // hex — the original "fd00:0:0:dns::x" literals were rejected by parseNumericAddress
        // ('n','s' aren't hex digits), so establish() threw and the tunnel NEVER came up. These
        // are valid ULA (fc00::/7) addresses.
        private const val V4_LOCAL = "10.111.222.1"
        private const val V4_DNS = "10.111.222.2"
        private const val V6_LOCAL = "fd00:5ca0::1"
        private const val V6_DNS = "fd00:5ca0::2"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ScanDnsVpnService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScanDnsVpnService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}

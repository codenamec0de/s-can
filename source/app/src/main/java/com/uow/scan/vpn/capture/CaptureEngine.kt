package com.uow.scan.vpn.capture

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.uow.scan.util.IpAsnDb
import com.uow.scan.util.NtmStore
import com.uow.scan.util.TrackerDomainMatcher
import com.uow.scan.vpn.DnsPacket
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * Stage-4b full-capture forwarder. When capture mode is on the tunnel routes all IPv4 (`0.0.0.0/0`),
 * so every app's connections arrive here as raw IP packets. We terminate TCP in userspace and relay
 * to the real destination over a `protect()`-ed socket, reading the first client bytes for SNI/Host
 * ([SniSniffer]) → real per-connection hostname + cleartext flag + per-destination bytes. UDP is
 * relayed too; DNS (:53) is handed back to the service's existing resolve/sinkhole/record path.
 *
 * Threading: one blocking **tun-reader** thread (app→net) + one **NIO selector** thread (net→app).
 * The tun is a *lossless* local channel to the app's kernel, so this is not a full TCP stack — no
 * retransmit/congestion control, only seq/ack bookkeeping, handshake, teardown, and window-based
 * flow control. Every flow is wrapped in try/catch; any error resets that flow, never the tunnel.
 *
 * IPv6 is intentionally NOT routed in capture mode → it bypasses the tunnel and keeps working, so a
 * bug here can never break IPv6 connectivity. This is experimental + flag-gated.
 */
internal class CaptureEngine(
    private val vpn: VpnService,
    private val context: Context,
    private val tun: ParcelFileDescriptor,
    /** Resolve/sinkhole/record a DNS datagram → reply IP-packet bytes (the service owns DNS logic). */
    private val dnsRespond: (DnsPacket.UdpDatagram) -> ByteArray?,
    /** Attribute a 5-tuple to an app UID (the service's getConnectionOwnerUid path). */
    private val attribute: (proto: Int, srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int) -> Int,
) {
    @Volatile private var running = false
    private lateinit var input: FileInputStream
    private lateinit var output: FileOutputStream
    private val writeLock = Any()
    private val selector: Selector = Selector.open()
    private val tcp = ConcurrentHashMap<String, TcpConn>()
    private val udp = ConcurrentHashMap<String, UdpConn>()
    private val rng = java.util.Random()
    /** Channel (de)registrations + interest-ops changes, queued by the tun thread and applied on the
     *  SELECTOR thread — registering a channel from another thread can deadlock with select(). */
    private val pendingReg = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    /** DNS resolution (DoH/system) must not block the tun-reader thread → run it off-thread. */
    private val dnsPool = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
        Thread(r, "ntm-capture-dns").apply { isDaemon = true }
    }

    fun start() {
        running = true
        input = FileInputStream(tun.fileDescriptor)
        output = FileOutputStream(tun.fileDescriptor)
        Thread({ tunLoop() }, "ntm-capture-tun").apply { isDaemon = true }.start()
        Thread({ selectorLoop() }, "ntm-capture-sel").apply { isDaemon = true }.start()
        Log.i(TAG, "capture engine started")
    }

    fun stop() {
        running = false
        runCatching { selector.wakeup() }
        tcp.values.forEach { runCatching { it.channel.close() } }
        udp.values.forEach { runCatching { it.channel.close() } }
        tcp.clear(); udp.clear()
        runCatching { dnsPool.shutdownNow() }
        runCatching { selector.close() }
        Log.i(TAG, "capture engine stopped")
    }

    private fun writeTun(pkt: ByteArray) {
        synchronized(writeLock) { runCatching { output.write(pkt); output.flush() } }
    }

    // ───────────────────────── app → net (tun reader) ─────────────────────────

    private fun tunLoop() {
        val buf = ByteArray(MAX_PKT)
        while (running) {
            val n = try { input.read(buf) } catch (_: Exception) { break }
            if (n <= 0) { if (n < 0) break else continue }
            if (IpPacket.version(buf, n) != 4) continue          // only v4 is routed here
            try {
                when (IpPacket.protocol(buf)) {
                    IpPacket.PROTO_TCP -> IpPacket.parseTcp(buf, n)?.let { onTcp(it) }
                    IpPacket.PROTO_UDP -> IpPacket.parseUdp(buf, n)?.let { onUdp(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "packet handling error", e)
            }
        }
    }

    // ───────────────────────── TCP ─────────────────────────

    private fun key(srcPort: Int, dstIp: ByteArray, dstPort: Int) =
        "$srcPort>${ip(dstIp)}:$dstPort"

    private fun onTcp(t: IpPacket.Tcp) {
        val k = key(t.srcPort, t.dstIp, t.dstPort)
        val conn = tcp[k]
        if (t.isSyn && !t.isAck && conn == null) {
            if (tcp.size >= MAX_FLOWS) { writeTun(rst(t)); return }
            openTcp(k, t)
            return
        }
        if (conn == null) {                              // unknown flow → reset so the app gives up fast
            if (!t.isRst) writeTun(rst(t))
            return
        }
        synchronized(conn) {
            if (t.isRst) { closeTcp(k, conn); return }
            // ACK of our data → advance flow-control window
            if (t.isAck) { conn.appAck = t.ack; conn.appWindow = t.window }
            if (t.payloadLen > 0) onClientData(k, conn, t)
            if (t.isFin) onClientFin(k, conn, t)
        }
        if (conn.paused && seqDistance(conn.mySeq, conn.appAck) < conn.effectiveWindow()) {
            conn.paused = false
            pendingReg.add { runCatching { conn.key2.interestOps(SelectionKey.OP_READ) } }
            selector.wakeup()
        }
    }

    private fun openTcp(k: String, t: IpPacket.Tcp) {
        val conn = TcpConn(t.srcIp, t.srcPort, t.dstIp, t.dstPort, clientNext = inc(t.seq, 1), myNext = nextIsn())
        try {
            val ch = SocketChannel.open()
            ch.configureBlocking(false)
            if (!vpn.protect(ch.socket())) { ch.close(); writeTun(rst(t)); return }
            conn.channel = ch
            conn.uid = runCatching { attribute(IpPacket.PROTO_TCP, t.srcIp, t.srcPort, t.dstIp, t.dstPort) }.getOrDefault(-1)
            ch.connect(InetSocketAddress(InetAddress.getByAddress(t.dstIp), t.dstPort))
            tcp[k] = conn
            if (DEBUG_CAP) Log.d(TAG, "SYN $k uid=${conn.uid} → connecting")
            // SYN-ACK back to the app FIRST (advertise MSS + our window), then queue the register.
            writeTun(seg(conn, IpPacket.SYN or IpPacket.ACK, null, 0, 0, mss = MSS))
            conn.mySeq = inc(conn.mySeq, 1)             // SYN consumes one sequence number
            pendingReg.add { runCatching { conn.key2 = ch.register(selector, SelectionKey.OP_CONNECT, Pair(k, conn)) } }
            selector.wakeup()
        } catch (e: Exception) {
            Log.w(TAG, "tcp open failed ${ip(t.dstIp)}:${t.dstPort}", e)
            runCatching { conn.channel.close() }
            tcp.remove(k); writeTun(rst(t))
        }
    }

    private fun onClientData(k: String, conn: TcpConn, t: IpPacket.Tcp) {
        if (t.seq != conn.clientNext) {                  // retransmit / reorder on a lossless tun → just re-ACK
            writeTun(seg(conn, IpPacket.ACK, null, 0, 0)); return
        }
        val data = t.data.copyOfRange(t.payloadOff, t.payloadOff + t.payloadLen)
        conn.clientNext = inc(conn.clientNext, t.payloadLen.toLong())
        conn.bytesUp += t.payloadLen
        if (!conn.sniffDone) sniffClientData(k, conn, data)
        if (conn.connected) writeUpstream(conn, data) else conn.pendingOut.add(data)
        writeTun(seg(conn, IpPacket.ACK, null, 0, 0))    // ACK the app's data
    }

    /** Buffer the first client bytes until the SNI/Host is parseable (a TLS ClientHello can span
     *  several TCP segments) or a small cap is hit, then record the flow exactly once. */
    private fun sniffClientData(k: String, conn: TcpConn, data: ByteArray) {
        val room = SNIFF_CAP - conn.sniffBuf.size()
        if (room > 0) conn.sniffBuf.write(data, 0, minOf(data.size, room))
        conn.sniffSegs++
        val buf = conn.sniffBuf.toByteArray()
        val r = SniSniffer.sniff(buf, 0, buf.size)
        val done = r.host != null || !r.encrypted ||
            conn.sniffBuf.size() >= SNIFF_CAP || conn.sniffSegs >= SNIFF_MAX_SEGS
        if (done) {
            conn.sniffDone = true
            conn.host = r.host; conn.enc = r.encrypted
            conn.sniffBuf.reset()
            if (DEBUG_CAP) Log.d(TAG, "flow $k host=${conn.host} enc=${conn.enc}")
            recordFlow(conn)
        }
    }

    private fun onClientFin(k: String, conn: TcpConn, t: IpPacket.Tcp) {
        conn.clientNext = inc(conn.clientNext, 1)        // FIN consumes one
        writeTun(seg(conn, IpPacket.ACK, null, 0, 0))
        runCatching { conn.channel.socket().shutdownOutput() } // half-close upstream
        conn.clientFin = true
    }

    private fun writeUpstream(conn: TcpConn, data: ByteArray) {
        try {
            val bb = ByteBuffer.wrap(data)
            while (bb.hasRemaining()) if (conn.channel.write(bb) == 0) break   // best-effort; socket buffers
        } catch (e: Exception) { resetFlowToApp(conn) }
    }

    // ───────────────────────── net → app (selector) ─────────────────────────

    private fun selectorLoop() {
        val rbuf = ByteBuffer.allocate(MAX_PKT)
        while (running) {
            try {
                while (true) { (pendingReg.poll() ?: break).invoke() }   // register on THIS thread
                if (selector.select(IDLE_SWEEP_MS) == 0) { sweepIdle(); continue }
            } catch (e: Exception) { if (!running) break else { Log.w(TAG, "selector loop", e); continue } }
            val it = selector.selectedKeys().iterator()
            while (it.hasNext()) {
                val key = it.next(); it.remove()
                if (!key.isValid) continue                 // flow closed mid-iteration → key cancelled
                @Suppress("UNCHECKED_CAST") val att = key.attachment() as? Pair<String, Any> ?: continue
                try {
                    when (val c = att.second) {
                        is TcpConn -> handleTcpKey(key, att.first, c, rbuf)
                        is UdpConn -> handleUdpKey(key, att.first, c, rbuf)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "selector key error", e); runCatching { key.cancel() }
                }
            }
        }
    }

    private fun handleTcpKey(key: SelectionKey, k: String, conn: TcpConn, rbuf: ByteBuffer) {
        if (key.isConnectable) {
            val ok = runCatching { conn.channel.finishConnect() }.getOrDefault(false)
            if (DEBUG_CAP) Log.d(TAG, "connect $k → $ok")
            if (ok) {
                conn.connected = true
                synchronized(conn) { conn.pendingOut.forEach { writeUpstream(conn, it) }; conn.pendingOut.clear() }
                key.interestOps(SelectionKey.OP_READ)
            } else { resetFlowToApp(conn); return }   // RST the app instead of leaving it hanging
        }
        if (key.isValid && key.isReadable) {
            synchronized(conn) {
                if (seqDistance(conn.mySeq, conn.appAck) >= conn.effectiveWindow()) {  // window full → pause
                    conn.paused = true; key.interestOps(0); return
                }
            }
            rbuf.clear()
            val n = runCatching { conn.channel.read(rbuf) }.getOrDefault(-1)
            if (n < 0) { onUpstreamEof(k, conn); return }
            if (n == 0) return
            rbuf.flip()
            val data = ByteArray(n); rbuf.get(data)
            conn.bytesDown += n
            synchronized(conn) {
                writeTun(seg(conn, IpPacket.PSH or IpPacket.ACK, data, 0, n))
                conn.mySeq = inc(conn.mySeq, n.toLong())
            }
        }
    }

    private fun onUpstreamEof(k: String, conn: TcpConn) {
        synchronized(conn) {
            writeTun(seg(conn, IpPacket.FIN or IpPacket.ACK, null, 0, 0))
            conn.mySeq = inc(conn.mySeq, 1)
        }
        runCatching { conn.channel.close() }
        // keep the flow briefly so a late app ACK/FIN is answered; idle sweep reaps it
        conn.upstreamClosed = true
        recordFlow(conn)            // final byte counts
    }

    // ───────────────────────── UDP ─────────────────────────

    private fun onUdp(u: IpPacket.Udp) {
        if (u.dstPort == DNS_PORT) {                     // DNS keeps the service's resolve/sinkhole path
            val datagram = DnsPacket.UdpDatagram(4, u.srcIp, u.dstIp, u.srcPort, u.dstPort,
                u.data.copyOfRange(u.payloadOff, u.payloadOff + u.payloadLen))
            dnsPool.execute { runCatching { dnsRespond(datagram) }.getOrNull()?.let { writeTun(it) } }
            return
        }
        val k = key(u.srcPort, u.dstIp, u.dstPort)
        val conn = udp.getOrPut(k) { openUdp(k, u) ?: return }
        conn.lastSeen = System.currentTimeMillis()
        val payload = u.data.copyOfRange(u.payloadOff, u.payloadOff + u.payloadLen)
        runCatching { conn.channel.write(ByteBuffer.wrap(payload)) }
    }

    private fun openUdp(k: String, u: IpPacket.Udp): UdpConn? = try {
        val ch = DatagramChannel.open()
        ch.configureBlocking(false)
        if (!vpn.protect(ch.socket())) { ch.close(); null }
        else {
            ch.connect(InetSocketAddress(InetAddress.getByAddress(u.dstIp), u.dstPort))
            val conn = UdpConn(u.srcIp, u.srcPort, u.dstIp, u.dstPort, ch)
            pendingReg.add { runCatching { ch.register(selector, SelectionKey.OP_READ, Pair(k, conn)) } }
            selector.wakeup()
            conn
        }
    } catch (e: Exception) { Log.w(TAG, "udp open failed", e); null }

    private fun handleUdpKey(key: SelectionKey, k: String, conn: UdpConn, rbuf: ByteBuffer) {
        rbuf.clear()
        val n = runCatching { conn.channel.read(rbuf) }.getOrDefault(-1)
        if (n <= 0) return
        rbuf.flip(); val data = ByteArray(n); rbuf.get(data)
        conn.lastSeen = System.currentTimeMillis()
        writeTun(IpPacket.buildUdp(conn.dstIp, conn.srcIp, conn.dstPort, conn.srcPort, data))
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun seg(c: TcpConn, flags: Int, payload: ByteArray?, off: Int, len: Int, mss: Int = -1): ByteArray =
        IpPacket.buildTcp(c.dstIp, c.srcIp, c.dstPort, c.srcPort, c.mySeq, c.clientNext, flags, OUR_WINDOW, payload, off, len, mss)

    private fun rst(t: IpPacket.Tcp): ByteArray =
        IpPacket.buildTcp(t.dstIp, t.srcIp, t.dstPort, t.srcPort, t.ack, inc(t.seq, (t.payloadLen + if (t.isSyn) 1 else 0).toLong()), IpPacket.RST or IpPacket.ACK, 0)

    private fun resetFlowToApp(conn: TcpConn) {
        writeTun(IpPacket.buildTcp(conn.dstIp, conn.srcIp, conn.dstPort, conn.srcPort, conn.mySeq, conn.clientNext, IpPacket.RST or IpPacket.ACK, 0))
        tcp.entries.removeIf { it.value === conn }
        runCatching { conn.channel.close() }
    }

    private fun closeTcp(k: String, conn: TcpConn) { tcp.remove(k); runCatching { conn.channel.close() } }

    private fun sweepIdle() {
        val now = System.currentTimeMillis()
        udp.entries.removeIf { (now - it.value.lastSeen > UDP_IDLE_MS).also { dead -> if (dead) runCatching { it.value.channel.close() } } }
        tcp.entries.removeIf { (it.value.upstreamClosed && now - it.value.created > TCP_LINGER_MS).also { dead -> if (dead) runCatching { it.value.channel.close() } } }
    }

    private fun recordFlow(conn: TcpConn) {
        val host = conn.host ?: return
        val trk = runCatching { TrackerDomainMatcher.match(context, host) }.getOrNull()
        if (trk != null) {
            runCatching {
                NtmStore.enrichmentFor(host) ?: run {
                    val info = IpAsnDb.lookup(context, conn.dstIp)
                    if (info != null) NtmStore.enrichHost(host, info.org, "AS${info.asn}", info.country)
                }
            }
        } else {
            runCatching {
                if (NtmStore.enrichmentFor(host) == null) {
                    IpAsnDb.lookup(context, conn.dstIp)?.let { NtmStore.enrichHost(host, it.org, "AS${it.asn}", it.country) }
                }
            }
        }
        NtmStore.recordFlow(conn.uid, host, conn.dstPort, "TCP", conn.enc, conn.bytesUp + conn.bytesDown)
    }

    private fun nextIsn(): Long = (rng.nextInt().toLong() and IpPacket.MASK32)
    private fun inc(v: Long, by: Long): Long = (v + by) and IpPacket.MASK32
    private fun seqDistance(a: Long, b: Long): Long = (a - b) and IpPacket.MASK32
    private fun ip(b: ByteArray) = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}"

    // ───────────────────────── flow state ─────────────────────────

    private inner class TcpConn(
        val srcIp: ByteArray, val srcPort: Int, val dstIp: ByteArray, val dstPort: Int,
        var clientNext: Long, var myNext: Long,
    ) {
        lateinit var channel: SocketChannel
        lateinit var key2: SelectionKey
        @Volatile var mySeq = myNext
        @Volatile var appAck = myNext
        @Volatile var appWindow = 65535
        @Volatile var connected = false
        @Volatile var clientFin = false
        @Volatile var upstreamClosed = false
        @Volatile var paused = false
        @Volatile var sniffDone = false
        var sniffSegs = 0
        val sniffBuf = java.io.ByteArrayOutputStream()
        @Volatile var host: String? = null
        @Volatile var enc = true
        @Volatile var uid = -1
        @Volatile var bytesUp = 0L
        @Volatile var bytesDown = 0L
        val created = System.currentTimeMillis()
        val pendingOut = ArrayDeque<ByteArray>()
        fun effectiveWindow(): Long = appWindow.toLong().coerceAtLeast(1L)
    }

    private inner class UdpConn(
        val srcIp: ByteArray, val srcPort: Int, val dstIp: ByteArray, val dstPort: Int,
        val channel: DatagramChannel,
    ) { @Volatile var lastSeen = System.currentTimeMillis() }

    companion object {
        private const val TAG = "NtmCapture"
        private const val DEBUG_CAP = false  // flow lifecycle logging — diagnostics only, off by default
        private const val SNIFF_CAP = 4096   // bytes to buffer for a (possibly multi-segment) ClientHello
        private const val SNIFF_MAX_SEGS = 5
        private const val MAX_PKT = 32767
        private const val MAX_FLOWS = 512
        private const val MSS = 1460
        private const val OUR_WINDOW = 65535
        private const val DNS_PORT = 53
        private const val IDLE_SWEEP_MS = 5000L
        private const val UDP_IDLE_MS = 30_000L
        private const val TCP_LINGER_MS = 10_000L
    }
}

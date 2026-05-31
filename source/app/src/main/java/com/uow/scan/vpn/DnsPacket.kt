package com.uow.scan.vpn

import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal IPv4/IPv6 + UDP packet helpers for the DNS-only VPN. We only ever touch UDP
 * datagrams destined for our virtual DNS server, extract the DNS wire payload, and re-frame
 * a reply with src/dst (and ports) swapped. Anything that isn't plain UDP is ignored.
 *
 * No external dependency — hand-rolled header parsing + checksums, which is all DNS needs.
 */
internal object DnsPacket {

    private const val PROTO_UDP = 17
    private val ipIdCounter = AtomicInteger(0)

    /** A parsed UDP/DNS datagram pulled off the tun. [srcIp]/[dstIp] are raw bytes (4 or 16). */
    data class UdpDatagram(
        val ipVersion: Int,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray,
    )

    /** Parse an IP packet; returns the UDP datagram if it is UDP, else null. */
    fun parseUdp(pkt: ByteArray, len: Int): UdpDatagram? {
        if (len < 1) return null
        return when ((pkt[0].toInt() ushr 4) and 0xF) {
            4 -> parseV4(pkt, len)
            6 -> parseV6(pkt, len)
            else -> null
        }
    }

    private fun parseV4(pkt: ByteArray, len: Int): UdpDatagram? {
        if (len < 20) return null
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if ((pkt[9].toInt() and 0xFF) != PROTO_UDP) return null
        val src = pkt.copyOfRange(12, 16)
        val dst = pkt.copyOfRange(16, 20)
        return udp(4, src, dst, pkt, ihl, len)
    }

    private fun parseV6(pkt: ByteArray, len: Int): UdpDatagram? {
        if (len < 40) return null
        // No extension-header walking: DNS datagrams use a bare UDP next-header in practice.
        if ((pkt[6].toInt() and 0xFF) != PROTO_UDP) return null
        val src = pkt.copyOfRange(8, 24)
        val dst = pkt.copyOfRange(24, 40)
        return udp(6, src, dst, pkt, 40, len)
    }

    private fun udp(ver: Int, src: ByteArray, dst: ByteArray, pkt: ByteArray, u: Int, len: Int): UdpDatagram? {
        if (len < u + 8) return null
        val srcPort = u16(pkt, u)
        val dstPort = u16(pkt, u + 2)
        val udpLen = u16(pkt, u + 4)
        val payStart = u + 8
        val payEnd = minOf(len, u + maxOf(udpLen, 8))
        if (payStart > payEnd) return null
        return UdpDatagram(ver, src, dst, srcPort, dstPort, pkt.copyOfRange(payStart, payEnd))
    }

    /**
     * Build a reply IP packet carrying [answer] as a UDP datagram, addressed back to the
     * original sender: src = original dst (our resolver), dst = original src; ports swapped.
     */
    fun buildUdpReply(q: UdpDatagram, answer: ByteArray): ByteArray =
        if (q.ipVersion == 4) buildV4(q, answer) else buildV6(q, answer)

    private fun buildV4(q: UdpDatagram, answer: ByteArray): ByteArray {
        val udpLen = 8 + answer.size
        val total = 20 + udpLen
        val p = ByteArray(total)
        p[0] = 0x45.toByte()                 // IPv4, IHL = 5
        put16(p, 2, total)                   // total length
        put16(p, 4, ipIdCounter.getAndIncrement() and 0xFFFF) // identification (varies for fragmentation)
        put16(p, 6, 0)                       // flags: allow fragmentation (DF clear)
        p[8] = 64                            // TTL
        p[9] = PROTO_UDP.toByte()
        System.arraycopy(q.dstIp, 0, p, 12, 4)   // src = original dst
        System.arraycopy(q.srcIp, 0, p, 16, 4)   // dst = original src
        put16(p, 10, checksum(p, 0, 20))         // IPv4 header checksum
        val u = 20
        put16(p, u, q.dstPort)               // src port = our :53
        put16(p, u + 2, q.srcPort)           // dst port = client's ephemeral
        put16(p, u + 4, udpLen)
        // UDP checksum is optional over IPv4 → leave 0.
        System.arraycopy(answer, 0, p, u + 8, answer.size)
        return p
    }

    private fun buildV6(q: UdpDatagram, answer: ByteArray): ByteArray {
        val udpLen = 8 + answer.size
        val total = 40 + udpLen
        val p = ByteArray(total)
        p[0] = 0x60.toByte()                 // IPv6
        put16(p, 4, udpLen)                  // payload length
        p[6] = PROTO_UDP.toByte()            // next header
        p[7] = 64                            // hop limit
        System.arraycopy(q.dstIp, 0, p, 8, 16)   // src = original dst
        System.arraycopy(q.srcIp, 0, p, 24, 16)  // dst = original src
        val u = 40
        put16(p, u, q.dstPort)
        put16(p, u + 2, q.srcPort)
        put16(p, u + 4, udpLen)
        System.arraycopy(answer, 0, p, u + 8, answer.size)
        // UDP checksum is mandatory over IPv6.
        val ck = udpChecksumV6(p, u, udpLen)
        put16(p, u + 6, if (ck == 0) 0xFFFF else ck)
        return p
    }

    // ── checksums (16-bit one's complement) ──

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        val end = off + len
        while (i + 1 < end) { sum += u16(b, i).toLong(); i += 2 }
        if (i < end) sum += ((b[i].toInt() and 0xFF) shl 8).toLong()
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** UDP checksum over the IPv6 pseudo-header (src+dst at offset 8, contiguous 32 bytes) + UDP. */
    private fun udpChecksumV6(p: ByteArray, udpOff: Int, udpLen: Int): Int {
        var sum = 0L
        var i = 8
        while (i < 8 + 32) { sum += u16(p, i).toLong(); i += 2 }   // src(16) + dst(16)
        sum += (udpLen.toLong() and 0xFFFF)
        sum += PROTO_UDP.toLong()
        var j = udpOff
        val end = udpOff + udpLen
        while (j + 1 < end) { sum += u16(p, j).toLong(); j += 2 }
        if (j < end) sum += ((p[j].toInt() and 0xFF) shl 8).toLong()
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v ushr 8) and 0xFF).toByte()
        b[o + 1] = (v and 0xFF).toByte()
    }
}

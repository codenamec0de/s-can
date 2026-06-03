package com.uow.scan.vpn.capture

/**
 * Minimal IPv4 + TCP/UDP packet parse/build for the Stage-4 capture forwarder. **IPv4 only** — the
 * capture tunnel routes only `0.0.0.0/0` and leaves IPv6 to bypass (so a forwarder bug can never
 * break IPv6 connectivity), which means the forwarder only ever sees v4 packets here.
 *
 * Hand-rolled headers + one's-complement checksums, no dependency. Sequence/ack numbers are carried
 * as [Long] (0 .. 2^32-1) to dodge Kotlin's signed-Int wrap; mask with [MASK32] when arithmetic wraps.
 */
internal object IpPacket {

    const val PROTO_TCP = 6
    const val PROTO_UDP = 17
    const val MASK32 = 0xFFFFFFFFL

    // TCP flag bits
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    fun version(p: ByteArray, len: Int): Int = if (len < 1) 0 else (p[0].toInt() ushr 4) and 0xF
    fun protocol(p: ByteArray): Int = p[9].toInt() and 0xFF
    private fun ihl(p: ByteArray): Int = (p[0].toInt() and 0x0F) * 4

    // ───────────────────────── TCP ─────────────────────────

    class Tcp(
        val srcIp: ByteArray, val dstIp: ByteArray,
        val srcPort: Int, val dstPort: Int,
        val seq: Long, val ack: Long, val flags: Int, val window: Int,
        val data: ByteArray, val payloadOff: Int, val payloadLen: Int,
    ) {
        val isSyn get() = flags and SYN != 0
        val isAck get() = flags and ACK != 0
        val isFin get() = flags and FIN != 0
        val isRst get() = flags and RST != 0
    }

    fun parseTcp(p: ByteArray, len: Int): Tcp? {
        val ih = ihl(p)
        if (ih < 20 || len < ih + 20) return null
        val o = ih
        val dataOff = ((p[o + 12].toInt() ushr 4) and 0xF) * 4
        if (dataOff < 20 || o + dataOff > len) return null
        val payOff = o + dataOff
        return Tcp(
            srcIp = p.copyOfRange(12, 16), dstIp = p.copyOfRange(16, 20),
            srcPort = u16(p, o), dstPort = u16(p, o + 2),
            seq = u32(p, o + 4), ack = u32(p, o + 8),
            flags = p[o + 13].toInt() and 0x3F, window = u16(p, o + 14),
            data = p, payloadOff = payOff, payloadLen = (len - payOff).coerceAtLeast(0),
        )
    }

    /**
     * Build an IPv4+TCP segment addressed back to the app (caller passes src=remote, dst=appLocal).
     * Optional single MSS option (only on SYN-ACK). Both IP + TCP checksums are filled in.
     */
    fun buildTcp(
        srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int, window: Int,
        payload: ByteArray? = null, payOff: Int = 0, payLen: Int = 0, mss: Int = -1,
    ): ByteArray {
        val optLen = if (mss >= 0) 4 else 0
        val tcpHdr = 20 + optLen
        val total = 20 + tcpHdr + payLen
        val pk = ByteArray(total)
        // IPv4 header
        pk[0] = 0x45
        put16(pk, 2, total)
        pk[6] = 0x40            // DF
        pk[8] = 64              // TTL
        pk[9] = PROTO_TCP.toByte()
        System.arraycopy(srcIp, 0, pk, 12, 4)
        System.arraycopy(dstIp, 0, pk, 16, 4)
        put16(pk, 10, checksum(pk, 0, 20))
        // TCP header
        val o = 20
        put16(pk, o, srcPort); put16(pk, o + 2, dstPort)
        put32(pk, o + 4, seq); put32(pk, o + 8, ack)
        pk[o + 12] = ((tcpHdr / 4) shl 4).toByte()
        pk[o + 13] = flags.toByte()
        put16(pk, o + 14, window)
        if (mss >= 0) { pk[o + 20] = 2; pk[o + 21] = 4; put16(pk, o + 22, mss) }
        if (payload != null && payLen > 0) System.arraycopy(payload, payOff, pk, o + tcpHdr, payLen)
        put16(pk, o + 16, tcpUdpChecksum(pk, srcIp, dstIp, PROTO_TCP, o, tcpHdr + payLen))
        return pk
    }

    // ───────────────────────── UDP ─────────────────────────

    class Udp(
        val srcIp: ByteArray, val dstIp: ByteArray,
        val srcPort: Int, val dstPort: Int,
        val data: ByteArray, val payloadOff: Int, val payloadLen: Int,
    )

    fun parseUdp(p: ByteArray, len: Int): Udp? {
        val ih = ihl(p)
        if (ih < 20 || len < ih + 8) return null
        val o = ih
        val ulen = u16(p, o + 4)
        val payOff = o + 8
        val payEnd = minOf(len, o + maxOf(ulen, 8))
        if (payOff > payEnd) return null
        return Udp(
            p.copyOfRange(12, 16), p.copyOfRange(16, 20),
            u16(p, o), u16(p, o + 2), p, payOff, payEnd - payOff,
        )
    }

    /** Build an IPv4+UDP datagram back to the app (src=remote, dst=appLocal). */
    fun buildUdp(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val pk = ByteArray(total)
        pk[0] = 0x45; put16(pk, 2, total); pk[6] = 0x40; pk[8] = 64; pk[9] = PROTO_UDP.toByte()
        System.arraycopy(srcIp, 0, pk, 12, 4); System.arraycopy(dstIp, 0, pk, 16, 4)
        put16(pk, 10, checksum(pk, 0, 20))
        val o = 20
        put16(pk, o, srcPort); put16(pk, o + 2, dstPort); put16(pk, o + 4, udpLen)
        System.arraycopy(payload, 0, pk, o + 8, payload.size)
        val ck = tcpUdpChecksum(pk, srcIp, dstIp, PROTO_UDP, o, udpLen)
        put16(pk, o + 6, if (ck == 0) 0xFFFF else ck)
        return pk
    }

    // ───────────────────────── checksums / ints ─────────────────────────

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L; var i = off; val end = off + len
        while (i + 1 < end) { sum += u16(b, i).toLong(); i += 2 }
        if (i < end) sum += ((b[i].toInt() and 0xFF) shl 8).toLong()
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** Checksum over the IPv4 pseudo-header + the L4 segment (TCP or UDP). */
    private fun tcpUdpChecksum(pk: ByteArray, src: ByteArray, dst: ByteArray, proto: Int, off: Int, len: Int): Int {
        var sum = 0L
        sum += u16(src, 0).toLong() + u16(src, 2).toLong()
        sum += u16(dst, 0).toLong() + u16(dst, 2).toLong()
        sum += proto.toLong() + len.toLong()
        var i = off; val end = off + len
        while (i + 1 < end) { sum += u16(pk, i).toLong(); i += 2 }
        if (i < end) sum += ((pk[i].toInt() and 0xFF) shl 8).toLong()
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = ((v ushr 8) and 0xFF).toByte(); b[o + 1] = (v and 0xFF).toByte() }
    private fun put32(b: ByteArray, o: Int, v: Long) {
        b[o] = ((v ushr 24) and 0xFF).toByte(); b[o + 1] = ((v ushr 16) and 0xFF).toByte()
        b[o + 2] = ((v ushr 8) and 0xFF).toByte(); b[o + 3] = (v and 0xFF).toByte()
    }
}

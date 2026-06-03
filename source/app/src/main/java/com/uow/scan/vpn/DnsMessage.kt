package com.uow.scan.vpn

/**
 * Minimal DNS message reader/synthesizer for the NTM monitor + sinkhole. The unified tunnel only
 * needs two cheap things from the wire-format **question** section: the queried name (for per-app
 * attribution + tracker matching) and the query type (to synthesize a matching block answer).
 *
 * DNS *answer* parsing (with name compression, RDATA, etc.) is intentionally NOT implemented —
 * the upstream (DoH or the system resolver) returns the answer bytes already and we forward them
 * verbatim. Question names are never compressed, so label parsing here stays simple and safe.
 */
internal object DnsMessage {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    /**
     * A parsed first question: lowercased [qname] (no trailing dot), the [qtype], and
     * [questionEnd] — the byte offset just past the question (header + QNAME + QTYPE + QCLASS),
     * so a synthesized answer can be appended right after it.
     */
    data class Question(val qname: String, val qtype: Int, val questionEnd: Int)

    /** Parse the first question of a DNS query payload; null if malformed or there is none. */
    fun parseQuestion(p: ByteArray): Question? {
        if (p.size < 12) return null
        if (u16(p, 4) < 1) return null              // QDCOUNT
        var i = 12
        val sb = StringBuilder()
        while (i < p.size) {
            val len = p[i].toInt() and 0xFF
            if (len == 0) { i++; break }            // root label → name complete
            if (len and 0xC0 != 0) return null      // a compression pointer is invalid in a question
            if (i + 1 + len > p.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (j in 0 until len) sb.append((p[i + 1 + j].toInt() and 0xFF).toChar()) // ASCII labels
            i += 1 + len
        }
        if (i + 4 > p.size) return null
        val qtype = u16(p, i)
        return Question(sb.toString().lowercase(), qtype, i + 4) // + QTYPE(2) + QCLASS(2)
    }

    /**
     * Synthesize a "blocked" response for [query] given its parsed [q]. For A/AAAA we return a
     * NOERROR answer pinned to the unroutable 0.0.0.0 / :: (so the app's connect() fails fast and
     * locally — the Pi-hole approach); for any other type we return NXDOMAIN. Built by echoing the
     * original header + question and flipping it into a response.
     */
    fun buildBlockResponse(query: ByteArray, q: Question): ByteArray {
        val sinkhole: ByteArray? = when (q.qtype) {
            TYPE_A -> ByteArray(4)       // 0.0.0.0
            TYPE_AAAA -> ByteArray(16)   // ::
            else -> null
        }
        val base = q.questionEnd
        if (sinkhole == null) {
            val out = query.copyOf(base)
            setResponseFlags(out, rcode = 3)   // NXDOMAIN
            put16(out, 6, 0)                   // ANCOUNT = 0
            return out
        }
        val rdlen = sinkhole.size
        val out = query.copyOf(base + 12 + rdlen) // name ptr(2)+type(2)+class(2)+ttl(4)+rdlen(2)+rdata
        setResponseFlags(out, rcode = 0)          // NOERROR
        put16(out, 6, 1)                          // ANCOUNT = 1
        var o = base
        out[o] = 0xC0.toByte(); out[o + 1] = 0x0C // NAME → pointer to the question at offset 12
        o += 2
        put16(out, o, q.qtype); o += 2            // TYPE
        put16(out, o, 1); o += 2                  // CLASS = IN
        put32(out, o, 60); o += 4                 // TTL = 60s
        put16(out, o, rdlen); o += 2              // RDLENGTH
        System.arraycopy(sinkhole, 0, out, o, rdlen)
        return out
    }

    /** The first A-record IPv4 address (4 bytes) in a DNS response, or null. Used to enrich a
     *  destination with ASN/org/country ([com.uow.scan.util.IpAsnDb]); answer names may be
     *  compressed, so we skip past them rather than decode them. */
    fun firstAnswerIp(resp: ByteArray): ByteArray? {
        if (resp.size < 12) return null
        val qd = u16(resp, 4)
        val an = u16(resp, 6)
        if (an < 1) return null
        var i = 12
        repeat(qd) {
            i = skipName(resp, i) ?: return null
            i += 4                                   // QTYPE + QCLASS
        }
        repeat(an) {
            i = skipName(resp, i) ?: return null
            if (i + 10 > resp.size) return null
            val type = u16(resp, i)
            val rdlen = u16(resp, i + 8)
            val rd = i + 10
            if (rd + rdlen > resp.size) return null
            if (type == TYPE_A && rdlen == 4) return resp.copyOfRange(rd, rd + 4)
            i = rd + rdlen
        }
        return null
    }

    /** Advance past a DNS name at [start]; a compression pointer ends the name in 2 bytes. */
    private fun skipName(p: ByteArray, start: Int): Int? {
        var i = start
        while (i < p.size) {
            val len = p[i].toInt() and 0xFF
            if (len == 0) return i + 1
            if (len and 0xC0 == 0xC0) return i + 2
            i += 1 + len
        }
        return null
    }

    /** byte2: set QR=1, preserve opcode + RD, clear AA/TC. byte3: set RA=1, Z=0, [rcode]. */
    private fun setResponseFlags(p: ByteArray, rcode: Int) {
        p[2] = (0x80 or (p[2].toInt() and 0x78) or (p[2].toInt() and 0x01)).toByte()
        p[3] = (0x80 or (rcode and 0x0F)).toByte()
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v ushr 8) and 0xFF).toByte(); b[o + 1] = (v and 0xFF).toByte()
    }
    private fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v ushr 24) and 0xFF).toByte(); b[o + 1] = ((v ushr 16) and 0xFF).toByte()
        b[o + 2] = ((v ushr 8) and 0xFF).toByte(); b[o + 3] = (v and 0xFF).toByte()
    }
}

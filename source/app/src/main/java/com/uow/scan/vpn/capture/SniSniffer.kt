package com.uow.scan.vpn.capture

/**
 * Reads the destination hostname + encryption posture from the **first client→server bytes** of a
 * captured TCP stream:
 *  - a TLS ClientHello → the SNI server_name (encrypted = true)
 *  - a plaintext HTTP request → the `Host:` header (encrypted = false, i.e. cleartext)
 *  - anything else → no host, assumed encrypted (we never *claim* cleartext we didn't prove)
 *
 * This is what turns "app talked to 142.250.x.x" into "app talked to youtube.com over HTTPS" — the
 * Stage-4 capture value the DNS-only tunnel can't see. All parsing is bounds-checked; a short or
 * malformed ClientHello just yields a null host (best-effort, never throws).
 */
internal object SniSniffer {

    data class Result(val host: String?, val encrypted: Boolean)

    private val HTTP_METHODS = arrayOf(
        "GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT ", "TRACE ",
    )

    fun sniff(d: ByteArray, off: Int, len: Int): Result {
        if (len < 1) return Result(null, true)
        if ((d[off].toInt() and 0xFF) == 0x16) {            // TLS handshake record
            return Result(parseSni(d, off, len), true)
        }
        val head = runCatching { String(d, off, minOf(len, 8), Charsets.US_ASCII) }.getOrDefault("")
        if (HTTP_METHODS.any { head.startsWith(it) }) {
            return Result(parseHttpHost(d, off, len), false) // plaintext HTTP → cleartext
        }
        return Result(null, true)                           // unknown binary → don't claim cleartext
    }

    private val HOST_RE = Regex("(?im)^Host:[ \\t]*([^\\r\\n]+)")

    private fun parseHttpHost(d: ByteArray, off: Int, len: Int): String? {
        val s = runCatching { String(d, off, minOf(len, 2048), Charsets.US_ASCII) }.getOrNull() ?: return null
        val raw = HOST_RE.find(s)?.groupValues?.get(1)?.trim() ?: return null
        return raw.substringBefore(':').lowercase().ifBlank { null }   // strip :port
    }

    /** Walk a TLS ClientHello to the server_name extension. Returns the SNI host or null. */
    private fun parseSni(d: ByteArray, off: Int, len: Int): String? {
        val end = off + len
        var p = off
        if (p + 5 > end) return null                         // TLS record header
        val recLen = u16(d, p + 3)
        p += 5
        val recEnd = minOf(end, p + recLen)
        if (p + 4 > recEnd || (d[p].toInt() and 0xFF) != 0x01) return null  // ClientHello
        val hsEnd = minOf(recEnd, p + 4 + u24(d, p + 1))
        p += 4
        p += 2 + 32                                          // client_version + random
        if (p + 1 > hsEnd) return null
        p += 1 + (d[p].toInt() and 0xFF)                     // session_id
        if (p + 2 > hsEnd) return null
        p += 2 + u16(d, p)                                   // cipher_suites
        if (p + 1 > hsEnd) return null
        p += 1 + (d[p].toInt() and 0xFF)                     // compression_methods
        if (p + 2 > hsEnd) return null
        val extEnd = minOf(hsEnd, p + 2 + u16(d, p))
        p += 2
        while (p + 4 <= extEnd) {
            val type = u16(d, p)
            val dataLen = u16(d, p + 2)
            p += 4
            if (p + dataLen > extEnd) return null
            if (type == 0x0000) {                            // server_name
                var q = p
                if (q + 2 > p + dataLen) return null
                val listEnd = minOf(p + dataLen, q + 2 + u16(d, q))
                q += 2
                while (q + 3 <= listEnd) {
                    val nameType = d[q].toInt() and 0xFF
                    val nameLen = u16(d, q + 1)
                    q += 3
                    if (q + nameLen > listEnd) return null
                    if (nameType == 0) {                     // host_name
                        return runCatching { String(d, q, nameLen, Charsets.US_ASCII).lowercase() }.getOrNull()
                    }
                    q += nameLen
                }
                return null
            }
            p += dataLen
        }
        return null
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u24(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)
}

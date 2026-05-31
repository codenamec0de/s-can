package com.uow.scan.vpn

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * RFC 8484 DNS-over-HTTPS client. Takes a raw DNS query (wire format) and returns the raw
 * DNS answer, fetched over HTTPS from Cloudflare. This is the engine behind the Protect
 * feature: the device's DNS leaves the phone encrypted, not in plaintext to the ISP/router.
 *
 * Bootstrap without plaintext DNS: Cloudflare's anycast IPs for cloudflare-dns.com are
 * hardcoded, so resolving the DoH endpoint itself never leaks a plaintext lookup. SNI still
 * uses the hostname, so normal certificate hostname verification applies.
 */
internal class DohClient {

    private val dnsMessage = "application/dns-message".toMediaType()

    private val bootstrap = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname.equals(DOH_HOST, ignoreCase = true)) BOOTSTRAP_IPS.map(InetAddress::getByName)
            else Dns.SYSTEM.lookup(hostname)
    }

    private val client = OkHttpClient.Builder()
        .dns(bootstrap)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Resolve [query] (DNS wire format) over DoH; returns the answer bytes, or null on failure. */
    fun resolve(query: ByteArray): ByteArray? = try {
        val req = Request.Builder()
            .url(DOH_URL)
            .header("Accept", "application/dns-message")
            .post(query.toRequestBody(dnsMessage))
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val DOH_HOST = "cloudflare-dns.com"
        private const val DOH_URL = "https://cloudflare-dns.com/dns-query"
        private val BOOTSTRAP_IPS =
            listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
    }
}

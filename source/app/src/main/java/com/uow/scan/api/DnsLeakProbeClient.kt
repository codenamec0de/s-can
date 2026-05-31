package com.uow.scan.api

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit client for the Tier-B DNS egress result API (dnsprobe-api.scan-ai.xyz).
 *
 * Deliberately **not** certificate-pinned — unlike [ScanAiClient], which carries SMS content.
 * This endpoint is Cloudflare-proxied and returns only a random token's resolver owner/geo
 * (non-sensitive), and Cloudflare may issue the edge cert for a freshly-added proxied host from
 * a different CA than scan-api's (Let's Encrypt vs Google Trust Services), which would make a
 * reused pin silently fail. Standard system-CA TLS validation is the right trade-off here:
 * the channel is still encrypted and authenticated, with no pin-rotation footgun.
 */
object DnsLeakProbeClient {

    private const val BASE_URL = "https://dnsprobe-api.scan-ai.xyz/"

    @Volatile
    private var api: DnsLeakProbeService? = null

    fun getApi(): DnsLeakProbeService {
        api?.let { return it }
        return synchronized(this) { api ?: build().also { api = it } }
    }

    private fun build(): DnsLeakProbeService {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DnsLeakProbeService::class.java)
    }
}

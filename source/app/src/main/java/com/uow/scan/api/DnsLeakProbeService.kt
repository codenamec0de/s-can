package com.uow.scan.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Tier-B DNS egress test result API — `GET /result/<token>` on dnsprobe-api.scan-ai.xyz.
 * Returns which resolver actually queried our authoritative nameserver for the token,
 * enriched (offline, on the server) with owner / ASN / location and a coarse [ProbeResultResponse.kind].
 */
interface DnsLeakProbeService {

    @GET("result/{token}")
    suspend fun result(@Path("token") token: String): Response<ProbeResultResponse>

    data class ProbeResultResponse(
        val status: String,             // ok | pending | invalid
        val resolver_ip: String? = null,
        val org: String? = null,
        val asn: Int? = null,
        val country: String? = null,
        val city: String? = null,
        val transport: String? = null,
        val kind: String? = null,       // vpn | public_resolver | hosting | isp | unknown
        val age_seconds: Int? = null,
    )
}

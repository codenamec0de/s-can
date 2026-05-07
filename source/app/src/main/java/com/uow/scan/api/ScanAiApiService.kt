package com.uow.scan.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Public S'CAN AI sidecar contract — see SCAN_AI_SERVER_COOKBOOK.md.
 *
 * Server returns verdict lowercase ("scam" / "safe"); the worker uppercases at the boundary.
 */
interface ScanAiApiService {

    @POST("classify")
    suspend fun classify(@Body request: ClassifyRequest): Response<ClassifyResponse>

    @GET("health")
    suspend fun health(): Response<HealthResponse>

    data class ClassifyRequest(
        val sms: String
    )

    data class ClassifyResponse(
        val verdict: String,
        val reasoning: String,
        val confidence: Double,
        val model: String? = null,
        val latency_ms: Int? = null
    )

    data class HealthResponse(
        val status: String,
        val model: String
    )
}

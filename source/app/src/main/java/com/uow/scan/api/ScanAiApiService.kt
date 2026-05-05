package com.uow.scan.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ScanAiApiService {

    @POST("v1/classify")
    suspend fun classify(@Body request: ClassifyRequest): Response<ClassifyResponse>

    @POST("v1/url-check")
    suspend fun urlCheck(@Body request: UrlCheckRequest): Response<UrlCheckResponse>

    @GET("v1/health")
    suspend fun health(): Response<HealthResponse>

    @POST("v1/feedback")
    suspend fun feedback(@Body request: FeedbackRequest): Response<Map<String, String>>

    data class ClassifyRequest(
        val text: String,
        val sender: String? = null
    )

    data class ClassifyResponse(
        val verdict: String,
        val confidence: Double,
        val explanation: String,
        val urls: List<String> = emptyList()
    )

    data class UrlCheckRequest(
        val url: String,
        val deep: Boolean = true
    )

    data class UrlCheckResponse(
        val url: String,
        val verdict: String,
        val brand_match: String? = null,
        val brand_confidence: Double = 0.0,
        val signals: List<UrlSignal> = emptyList(),
        val risk_score: Double = 0.0
    )

    data class UrlSignal(
        val type: String,
        val value: String,
        val weight: Double
    )

    data class HealthResponse(
        val status: String,
        val model: String,
        val ollama_ok: Boolean
    )

    data class FeedbackRequest(
        val message_text: String,
        val original_verdict: String,
        val correct_verdict: String
    )
}

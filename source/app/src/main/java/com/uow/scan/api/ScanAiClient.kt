package com.uow.scan.api

import android.content.Context
import com.uow.scan.util.PreferencesManager
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit client for the public S'CAN AI sidecar at scan-api.scan-ai.xyz.
 *
 * TLS pins (SHA-256 of SubjectPublicKeyInfo, base64):
 *   - Cloudflare Let's Encrypt E7 intermediate — current chain link.
 *   - ISRG Root X1 — backup; root is stable until 2035.
 *
 * Re-extracting the E7 pin on rotation: see SCAN_AI_SERVER_COOKBOOK.md
 * § "Re-extracting pins on rotation".
 */
object ScanAiClient {

    private const val PINNED_HOST = "scan-api.scan-ai.xyz"
    private const val PIN_LE_E7_INTERMEDIATE = "sha256/y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU="
    private const val PIN_ISRG_ROOT_X1 = "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M="

    @Volatile
    private var retrofit: Retrofit? = null

    @Volatile
    private var currentBaseUrl: String? = null

    private fun buildRetrofit(baseUrl: String): Retrofit {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val pinner = CertificatePinner.Builder()
            .add(PINNED_HOST, PIN_LE_E7_INTERMEDIATE, PIN_ISRG_ROOT_X1)
            .build()

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .certificatePinner(pinner)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    fun getApi(context: Context): ScanAiApiService {
        val url = PreferencesManager.getSmsServerUrl(context)
        val normalizedUrl = if (url.endsWith("/")) url else "$url/"

        if (retrofit == null || currentBaseUrl != normalizedUrl) {
            synchronized(this) {
                if (retrofit == null || currentBaseUrl != normalizedUrl) {
                    retrofit = buildRetrofit(normalizedUrl)
                    currentBaseUrl = normalizedUrl
                }
            }
        }

        return retrofit!!.create(ScanAiApiService::class.java)
    }

    fun reset() {
        synchronized(this) {
            retrofit = null
            currentBaseUrl = null
        }
    }
}

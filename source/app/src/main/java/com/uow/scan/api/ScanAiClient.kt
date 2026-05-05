package com.uow.scan.api

import android.content.Context
import com.uow.scan.BuildConfig
import com.uow.scan.util.PreferencesManager
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ScanAiClient {

    @Volatile
    private var retrofit: Retrofit? = null

    @Volatile
    private var currentBaseUrl: String? = null

    private fun buildRetrofit(baseUrl: String, token: String): Retrofit {
        val authInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
            chain.proceed(request)
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val pinner = CertificatePinner.Builder()
            .add("scan-ai.local", BuildConfig.SCAN_AI_CERT_PIN)
            .add("scan-ai", BuildConfig.SCAN_AI_CERT_PIN)
            .add("192.168.0.152", BuildConfig.SCAN_AI_CERT_PIN)
            .build()

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
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
        val token = PreferencesManager.getSmsServerToken(context)

        if (url.isBlank() || token.isBlank()) {
            throw IllegalStateException("SMS AI server not configured")
        }

        val normalizedUrl = if (url.endsWith("/")) url else "$url/"

        if (retrofit == null || currentBaseUrl != normalizedUrl) {
            synchronized(this) {
                if (retrofit == null || currentBaseUrl != normalizedUrl) {
                    retrofit = buildRetrofit(normalizedUrl, token)
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

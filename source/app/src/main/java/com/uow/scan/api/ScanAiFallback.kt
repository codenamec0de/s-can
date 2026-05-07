package com.uow.scan.api

import android.content.Context
import com.google.gson.Gson
import com.uow.scan.api.ScanAiApiService.ClassifyResponse

/**
 * On-device cached classifier used when the AI sidecar is unreachable or the user has
 * flipped the demo-mode toggle on the AI Server screen. Bypasses the network entirely.
 *
 * Algorithm (see assets/scam_fallback.json):
 *   1. Lowercase the SMS body.
 *   2. Substring-match against `examples` first (hand-picked demo messages).
 *   3. Sweep `scam_keywords`, then `safe_keywords`.
 *   4. Fall back to `default_unverifiable` (SUSPICIOUS, conf 0.5).
 *
 * The returned [ClassifyResponse] carries `model = "cached-fallback"` so verdict
 * history can distinguish it from server responses.
 */
object ScanAiFallback {

    private const val ASSET_NAME = "scam_fallback.json"
    private const val MODEL_TAG = "cached-fallback"

    private data class Example(
        val match: String,
        val verdict: String,
        val reasoning: String,
        val confidence: Double
    )

    private data class Default(
        val verdict: String,
        val reasoning: String,
        val confidence: Double
    )

    private data class Catalog(
        val examples: List<Example> = emptyList(),
        val scam_keywords: List<String> = emptyList(),
        val safe_keywords: List<String> = emptyList(),
        val default_unverifiable: Default
    )

    @Volatile
    private var cached: Catalog? = null

    private fun catalog(context: Context): Catalog {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val json = context.applicationContext.assets.open(ASSET_NAME)
                .bufferedReader()
                .use { it.readText() }
            val parsed = Gson().fromJson(json, Catalog::class.java)
            cached = parsed
            return parsed
        }
    }

    fun classify(context: Context, sms: String): ClassifyResponse {
        val haystack = sms.lowercase()
        val cat = catalog(context)

        cat.examples.firstOrNull { haystack.contains(it.match.lowercase()) }?.let { ex ->
            return ClassifyResponse(
                verdict = ex.verdict,
                reasoning = ex.reasoning,
                confidence = ex.confidence,
                model = MODEL_TAG
            )
        }

        cat.scam_keywords.firstOrNull { haystack.contains(it.lowercase()) }?.let { kw ->
            return ClassifyResponse(
                verdict = "SCAM",
                reasoning = "Cached classifier matched scam keyword: \"$kw\".",
                confidence = 0.75,
                model = MODEL_TAG
            )
        }

        cat.safe_keywords.firstOrNull { haystack.contains(it.lowercase()) }?.let { kw ->
            return ClassifyResponse(
                verdict = "SAFE",
                reasoning = "Cached classifier matched benign keyword: \"$kw\".",
                confidence = 0.75,
                model = MODEL_TAG
            )
        }

        return ClassifyResponse(
            verdict = cat.default_unverifiable.verdict,
            reasoning = cat.default_unverifiable.reasoning,
            confidence = cat.default_unverifiable.confidence,
            model = MODEL_TAG
        )
    }
}

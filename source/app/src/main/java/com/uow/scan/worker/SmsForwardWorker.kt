package com.uow.scan.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.uow.scan.R
import com.uow.scan.SmsScamActivity
import com.uow.scan.api.ScanAiApiService
import com.uow.scan.api.ScanAiClient
import com.uow.scan.data.ScanDatabase
import com.uow.scan.data.entity.SmsVerdictEntity
import org.json.JSONArray
import org.json.JSONObject

class SmsForwardWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_SENDER = "sender"
        const val KEY_BODY = "body"
        const val KEY_TIMESTAMP = "timestamp"
        private const val TAG = "SmsForwardWorker"
        const val CHANNEL_ID = "scan_sms_scam_channel"
    }

    override suspend fun doWork(): Result {
        val sender = inputData.getString(KEY_SENDER) ?: return Result.failure()
        val body = inputData.getString(KEY_BODY) ?: return Result.failure()
        val timestamp = inputData.getLong(KEY_TIMESTAMP, System.currentTimeMillis())

        return try {
            val api = ScanAiClient.getApi(applicationContext)
            val response = api.classify(
                ScanAiApiService.ClassifyRequest(text = body, sender = sender)
            )

            if (!response.isSuccessful) {
                Log.w(TAG, "AI server returned ${response.code()}")
                return Result.retry()
            }

            val result = response.body() ?: return Result.retry()

            // Run URL checks for any URLs extracted by the classifier
            val urlSignalsJson = checkUrls(api, result.urls)

            val entity = SmsVerdictEntity(
                sender = sender,
                messageBody = body,
                verdict = result.verdict,
                confidence = result.confidence,
                explanation = result.explanation,
                timestamp = timestamp,
                urlSignals = urlSignalsJson
            )
            val dao = ScanDatabase.getInstance(applicationContext).smsVerdictDao()
            val id = dao.insert(entity)

            // Trim old entries
            val count = dao.getCount()
            if (count > 100) {
                dao.deleteOldest(count - 100)
            }

            // Notify for SCAM and SUSPICIOUS verdicts
            if (result.verdict != "SAFE") {
                sendNotification(sender, result.verdict, result.explanation, id)
            }

            Result.success()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "SMS server not configured: ${e.message}")
            Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to classify SMS", e)
            Result.retry()
        }
    }

    private suspend fun checkUrls(
        api: ScanAiApiService,
        urls: List<String>
    ): String? {
        if (urls.isEmpty()) return null

        val results = JSONArray()
        for (url in urls) {
            try {
                val resp = api.urlCheck(
                    ScanAiApiService.UrlCheckRequest(url = url, deep = true)
                )
                if (resp.isSuccessful) {
                    val body = resp.body() ?: continue
                    val obj = JSONObject().apply {
                        put("url", body.url)
                        put("verdict", body.verdict)
                        put("brand_match", body.brand_match ?: "")
                        put("risk_score", body.risk_score)
                        val signalsArr = JSONArray()
                        for (s in body.signals) {
                            signalsArr.put(JSONObject().apply {
                                put("type", s.type)
                                put("value", s.value)
                                put("weight", s.weight)
                            })
                        }
                        put("signals", signalsArr)
                    }
                    results.put(obj)
                }
            } catch (e: Exception) {
                Log.w(TAG, "URL check failed for $url: ${e.message}")
            }
        }

        return if (results.length() > 0) results.toString() else null
    }

    private fun sendNotification(
        sender: String,
        verdict: String,
        explanation: String,
        verdictId: Long
    ) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "SMS Scam Detection",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when suspicious or scam SMS messages are detected"
        }
        nm.createNotificationChannel(channel)

        val title = if (verdict == "SCAM") "Scam SMS Detected!" else "Suspicious SMS"
        val body = "From $sender: $explanation"

        val intent = Intent(applicationContext, SmsScamActivity::class.java).apply {
            putExtra(SmsScamActivity.EXTRA_VERDICT_ID, verdictId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            verdictId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(verdictId.toInt(), notification)
    }
}

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
import com.uow.scan.api.ScanAiFallback
import com.uow.scan.data.ScanDatabase
import com.uow.scan.data.entity.SmsVerdictEntity
import com.uow.scan.util.PreferencesManager

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
            val result = classifyWithFailover(body)

            // Server returns "scam"/"safe" lowercase; cached fallback returns uppercase already.
            // Uppercase at the boundary so the rest of the app sees a single shape.
            val verdict = result.verdict.uppercase()

            val entity = SmsVerdictEntity(
                sender = sender,
                messageBody = body,
                verdict = verdict,
                confidence = result.confidence,
                explanation = result.reasoning,
                timestamp = timestamp,
                urlSignals = null
            )
            val dao = ScanDatabase.getInstance(applicationContext).smsVerdictDao()
            val id = dao.insert(entity)

            val count = dao.getCount()
            if (count > 100) {
                dao.deleteOldest(count - 100)
            }

            if (verdict != "SAFE") {
                sendNotification(sender, verdict, result.reasoning, id)
            }

            Result.success()
        } catch (e: Exception) {
            // classifyWithFailover() already swallows network/server errors and returns an
            // on-device verdict, so reaching here means something unexpected (e.g. DB write).
            // Fail terminally rather than retrying forever — a stuck retry loop is worse than
            // a single missed verdict, and never blocks the demo.
            Log.e(TAG, "Failed to classify/store SMS", e)
            Result.failure()
        }
    }

    /**
     * Classify [body] and ALWAYS return a verdict. Uses the on-device fallback when it is
     * enabled (the default) or whenever the remote sidecar is unconfigured, errors, or times
     * out. This guarantees SMS scam detection never silently dies because a server is down.
     */
    private suspend fun classifyWithFailover(body: String): ScanAiApiService.ClassifyResponse {
        if (PreferencesManager.isSmsFallbackEnabled(applicationContext)) {
            return ScanAiFallback.classify(applicationContext, body)
        }
        return try {
            val api = ScanAiClient.getApi(applicationContext)
            val response = api.classify(ScanAiApiService.ClassifyRequest(sms = body))
            val parsed = if (response.isSuccessful) response.body() else null
            if (parsed != null) {
                parsed
            } else {
                Log.w(TAG, "AI server unavailable (code=${response.code()}); using on-device classifier")
                ScanAiFallback.classify(applicationContext, body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI server call failed (${e.message}); using on-device classifier")
            ScanAiFallback.classify(applicationContext, body)
        }
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

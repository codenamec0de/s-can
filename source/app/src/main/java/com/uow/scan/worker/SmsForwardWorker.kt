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
            val result = if (PreferencesManager.isSmsFallbackEnabled(applicationContext)) {
                ScanAiFallback.classify(applicationContext, body)
            } else {
                val api = ScanAiClient.getApi(applicationContext)
                val response = api.classify(ScanAiApiService.ClassifyRequest(sms = body))
                if (!response.isSuccessful) {
                    Log.w(TAG, "AI server returned ${response.code()}")
                    return Result.retry()
                }
                response.body() ?: return Result.retry()
            }

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
        } catch (e: IllegalStateException) {
            Log.w(TAG, "SMS server not configured: ${e.message}")
            Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to classify SMS", e)
            Result.retry()
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

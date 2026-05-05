package com.uow.scan.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.uow.scan.util.PreferencesManager
import com.uow.scan.worker.SmsForwardWorker

class SmsBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!PreferencesManager.isSmsScamDetectionEnabled(context)) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group multi-part SMS by sender
        val grouped = mutableMapOf<String, StringBuilder>()
        for (msg in messages) {
            val sender = msg.displayOriginatingAddress ?: "Unknown"
            grouped.getOrPut(sender) { StringBuilder() }
                .append(msg.displayMessageBody ?: "")
        }

        for ((sender, body) in grouped) {
            val text = body.toString().trim()
            if (text.isEmpty()) continue

            val data = Data.Builder()
                .putString(SmsForwardWorker.KEY_SENDER, sender)
                .putString(SmsForwardWorker.KEY_BODY, text)
                .putLong(SmsForwardWorker.KEY_TIMESTAMP, System.currentTimeMillis())
                .build()

            val request = OneTimeWorkRequestBuilder<SmsForwardWorker>()
                .setInputData(data)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}

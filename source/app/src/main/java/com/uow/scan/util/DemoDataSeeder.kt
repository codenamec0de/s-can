package com.uow.scan.util

import android.content.Context
import com.uow.scan.data.ScanDatabase
import com.uow.scan.data.entity.AlertEntity
import com.uow.scan.data.entity.SmsVerdictEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Seeds realistic, presentation-friendly sample data so the headline screens (Home "Needs
 * attention", Audit, SMS history, device-security score) are never empty on a clean install
 * during a live demo. Triggered by a hidden long-press on the Home score ring — invisible to
 * an audience, instant for the presenter.
 *
 * All rows use REPLACE-on-conflict so repeated seeding is idempotent. Nothing here runs
 * automatically; it is purely an opt-in demo aid.
 */
object DemoDataSeeder {

    suspend fun seed(context: Context) = withContext(Dispatchers.IO) {
        val db = ScanDatabase.getInstance(context)
        val now = System.currentTimeMillis()
        val min = 60_000L
        val hr = 60 * min

        // --- Background-activity alerts (silent = 0ms duration) ---
        val alerts = listOf(
            AlertEntity(
                id = "demo-alert-1",
                packageName = "com.instagram.android", appName = "Instagram",
                permissions = "android.permission.CAMERA",
                dataUsedBytes = 2_300_000, backgroundDurationMs = 0, timestamp = now - 8 * min
            ),
            AlertEntity(
                id = "demo-alert-2",
                packageName = "com.facebook.katana", appName = "Facebook",
                permissions = "android.permission.ACCESS_FINE_LOCATION",
                dataUsedBytes = 5_100_000, backgroundDurationMs = 0, timestamp = now - 42 * min
            ),
            AlertEntity(
                id = "demo-alert-3",
                packageName = "com.zhiliaoapp.musically", appName = "TikTok",
                permissions = "android.permission.RECORD_AUDIO",
                dataUsedBytes = 12_400_000, backgroundDurationMs = 0, timestamp = now - 3 * hr
            ),
            AlertEntity(
                id = "demo-alert-4",
                packageName = "com.whatsapp", appName = "WhatsApp",
                permissions = "android.permission.READ_CONTACTS",
                dataUsedBytes = 900_000, backgroundDurationMs = 95 * min, timestamp = now - 26 * hr
            ),
        )
        db.alertDao().insertAll(alerts)

        // --- SMS verdicts ---
        val sms = listOf(
            SmsVerdictEntity(
                sender = "+61 400 123 456",
                messageBody = "AusPost: your parcel is held. Pay \$1.99 to release: auspost-au.info/track",
                verdict = "SCAM", confidence = 0.94,
                explanation = "Brand-typosquat link plus a small payment request — classic parcel-delivery phishing.",
                timestamp = now - 15 * min
            ),
            SmsVerdictEntity(
                sender = "+61 491 570 006",
                messageBody = "Hi mum, I dropped my phone — this is my new number. Can you transfer \$500 today?",
                verdict = "SCAM", confidence = 0.93,
                explanation = "Family-impersonation 'Hi Mum' scam from an unknown number requesting urgent money.",
                timestamp = now - 2 * hr
            ),
            SmsVerdictEntity(
                sender = "VERIFY",
                messageBody = "Your verification code is 882140. Do not share it with anyone.",
                verdict = "SAFE", confidence = 0.90,
                explanation = "Standard one-time-passcode wording; no link or payment request.",
                timestamp = now - 5 * hr
            ),
        )
        sms.forEach { db.smsVerdictDao().insert(it) }

        // --- A real device-security row so the score / PDF report have data ---
        runCatching { DeviceSecurityChecker.checkAndSave(context) }
    }
}

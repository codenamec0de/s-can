package com.uow.scan.model

data class SmsVerdict(
    val id: Long = 0,
    val sender: String,
    val messageBody: String,
    val verdict: String,
    val confidence: Double,
    val explanation: String,
    val timestamp: Long,
    val isRead: Boolean = false
) {
    val verdictLabel: String
        get() = when (verdict) {
            "SCAM" -> "Scam Detected"
            "SUSPICIOUS" -> "Suspicious"
            else -> "Safe"
        }

    val confidencePercent: String
        get() = "${(confidence * 100).toInt()}%"
}

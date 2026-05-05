package com.uow.scan.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms_verdicts")
data class SmsVerdictEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sender: String,
    val messageBody: String,
    val verdict: String,
    val confidence: Double,
    val explanation: String,
    val timestamp: Long,
    val isRead: Boolean = false,
    val urlSignals: String? = null  // JSON string of URL check results
)

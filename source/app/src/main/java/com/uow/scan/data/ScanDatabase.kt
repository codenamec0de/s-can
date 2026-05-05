package com.uow.scan.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.uow.scan.data.dao.AlertDao
import com.uow.scan.data.dao.AppSettingsDao
import com.uow.scan.data.dao.BreachResultDao
import com.uow.scan.data.dao.DeviceCheckDao
import com.uow.scan.data.dao.MonitoredAppDao
import com.uow.scan.data.dao.ScanResultDao
import com.uow.scan.data.dao.SmsVerdictDao
import com.uow.scan.data.dao.WeeklyStatsDao
import com.uow.scan.data.entity.AlertEntity
import com.uow.scan.data.entity.AppSettingsEntity
import com.uow.scan.data.entity.BreachResultEntity
import com.uow.scan.data.entity.DeviceCheckEntity
import com.uow.scan.data.entity.MonitoredAppEntity
import com.uow.scan.data.entity.ScanResultEntity
import com.uow.scan.data.entity.SmsVerdictEntity
import com.uow.scan.data.entity.WeeklyStatsEntity

@Database(
    entities = [
        AlertEntity::class,
        MonitoredAppEntity::class,
        ScanResultEntity::class,
        DeviceCheckEntity::class,
        BreachResultEntity::class,
        AppSettingsEntity::class,
        SmsVerdictEntity::class,
        WeeklyStatsEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class ScanDatabase : RoomDatabase() {

    abstract fun alertDao(): AlertDao
    abstract fun monitoredAppDao(): MonitoredAppDao
    abstract fun scanResultDao(): ScanResultDao
    abstract fun deviceCheckDao(): DeviceCheckDao
    abstract fun breachResultDao(): BreachResultDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun smsVerdictDao(): SmsVerdictDao
    abstract fun weeklyStatsDao(): WeeklyStatsDao

    companion object {
        @Volatile
        private var INSTANCE: ScanDatabase? = null

        fun getInstance(context: Context): ScanDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ScanDatabase::class.java,
                    "scan_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

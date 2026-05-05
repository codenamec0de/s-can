package com.uow.scan.util

import android.content.Context
import com.uow.scan.model.TrackerInfo

/**
 * Surfaces trackers detected for an installed app. Backed by [LocalTrackerScanner],
 * which scans DEX class names against the bundled Exodus tracker database.
 */
object TrackerRepository {

    suspend fun getTrackersForApp(context: Context, packageName: String): Result<List<TrackerInfo>> =
        runCatching { LocalTrackerScanner.scan(context, packageName) }
}

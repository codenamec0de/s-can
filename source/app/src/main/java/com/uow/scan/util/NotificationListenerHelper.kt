package com.uow.scan.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.uow.scan.service.PrivacyNotificationListener

/**
 * Utilities around the Notification Access permission. We use it to subscribe
 * to system-posted privacy notifications ("Camera is being used by X") via
 * [PrivacyNotificationListener].
 */
object NotificationListenerHelper {

    /** True if the user has granted us Notification Access. */
    fun isGranted(context: Context): Boolean {
        return context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }

    /**
     * Open the system Notification Listener Settings screen. On Android 11+
     * we deep-link straight to our component using the host:fragment extras
     * pattern; on older versions we fall back to the listing.
     */
    fun openSettings(context: Context) {
        val component = ComponentName(context, PrivacyNotificationListener::class.java)
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", component.flattenToString())
            val args = android.os.Bundle().apply {
                putString(":settings:fragment_args_key", component.flattenToString())
            }
            putExtra(":settings:show_fragment_args", args)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            // Some OEMs don't accept the deep-link extras — fall back.
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

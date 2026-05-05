package com.uow.scan.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.uow.scan.R

/**
 * Terminator engine - handles permission revocation when a watched app
 * moves to background.
 *
 * Mode "manual": sends a notification with a tap action that opens
 *   the app's permission settings so the user can revoke with one tap.
 *
 * Mode "auto": attempts to revoke via Shizuku. Falls back to manual
 *   notification if Shizuku is unavailable.
 */
object TerminatorEngine {

    private const val CHANNEL_ID = "terminator_channel"
    private const val NOTIFICATION_BASE_ID = 5000

    /**
     * Called by the monitoring service when a watched app moves to background.
     * @param packageName the package that just went to background
     */
    fun onAppBackgrounded(context: Context, packageName: String) {
        if (!TerminatorManager.isEnabled(context)) return
        if (!TerminatorManager.isAppWatched(context, packageName)) return

        val mode = TerminatorManager.getMode(context)
        val appName = getAppName(context, packageName)
        val grantedRevocable = getGrantedRevocablePermissions(context, packageName)

        if (grantedRevocable.isEmpty()) {
            FileLogger.d(context, "Terminator: $appName has no granted revocable perms, skipping")
            return
        }

        FileLogger.d(context, "Terminator: $appName backgrounded, mode=$mode, perms=$grantedRevocable")

        if (mode == "auto") {
            val success = tryShizukuRevoke(context, packageName, grantedRevocable)
            if (success) {
                sendRevokedNotification(context, appName, grantedRevocable)
                return
            }
            // Fall through to manual notification if Shizuku failed
            FileLogger.w(context, "Terminator: Shizuku revoke failed, falling back to manual")
        }

        sendManualNotification(context, packageName, appName, grantedRevocable)
    }

    /**
     * Get the list of revocable permissions that are currently granted to the app.
     */
    private fun getGrantedRevocablePermissions(context: Context, packageName: String): List<String> {
        val pm = context.packageManager
        val pkgInfo: PackageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            }
        } catch (e: Exception) {
            return emptyList()
        }

        val permissions = pkgInfo.requestedPermissions ?: return emptyList()
        val flags = pkgInfo.requestedPermissionsFlags ?: return emptyList()

        return permissions.filterIndexed { index, perm ->
            val isGranted = (flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            isGranted && perm in TerminatorManager.REVOCABLE_PERMISSIONS
        }
    }

    /**
     * Attempt to revoke permissions via Shizuku.
     * Returns true if all permissions were revoked successfully.
     */
    private fun tryShizukuRevoke(context: Context, packageName: String, permissions: List<String>): Boolean {
        // Shizuku SDK integration point.
        // Full implementation requires:
        //   implementation("dev.rikka.shizuku:api:13.1.5")
        //   implementation("dev.rikka.shizuku:provider:13.1.5")
        //
        // With Shizuku active, you would do:
        //   val ipm = IPackageManager.Stub.asInterface(
        //       ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package"))
        //   )
        //   ipm.revokeRuntimePermission(packageName, permission, userId)
        //
        // For now, return false to fall back to manual mode.
        // The Shizuku SDK dependency and full integration can be added when
        // the user confirms they want to include it in the build.
        return false
    }

    /**
     * Send a notification that the user can tap to go directly to the app's
     * permission settings page and revoke manually.
     */
    private fun sendManualNotification(
        context: Context,
        packageName: String,
        appName: String,
        permissions: List<String>
    ) {
        ensureChannel(context)

        val permLabels = permissions.mapNotNull { TerminatorManager.PERMISSION_LABELS[it] }

        // Deep-link to the app's permission settings
        val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            packageName.hashCode(),
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminator)
            .setContentTitle("Revoke: $appName")
            .setContentText("Tap to revoke ${permLabels.joinToString(", ")}")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setStyle(Notification.BigTextStyle().bigText(
                "$appName went to background with active permissions: ${permLabels.joinToString(", ")}.\nTap to open settings and revoke."
            ))
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_BASE_ID + packageName.hashCode().and(0xFFF), notification)
    }

    /**
     * Send a confirmation notification after Shizuku auto-revoke succeeded.
     */
    private fun sendRevokedNotification(
        context: Context,
        appName: String,
        permissions: List<String>
    ) {
        ensureChannel(context)

        val permLabels = permissions.mapNotNull { TerminatorManager.PERMISSION_LABELS[it] }

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminator)
            .setContentTitle("Terminated: $appName")
            .setContentText("Revoked ${permLabels.joinToString(", ")}")
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_BASE_ID + appName.hashCode().and(0xFFF), notification)
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Terminator Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when watched apps need permission revocation"
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun getAppName(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}

package com.uow.scan.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Surfaces the *why* behind a background-data alert: which foreground services
 * the app has declared, whether it can wake on boot, whether it holds
 * background-only location, whether it can receive FCM push, etc.
 *
 * All signals come from the app's static manifest plus PackageManager queries
 * — no privileged AppOps required. They establish that the *capability* exists,
 * not that the app exercised it during the specific alert window. Even so, this
 * is the most accurate "why" answer a non-privileged app can produce: an alert
 * for "Gmail sent 2 MB in the background" makes sense once the user can see
 * "Receives push (FCM) · Runs dataSync foreground service · Syncs accounts".
 */
object BackgroundReasonInspector {

    data class Reasons(
        /** Foreground service types declared on the app's <service> tags
         *  (e.g. "dataSync", "location", "mediaPlayback"). */
        val foregroundServiceTypes: List<String>,
        /** True if the app declares a sync adapter (auto-syncs an account). */
        val hasSyncAdapter: Boolean,
        /** True if the app can wake on device boot. */
        val autoStartOnBoot: Boolean,
        /** True if the app is registered for Firebase Cloud Messaging push. */
        val pushMessaging: Boolean,
        /** True if the app holds ACCESS_BACKGROUND_LOCATION. */
        val backgroundLocation: Boolean,
        /** True if the app declares a JobService (uses JobScheduler/WorkManager). */
        val schedulesJobs: Boolean,
    ) {
        fun isEmpty(): Boolean =
            foregroundServiceTypes.isEmpty() && !hasSyncAdapter && !autoStartOnBoot &&
                !pushMessaging && !backgroundLocation && !schedulesJobs
    }

    private val cache = HashMap<String, Reasons>()

    fun inspect(context: Context, packageName: String): Reasons {
        cache[packageName]?.let { return it }
        val pm = context.packageManager

        val pkgInfo: PackageInfo = try {
            val flags = (PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS).toLong()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, flags.toInt())
            }
        } catch (_: Exception) {
            val empty = Reasons(emptyList(), false, false, false, false, false)
            cache[packageName] = empty
            return empty
        }

        val services = pkgInfo.services ?: emptyArray()

        val fgTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            services.flatMap { fgServiceTypeNames(it.foregroundServiceType) }
                .distinct()
        } else emptyList()

        val schedulesJobs = services.any { svc ->
            svc.permission == "android.permission.BIND_JOB_SERVICE"
        }

        val syncAdapter = try {
            pm.queryIntentServices(
                Intent("android.content.SyncAdapter").setPackage(packageName), 0
            ).isNotEmpty()
        } catch (_: Exception) {
            false
        }

        val perms = pkgInfo.requestedPermissions?.toList() ?: emptyList()
        val flags = pkgInfo.requestedPermissionsFlags ?: IntArray(perms.size)
        fun granted(p: String): Boolean {
            val i = perms.indexOf(p)
            return i >= 0 && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
        }

        val autoStart = granted("android.permission.RECEIVE_BOOT_COMPLETED")
        val bgLocation = granted("android.permission.ACCESS_BACKGROUND_LOCATION")

        val push = perms.any {
            it == "com.google.android.c2dm.permission.RECEIVE" ||
                it == "com.google.firebase.MESSAGING_EVENT"
        } || try {
            pm.queryIntentServices(
                Intent("com.google.firebase.MESSAGING_EVENT").setPackage(packageName), 0
            ).isNotEmpty()
        } catch (_: Exception) {
            false
        }

        val r = Reasons(
            foregroundServiceTypes = fgTypes,
            hasSyncAdapter = syncAdapter,
            autoStartOnBoot = autoStart,
            pushMessaging = push,
            backgroundLocation = bgLocation,
            schedulesJobs = schedulesJobs,
        )
        cache[packageName] = r
        return r
    }

    /**
     * Single-line, user-facing summary suitable for an alert row. Returns null
     * when the app has no notable background mechanisms to report (very rare —
     * almost every app at least registers for FCM or boot completion).
     */
    fun summary(reasons: Reasons): String? {
        if (reasons.isEmpty()) return null
        val parts = mutableListOf<String>()
        if (reasons.foregroundServiceTypes.isNotEmpty()) {
            parts += "Foreground service: " + reasons.foregroundServiceTypes.joinToString(", ")
        }
        if (reasons.pushMessaging) parts += "Receives push (FCM)"
        if (reasons.hasSyncAdapter) parts += "Syncs accounts"
        if (reasons.schedulesJobs) parts += "Schedules background jobs"
        if (reasons.backgroundLocation) parts += "Background location granted"
        if (reasons.autoStartOnBoot) parts += "Auto-starts on boot"
        return parts.joinToString(" · ")
    }

    /**
     * Decodes the [ServiceInfo.foregroundServiceType] bitmask into human-readable
     * type names. Constants are gated on SDK level — types added in later API
     * levels (HEALTH, REMOTE_MESSAGING, SHORT_SERVICE, FILE_MANAGEMENT,
     * SPECIAL_USE, SYSTEM_EXEMPTED in API 34) are only checked when the device
     * actually runs that API to avoid VerifyError.
     */
    private fun fgServiceTypeNames(bitmask: Int): List<String> {
        if (bitmask == 0) return emptyList()
        val out = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (bitmask and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0) out += "dataSync"
            if (bitmask and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) out += "location"
            if (bitmask and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0) out += "mediaPlayback"
            if (bitmask and ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL != 0) out += "phoneCall"
            if (bitmask and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0) out += "screenCapture"
            if (bitmask and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0) out += "connectedDevice"
            if (bitmask and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0) out += "camera"
            if (bitmask and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) out += "microphone"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (bitmask and ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH != 0) out += "health"
            if (bitmask and ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING != 0) out += "remoteMessaging"
            if (bitmask and ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE != 0) out += "shortService"
            if (bitmask and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0) out += "specialUse"
            if (bitmask and ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED != 0) out += "systemExempt"
        }
        return out
    }
}

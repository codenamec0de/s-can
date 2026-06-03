package com.uow.scan.service

import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.uow.scan.data.ScanDatabase
import com.uow.scan.data.entity.PermissionAccessEntity
import com.uow.scan.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Captures system-posted "Camera/Microphone/Location is in use by X" notifications
 * and turns them into [PermissionAccessEntity] rows.
 *
 * Why: on Samsung One UI 5+ and AOSP Android 12+, the OS posts a notification
 * (via the permission controller / privacy controller package) every time an
 * app uses a sensitive sensor. These are OS-signed signals that survive the
 * `CameraManager.AvailabilityCallback` delivery gaps we hit on Samsung when
 * our process is in the background. By listening to them we get a second,
 * independent confirmation channel — enough to honestly tell the user
 * "verified" instead of "could access".
 *
 * The user must grant Notification Access for this service to receive
 * callbacks. [NotificationListenerHelper.isGranted] reports state and
 * [NotificationListenerHelper.openSettings] launches the system grant flow.
 */
class PrivacyNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Open events keyed by the StatusBarNotification key, so we can close
     * the matching DB row when the notification is removed (mirroring the
     * lifecycle Samsung uses: notification posted on access start, removed
     * on access end).
     */
    private val openByKey = ConcurrentHashMap<String, Long>()

    /**
     * Lowercased app label → package name, computed lazily and cached. The
     * privacy notifications carry the *display name* of the offending app
     * ("Instagram is using your camera"), not the package, so we have to
     * reverse it. PackageManager queries are kept off the listener thread.
     */
    @Volatile private var labelIndex: Map<String, String> = emptyMap()

    override fun onListenerConnected() {
        super.onListenerConnected()
        FileLogger.d(this, "PrivacyNotificationListener connected")
        scope.launch { rebuildLabelIndex() }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        FileLogger.d(this, "PrivacyNotificationListener disconnected")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName !in PRIVACY_CONTROLLER_PACKAGES) return
        val parsed = parse(n) ?: return
        val (op, accusedPkg) = parsed
        FileLogger.d(this, "PrivacyNotif posted: $op by $accusedPkg (key=${n.key})")
        val now = System.currentTimeMillis()
        scope.launch {
            try {
                val id = ScanDatabase.getInstance(this@PrivacyNotificationListener)
                    .permissionAccessDao()
                    .insert(
                        PermissionAccessEntity(
                            packageName = accusedPkg,
                            op = op,
                            startedAt = now,
                            endedAt = null,
                            // Privacy notifications fire regardless of fg/bg
                            // state of the accused app; treat as foreground
                            // unknown — caller can decide whether to alert.
                            foregroundAtStart = true
                        )
                    )
                openByKey[n.key] = id
            } catch (e: Exception) {
                FileLogger.e(this@PrivacyNotificationListener, "PrivacyNotif insert failed", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        val rowId = openByKey.remove(key) ?: return
        val now = System.currentTimeMillis()
        scope.launch {
            try {
                ScanDatabase.getInstance(this@PrivacyNotificationListener)
                    .permissionAccessDao()
                    .markEnded(rowId, now)
                FileLogger.d(this@PrivacyNotificationListener, "PrivacyNotif ended: id=$rowId")
            } catch (e: Exception) {
                FileLogger.e(this@PrivacyNotificationListener, "PrivacyNotif markEnded failed", e)
            }
        }
    }

    /**
     * Best-effort parse of the notification's title/text/bigText to extract
     * (op, packageName). Returns null if we can't determine both.
     *
     * Patterns observed across Samsung One UI 5/6/7 and AOSP 12–14:
     *   • "Camera is being used by Instagram"
     *   • "Camera in use · Instagram"
     *   • "Microphone access · Instagram"
     *   • Channel id often includes "camera"/"microphone"/"location"
     */
    private fun parse(sbn: StatusBarNotification): Pair<String, String>? {
        val extras = sbn.notification.extras ?: return null
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            sbn.notification.channelId.orEmpty()
        } else ""
        val haystack = (title + " " + text + " " + bigText + " " + channel).lowercase()

        val op = when {
            "camera" in haystack -> "CAMERA"
            "microphone" in haystack || "mic " in haystack || haystack.endsWith("mic") -> "MICROPHONE"
            "location" in haystack -> "LOCATION"
            else -> return null
        }

        // Find the offending app name. The notification typically embeds
        // the app's display name verbatim. We match against a precomputed
        // index of installed-app labels.
        val pkg = findPackageInText(title) ?: findPackageInText(text) ?: findPackageInText(bigText)
            ?: return null

        return op to pkg
    }

    private fun findPackageInText(text: String): String? {
        if (text.isBlank()) return null
        val labels = labelIndex
        if (labels.isEmpty()) return null
        val lower = text.lowercase()
        // Match longest-first to avoid "Google" stealing matches from
        // "Google Play Store", etc.
        return labels.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (label, _) -> label.length >= 3 && label in lower }
            ?.value
    }

    private fun rebuildLabelIndex() {
        val pm = packageManager
        val packages = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            FileLogger.e(this, "Failed to enumerate installed apps", e)
            return
        }
        val map = HashMap<String, String>(packages.size)
        for (info in packages) {
            val label = try {
                pm.getApplicationLabel(info).toString().trim()
            } catch (_: Exception) {
                continue
            }
            if (label.isNotEmpty()) map[label.lowercase()] = info.packageName
        }
        labelIndex = map
        FileLogger.d(this, "PrivacyNotif label index built: ${map.size} apps")
    }

    companion object {
        /**
         * Packages that legitimately post privacy/permission notifications.
         * Anything outside this set is ignored — we don't want app
         * notifications mentioning the word "camera" tricking us.
         */
        private val PRIVACY_CONTROLLER_PACKAGES = setOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.samsung.android.permissioncontroller",
            "com.samsung.android.privacydashboard",
            "com.android.systemui",            // Pixel + AOSP privacy chip persistent notif
            "com.samsung.android.app.smartcapture", // some One UI variants
        )
    }
}

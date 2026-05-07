package com.uow.scan.util

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.uow.scan.data.ScanDatabase
import com.uow.scan.data.entity.PermissionAccessEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Tracks real-time camera and microphone access by *other* apps via Android's
 * public availability callbacks, and records each access (with timestamps) in
 * the `permission_access_events` Room table.
 *
 * Why these two ops only:
 *  - [CameraManager.AvailabilityCallback] fires system-wide when any process
 *    opens or closes a camera. No special permission required.
 *  - [AudioManager.AudioRecordingCallback] fires system-wide on AudioRecord
 *    start/stop. No special permission required.
 *  - There is no equivalent public callback for location, contacts, SMS, etc.
 *    `AppOpsManager.startWatchingActive` requires the signature-only
 *    `WATCH_APPOPS` permission and is unavailable to third-party apps.
 *
 * Attribution heuristic: when a transition fires, we ask UsageStatsManager
 * which app is in the foreground at that moment. Android disallows non-
 * privileged background apps from opening the camera or microphone except
 * via foreground services with the matching foregroundServiceType, so the
 * foreground app is the camera/mic user in the overwhelming majority of
 * cases. Edge cases (camera-type foreground services, OEM face unlock)
 * may misattribute; the [PermissionAccessEntity.foregroundAtStart] field
 * records the foreground state at the moment of capture so downstream
 * filters can decide whether to treat the event as a *background* access.
 */
object OpAccessTracker {

    private const val OP_CAMERA = "CAMERA"
    private const val OP_MIC = "MICROPHONE"

    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Tracks open op rows so we can close them on the matching "end" event.
    // Keyed by "$packageName|$op".
    private val openRowIds = ConcurrentHashMap<String, Long>()

    // Camera availability callback fires once per camera at registration time
    // ("here is the current state of camera 0/1/..."), and we must not treat
    // that as a transition. We arm the listener after the first fire per id.
    private val cameraSeen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Audio callback delivers the *full* set of active recordings on each
    // change. We compare to previous size to detect transitions.
    private var lastAudioActiveCount = -1

    private var cameraCb: CameraManager.AvailabilityCallback? = null
    private var audioCb: AudioManager.AudioRecordingCallback? = null
    private var registeredContext: Context? = null

    private val SELF_PACKAGE_BLOCKLIST = setOf(
        // System / OEM camera apps that almost always are the foreground app
        // when they open the camera, and don't represent privacy risk worth
        // alerting on.
        "com.android.camera", "com.android.camera2",
        "com.sec.android.app.camera",
        "com.google.android.apps.camera.services",
    )

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        registeredContext = appCtx

        FileLogger.d(appCtx, "OpAccessTracker.start()")

        registerCameraCallback(appCtx)
        registerAudioCallback(appCtx)

        // Prune stale events on a fresh start
        scope.launch {
            try {
                val keepFromMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
                ScanDatabase.getInstance(appCtx).permissionAccessDao().pruneOlderThan(keepFromMs)
            } catch (e: Exception) {
                FileLogger.e(appCtx, "OpAccessTracker.pruneOlderThan failed", e)
            }
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        val ctx = registeredContext ?: return
        try {
            cameraCb?.let {
                (ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager)
                    ?.unregisterAvailabilityCallback(it)
            }
        } catch (_: Exception) { /* ignore */ }
        try {
            audioCb?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                        ?.unregisterAudioRecordingCallback(it)
                }
            }
        } catch (_: Exception) { /* ignore */ }
        cameraCb = null
        audioCb = null
        registeredContext = null
        cameraSeen.clear()
        openRowIds.clear()
        lastAudioActiveCount = -1
        FileLogger.d(ctx, "OpAccessTracker.stop()")
    }

    // ─── Camera ──────────────────────────────────────────────────────────────

    private fun registerCameraCallback(ctx: Context) {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val cb = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) {
                if (cameraSeen.add(cameraId)) {
                    // First fire for this id at registration is *current state*,
                    // not a fresh transition — but if the camera is already busy
                    // when we register, that IS a real ongoing access. So
                    // record it but mark the fact we synthesised the start.
                    handleOpStart(ctx, OP_CAMERA, viaInitialFire = true)
                    return
                }
                handleOpStart(ctx, OP_CAMERA, viaInitialFire = false)
            }

            override fun onCameraAvailable(cameraId: String) {
                if (cameraSeen.add(cameraId)) return  // initial state, no-op
                handleOpEnd(ctx, OP_CAMERA)
            }
        }
        cameraCb = cb
        cm.registerAvailabilityCallback(cb, mainHandler)
    }

    // ─── Microphone ──────────────────────────────────────────────────────────

    private fun registerAudioCallback(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val now = configs.size
                val prev = lastAudioActiveCount
                lastAudioActiveCount = now
                if (prev < 0) return  // first delivery = initial state, not a transition
                when {
                    now > prev -> handleOpStart(ctx, OP_MIC, viaInitialFire = false)
                    now < prev -> handleOpEnd(ctx, OP_MIC)
                }
            }
        }
        audioCb = cb
        am.registerAudioRecordingCallback(cb, mainHandler)
    }

    // ─── Event handling ──────────────────────────────────────────────────────

    private fun handleOpStart(ctx: Context, op: String, viaInitialFire: Boolean) {
        val now = System.currentTimeMillis()
        val pkg = currentForegroundPackage(ctx) ?: return
        FileLogger.d(ctx, "OpAccessTracker: $op start fired, fg=$pkg viaInitial=$viaInitialFire")
        if (pkg == ctx.packageName) return
        if (pkg in SELF_PACKAGE_BLOCKLIST) {
            FileLogger.d(ctx, "OpAccessTracker: $pkg is in blocklist, skipping")
            return
        }

        val key = "$pkg|$op"
        if (openRowIds.containsKey(key)) return  // already tracking this open
        val foreground = !viaInitialFire  // initial fire = unknown, treat as background
        scope.launch {
            try {
                val id = ScanDatabase.getInstance(ctx).permissionAccessDao().insert(
                    PermissionAccessEntity(
                        packageName = pkg,
                        op = op,
                        startedAt = now,
                        endedAt = null,
                        foregroundAtStart = foreground
                    )
                )
                openRowIds[key] = id
                FileLogger.d(ctx, "OpAccessTracker: $pkg started $op (fg=$foreground, id=$id)")
            } catch (e: Exception) {
                FileLogger.e(ctx, "OpAccessTracker.insert failed", e)
            }
        }
    }

    private fun handleOpEnd(ctx: Context, op: String) {
        val now = System.currentTimeMillis()
        FileLogger.d(ctx, "OpAccessTracker: $op end fired")
        // We don't know which package owns this end transition (the camera
        // simply became available again). Close every open row for [op] —
        // multiple cameras can be open simultaneously by the same app, and
        // when they all release the system reports "available" once each.
        val keysToClose = openRowIds.keys().toList().filter { it.endsWith("|$op") }
        for (key in keysToClose) {
            val id = openRowIds.remove(key) ?: continue
            scope.launch {
                try {
                    ScanDatabase.getInstance(ctx).permissionAccessDao().markEnded(id, now)
                    FileLogger.d(ctx, "OpAccessTracker: closed $key (id=$id)")
                } catch (e: Exception) {
                    FileLogger.e(ctx, "OpAccessTracker.markEnded failed", e)
                }
            }
        }
    }

    // ─── Foreground attribution ──────────────────────────────────────────────

    /**
     * Most recent ACTIVITY_RESUMED package within the last 60 seconds. This
     * is the standard non-privileged way to identify the foreground app.
     */
    private fun currentForegroundPackage(ctx: Context): String? {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        return try {
            val end = System.currentTimeMillis()
            val start = end - 60_000L
            val events = usm.queryEvents(start, end)
            val event = UsageEvents.Event()
            var lastResumedPkg: String? = null
            var lastResumedTs = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val type = event.eventType
                if (type == UsageEvents.Event.ACTIVITY_RESUMED ||
                    type == UsageEvents.Event.MOVE_TO_FOREGROUND
                ) {
                    if (event.timeStamp >= lastResumedTs) {
                        lastResumedTs = event.timeStamp
                        lastResumedPkg = event.packageName
                    }
                }
            }
            lastResumedPkg
        } catch (e: Exception) {
            FileLogger.e(ctx, "OpAccessTracker.currentForegroundPackage failed", e)
            null
        }
    }
}

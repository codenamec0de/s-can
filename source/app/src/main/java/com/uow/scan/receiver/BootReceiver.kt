package com.uow.scan.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.uow.scan.service.ScanMonitorService

/**
 * Restarts the persistent monitoring service after device reboot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            ScanMonitorService.start(context)
        }
    }
}

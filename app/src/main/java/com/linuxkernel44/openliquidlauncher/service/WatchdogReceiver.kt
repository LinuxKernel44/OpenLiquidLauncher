package com.linuxkernel44.openliquidlauncher.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired periodically by the alarm scheduled in [KeepAliveService.scheduleWatchdog]: restarts
 * [KeepAliveService] if the whole process (the service along with it) was killed in the background.
 * Starting it while it is already running is a harmless no-op (just reposts the same notification).
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        KeepAliveService.start(context)
    }
}

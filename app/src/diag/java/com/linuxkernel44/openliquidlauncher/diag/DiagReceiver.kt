package com.linuxkernel44.openliquidlauncher.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The "Mark bug" button of the notification. */
class DiagReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Diag.ACTION_MARK) Diag.mark(context.applicationContext)
    }
}

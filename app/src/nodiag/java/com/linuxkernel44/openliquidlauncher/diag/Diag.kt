package com.linuxkernel44.openliquidlauncher.diag

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.core.app.NotificationCompat
import com.kyant.backdrop.Backdrop
import com.linuxkernel44.openliquidlauncher.ui.GlassStyle

/** No-op stand-in for the `diag` flavor's log capture (see src/diag): the full and play builds carry none of it. */
object Diag {
    const val enabled = false
    fun onServiceStart(context: Context) {}
    fun notificationActions(context: Context): List<NotificationCompat.Action> = emptyList()
    fun onResumed(activity: Activity) {}
}

@Composable
fun DiagSettingsSection(backdrop: Backdrop, style: GlassStyle) {}

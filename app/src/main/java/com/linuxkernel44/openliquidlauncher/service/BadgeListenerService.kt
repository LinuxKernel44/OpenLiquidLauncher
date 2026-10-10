package com.linuxkernel44.openliquidlauncher.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Counts active notifications per package so the launcher can draw badges. */
class BadgeListenerService : NotificationListenerService() {

    override fun onListenerConnected() = publish()
    override fun onNotificationPosted(sbn: StatusBarNotification) = publish()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = publish()

    override fun onListenerDisconnected() {
        _counts.value = emptyMap()
    }

    private fun publish() {
        val list = runCatching { activeNotifications }.getOrNull() ?: return
        _counts.value = list
            .filter { it.isClearable || !it.isOngoing }
            .filter { it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 }
            .groupingBy { it.packageName }.eachCount()
    }

    companion object {
        private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
        val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, BadgeListenerService::class.java)
            return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

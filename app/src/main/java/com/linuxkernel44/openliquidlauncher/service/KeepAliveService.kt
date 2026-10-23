package com.linuxkernel44.openliquidlauncher.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.linuxkernel44.openliquidlauncher.LauncherActivity
import com.linuxkernel44.openliquidlauncher.R
import com.linuxkernel44.openliquidlauncher.diag.Diag

/**
 * Minimal always-on foreground service. It does no work of its own: its only purpose is to keep the
 * launcher process alive, so Android is less likely to kill it in the background, which is what makes
 * Home sometimes need a second press (and a cold restart + icon decode) after using another app.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Diag.enabled) Diag.onServiceStart(this)
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(CHANNEL_ID, getString(R.string.keep_alive_channel_name), NotificationManager.IMPORTANCE_MIN)
                channel.setShowBadge(false)
                mgr.createNotificationChannel(channel)
            }
        }
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, LauncherActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.keep_alive_notification_title))
            .setContentText(getString(R.string.keep_alive_notification_text))
            .setSmallIcon(R.drawable.ic_notification_keep_alive)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openIntent)
            .apply { Diag.notificationActions(this@KeepAliveService).forEach(::addAction) }
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 7391
        private const val CHANNEL_ID = "keep_alive"
        private const val WATCHDOG_REQUEST_CODE = 7392

        fun start(context: Context) {
            val i = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }

        /**
         * Periodic alarm that just calls [start] again: if the process (and the service with it) got killed
         * in the background since the last tick, this relaunches it; if it's still running, it's a no-op.
         * Inexact (the OS batches it with other apps' alarms to save battery) - this is a safety net, not a
         * timer anything depends on firing precisely. Not boot-persisted, same as the service itself: re-armed
         * every time the process starts (see [com.linuxkernel44.openliquidlauncher.LauncherActivity.onCreate]).
         */
        fun scheduleWatchdog(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                watchdogPendingIntent(context),
            )
        }

        fun cancelWatchdog(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(watchdogPendingIntent(context))
        }

        private fun watchdogPendingIntent(context: Context): PendingIntent {
            val i = Intent(context, WatchdogReceiver::class.java)
            return PendingIntent.getBroadcast(context, WATCHDOG_REQUEST_CODE, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}

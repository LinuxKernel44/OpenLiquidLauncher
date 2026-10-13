package com.linuxkernel44.openliquidlauncher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.linuxkernel44.openliquidlauncher.LauncherActivity
import com.linuxkernel44.openliquidlauncher.R

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 7391
        private const val CHANNEL_ID = "keep_alive"

        fun start(context: Context) {
            val i = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}

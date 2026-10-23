package com.linuxkernel44.openliquidlauncher.diag

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.PixelCopy
import androidx.core.app.NotificationCompat
import com.linuxkernel44.openliquidlauncher.R
import com.linuxkernel44.openliquidlauncher.data.ShizukuBridge
import java.io.BufferedOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.launch

/**
 * Diagnostic test build only. Records everything the system logs (all logcat buffers) plus `dumpsys` snapshots into
 * `Android/data/<package>/files/diag`, so the intermittent "only the wallpaper after Home" state can be analysed afterwards
 * with the phone plugged in (tools/diag-pull.sh). Runs inside [com.linuxkernel44.openliquidlauncher.service.KeepAliveService].
 * Capture stops by itself once [capGb] is reached or the phone gets short on free space.
 */
object Diag {
    const val enabled = true
    const val ACTION_MARK = "com.linuxkernel44.openliquidlauncher.diag.MARK"
    private const val TAG = "OLLDiag"
    private const val ALERT_CHANNEL = "diag_alert"
    private const val GIB = 1024L * 1024 * 1024
    private const val MIN_FREE_BYTES = 5 * GIB
    private const val FILE_LIMIT_BYTES = 64L * 1024 * 1024

    private val total = AtomicLong(-1)
    @Volatile private var captureThread: Thread? = null
    @Volatile private var process: Process? = null
    @Volatile var status: String = "not started"
        private set
    private var lastActivity: WeakReference<Activity> = WeakReference(null)
    private val stamp get() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun prefs(c: Context) = c.getSharedPreferences("diag", Context.MODE_PRIVATE)
    fun captureEnabled(c: Context) = prefs(c).getBoolean("capture", true)
    fun setCaptureEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("capture", on).apply(); if (on) startCapture(c) else stopCapture("switched off") }
    fun capGb(c: Context) = prefs(c).getInt("capGb", 50)
    fun setCapGb(c: Context, gb: Int) { prefs(c).edit().putInt("capGb", gb.coerceIn(1, 500)).apply(); if (captureEnabled(c) && captureThread == null) startCapture(c) }

    fun dir(c: Context): File = (c.getExternalFilesDir("diag") ?: File(c.filesDir, "diag")).also { it.mkdirs() }

    fun usedBytes(c: Context): Long = dir(c).listFiles()?.sumOf { it.length() } ?: 0L
    fun fileCount(c: Context): Int = dir(c).listFiles()?.size ?: 0

    fun onServiceStart(context: Context) {
        val c = context.applicationContext
        if (captureEnabled(c)) {
            // Capture started without Shizuku and it is available now (or the thread died): (re)start so it gets full access.
            if (captureThread?.isAlive == true && !usingShizuku && ShizukuBridge.hasPermission()) stopCapture("restarting with Shizuku")
            startCapture(c)
        }
    }

    /** Asks Shizuku for permission (its own dialog) when it runs but this app has none yet. */
    fun requestShizuku() { if (ShizukuBridge.isRunning() && !ShizukuBridge.hasPermission()) kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) { ShizukuBridge.requestPermission() } }

    fun notificationActions(context: Context): List<NotificationCompat.Action> {
        val pi = PendingIntent.getBroadcast(
            context, 1, Intent(context, DiagReceiver::class.java).setAction(ACTION_MARK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return listOf(NotificationCompat.Action.Builder(R.drawable.ic_notification_keep_alive, "Mark bug", pi).build())
    }

    @Volatile private var usingShizuku = false

    /**
     * Runs a shell command line. Through Shizuku (shell rights) when it is available: that is the only way to read the whole system log
     * without Android's per-process "allow log access" prompt, and to dump window / display state. Otherwise as our own app,
     * where logcat only shows our own lines.
     */
    private fun spawn(shellLine: String): Process {
        if (ShizukuBridge.hasPermission()) runCatching { return ShizukuBridge.newProcess("sh", "-c", shellLine).also { usingShizuku = true } }
        usingShizuku = false
        return ProcessBuilder("sh", "-c", shellLine).start()
    }

    /** Why capture may not go on, or null. */
    private fun limitReason(c: Context): String? {
        if (total.get() >= capGb(c) * GIB) return "cap of ${capGb(c)} GB reached"
        val free = runCatching { StatFs(dir(c).path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < MIN_FREE_BYTES) return "less than 5 GB free on the phone"
        return null
    }

    @Synchronized
    fun startCapture(c: Context) {
        if (captureThread?.isAlive == true) return
        total.set(usedBytes(c))
        captureThread = thread(name = "diag-logcat", isDaemon = true) { captureLoop(c.applicationContext) }
    }

    @Synchronized
    fun stopCapture(why: String) {
        status = "stopped: $why"
        captureThread = null
        process?.destroy()
    }

    private fun captureLoop(c: Context) {
        val me = Thread.currentThread()
        var since: String? = null
        var out: BufferedOutputStream? = null
        var fileBytes = 0L
        var lastFreeCheck = 0L
        val ts = Regex("^\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3}")
        try {
            while (captureThread === me) {
                limitReason(c)?.let { r -> stopCapture(r); alert(c, "Log capture stopped: $r"); return }
                val p = spawn("logcat -b all -v threadtime${since?.let { " -T '$it'" } ?: ""} 2>&1")
                process = p
                val viaShizuku = usingShizuku
                status = if (viaShizuku) "capturing everything (Shizuku)" else "capturing OWN LOGS ONLY: start Shizuku and grant it to this app"
                val reader = p.inputStream.bufferedReader()
                while (captureThread === me) {
                    val line = reader.readLine() ?: break
                    if (out == null || fileBytes >= FILE_LIMIT_BYTES) {
                        out?.close()
                        out = BufferedOutputStream(File(dir(c), "log_$stamp.txt").outputStream(), 1 shl 16)
                        fileBytes = 0
                    }
                    val bytes = (line + "\n").toByteArray()
                    out.write(bytes)
                    fileBytes += bytes.size
                    total.addAndGet(bytes.size.toLong())
                    ts.find(line)?.let { since = it.value }
                    if (!reader.ready()) out.flush()
                    val now = System.currentTimeMillis()
                    if (total.get() >= capGb(c) * GIB || now - lastFreeCheck > 5000) {
                        lastFreeCheck = now
                        if (!viaShizuku && ShizukuBridge.hasPermission()) break // Shizuku came up: restart with full access
                        limitReason(c)?.let { r -> stopCapture(r); alert(c, "Log capture stopped: $r"); return }
                    }
                }
                p.destroy()
                if (captureThread === me) { status = "logcat ended, restarting"; Thread.sleep(2000) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "capture loop failed", e)
            status = "error: ${e.message}"
        } finally {
            runCatching { out?.close() }
        }
    }

    private fun alert(c: Context, text: String) {
        val mgr = c.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(ALERT_CHANNEL) == null) mgr.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Diagnostics", NotificationManager.IMPORTANCE_DEFAULT))
        mgr.notify(7393, NotificationCompat.Builder(c, ALERT_CHANNEL).setSmallIcon(R.drawable.ic_notification_keep_alive).setContentTitle("OpenLiquidLauncher diagnostics").setContentText(text).build())
    }

    /** Empties the capture folder and restarts the capture from scratch. */
    fun clear(c: Context) {
        stopCapture("clearing")
        thread {
            Thread.sleep(300)
            dir(c).listFiles()?.forEach { it.delete() }
            total.set(0)
            if (captureEnabled(c)) startCapture(c)
        }
    }

    // ---- snapshots ----

    private fun run(c: Context, reason: String, name: String, vararg cmd: String) {
        runCatching {
            limitReason(c)?.let { return }
            val f = File(dir(c), "snap_${stamp}_${reason}_$name.txt")
            val p = spawn("${cmd.joinToString(" ")} 2>&1")
            f.outputStream().use { o -> p.inputStream.copyTo(o) }
            p.waitFor()
            total.addAndGet(f.length())
        }
    }

    /** `dumpsys` state of the window manager / activities (and, when [heavy], display layers and processes) at this very moment. */
    fun snapshot(c: Context, reason: String, heavy: Boolean) = thread(name = "diag-snap") {
        run(c, reason, "windows", "dumpsys", "window", "windows")
        run(c, reason, "activities", "dumpsys", "activity", "activities")
        if (heavy) {
            run(c, reason, "recents", "dumpsys", "activity", "recents")
            run(c, reason, "displays", "dumpsys", "window", "displays")
            run(c, reason, "surfaceflinger", "dumpsys", "SurfaceFlinger")
            run(c, reason, "processes", "dumpsys", "activity", "processes")
        }
        systemScreenshot(c, reason)
    }

    /** What the user really sees (all layers, as composed by the system): `screencap` needs shell rights, hence Shizuku only. */
    private fun systemScreenshot(c: Context, reason: String) = runCatching {
        if (limitReason(c) != null || !ShizukuBridge.hasPermission()) return@runCatching
        val p = spawn("screencap -p")
        val png = p.inputStream.readBytes()
        p.waitFor()
        val bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size) ?: return@runCatching
        val small = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
        val f = File(dir(c), "screen_${stamp}_$reason.jpg")
        f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        total.addAndGet(f.length())
    }

    /** What our own window contains right now (PixelCopy of the activity window, scaled down). Fails if the window is not shown. */
    fun screenshot(c: Context, reason: String) {
        val a = lastActivity.get() ?: run { Log.i(TAG, "screenshot $reason: no activity"); return }
        if (limitReason(c) != null) return
        val w = a.window.decorView.width / 2
        val h = a.window.decorView.height / 2
        if (w <= 0 || h <= 0) { Log.i(TAG, "screenshot $reason: window has no size"); return }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(a.window, bmp, { r ->
                Log.i(TAG, "screenshot $reason: PixelCopy result=$r (0 = success)")
                if (r == PixelCopy.SUCCESS) thread {
                    val f = File(dir(c), "shot_${stamp}_$reason.jpg")
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 60, it) }
                    total.addAndGet(f.length())
                }
            }, Handler(Looper.getMainLooper()))
        }.onFailure { Log.i(TAG, "screenshot $reason failed: $it") }
    }

    /** Called by the activity on every resume: a light snapshot and a self-screenshot once the home screen had time to settle. */
    fun onResumed(activity: Activity) {
        lastActivity = WeakReference(activity)
        requestShizuku()
        val c = activity.applicationContext
        activity.window.decorView.postDelayed({
            Log.i(TAG, "resume+2.5s snapshot")
            snapshot(c, "resume", heavy = false)
            screenshot(c, "resume")
        }, 2500)
    }

    /** The notification button: pressed when the bug is on screen. Heavy snapshot now and again 8 s later. */
    fun mark(c: Context) {
        val human = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.w("OLLMark", "=== BUG MARK $human ===")
        runCatching { File(dir(c), "marks.txt").appendText("$human\n") }
        runCatching { c.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)) }
        snapshot(c, "MARK", heavy = true)
        screenshot(c, "MARK")
        Handler(Looper.getMainLooper()).postDelayed({ snapshot(c, "MARK8s", heavy = false) }, 8000)
    }
}

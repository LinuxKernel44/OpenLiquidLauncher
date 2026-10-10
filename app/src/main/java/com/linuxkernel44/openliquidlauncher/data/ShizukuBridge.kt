package com.linuxkernel44.openliquidlauncher.data

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/** Thin wrapper around Shizuku: availability, permission request and running a command with shell rights. */
object ShizukuBridge {
    private const val REQUEST_CODE = 4242
    private const val PACKAGE = "moe.shizuku.privileged.api"

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    /** True when the Shizuku service is running and reachable. */
    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean =
        runCatching { isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    /** Shows Shizuku's own permission dialog and suspends until the user answers. */
    suspend fun requestPermission(): Boolean {
        if (!isRunning()) return false
        if (hasPermission()) return true
        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) return false // "deny and don't ask again"
        return suspendCancellableCoroutine { cont ->
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != REQUEST_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
            runCatching { Shizuku.requestPermission(REQUEST_CODE) }.onFailure {
                Shizuku.removeRequestPermissionResultListener(listener)
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    /** Runs [cmd] as the shell user through Shizuku and returns its combined output. */
    fun run(vararg cmd: String): String {
        // Shizuku.newProcess is private since API 13 but still the simplest way to run a one-off command.
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        ).also { it.isAccessible = true }
        val proc = method.invoke(null, arrayOf(*cmd), null, null) as Process
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        proc.waitFor()
        return out + err
    }
}

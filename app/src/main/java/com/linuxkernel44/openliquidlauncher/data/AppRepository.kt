package com.linuxkernel44.openliquidlauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Loads launchable apps through [LauncherApps] (like Launcher3 does) and keeps them up to date. */
class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Called with the ids of apps that were installed after the first load. */
    var onAppsAdded: ((List<AppInfo>) -> Unit)? = null

    private val iconSize = (context.resources.displayMetrics.density * 64).toInt().coerceAtLeast(96)

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload(notifyNew = true)
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
    }

    fun start() {
        launcherApps.registerCallback(callback, main)
        reload()
    }

    fun byId(id: String): AppInfo? = _apps.value.firstOrNull { it.id == id }

    fun reload(notifyNew: Boolean = false) {
        scope.launch {
            val old = _apps.value.associateBy { it.id }
            // Icon decoding dominates cold start with hundreds of apps: spread it over all cores.
            val infos = userManager.userProfiles.flatMap { user -> launcherApps.getActivityList(null, user) }
            val list = ArrayList<AppInfo>(infos.size)
            coroutineScope {
                infos.map { info ->
                    async {
                        val id = idOf(info.componentName, info.user)
                        old[id] ?: toAppInfo(info, id)
                    }
                }.awaitAll().let { list += it }
            }
            list.sortBy { it.label.lowercase() }
            val added = if (notifyNew && _loaded.value) list.filter { it.id !in old } else emptyList()
            _apps.value = list
            _loaded.value = true
            if (added.isNotEmpty()) main.post { onAppsAdded?.invoke(added) }
        }
    }

    fun idOf(component: ComponentName, user: UserHandle): String =
        component.flattenToShortString() + "#" + userManager.getSerialNumberForUser(user)

    private fun toAppInfo(info: LauncherActivityInfo, id: String): AppInfo {
        val icon = info.getBadgedIcon(0)
        val themed = runCatching { renderThemed(info) }.getOrNull()
        return AppInfo(
            id = id,
            label = info.label.toString(),
            component = info.componentName,
            user = info.user,
            icon = icon.toBitmap(iconSize).asImageBitmap(),
            themedIcon = themed?.asImageBitmap(),
        )
    }

    /** Renders the monochrome layer of an adaptive icon as an alpha mask (tinted at draw time). */
    private fun renderThemed(info: LauncherActivityInfo): Bitmap? {
        val d = info.getIcon(0) as? AdaptiveIconDrawable ?: return null
        val mono = d.monochrome?.mutate() ?: return null
        // Adaptive layers are 108dp with a 72dp visible window: render large and crop the center.
        val big = (iconSize * 1.5f).toInt()
        val full = mono.toBitmap(big)
        val off = (big - iconSize) / 2
        return Bitmap.createBitmap(full, off, off, iconSize, iconSize)
    }

    fun myUser(): UserHandle = Process.myUserHandle()
}

fun Drawable.toBitmap(size: Int): Bitmap {
    val bmp = createBitmap(size, size)
    val c = Canvas(bmp)
    setBounds(0, 0, size, size)
    draw(c)
    return bmp
}

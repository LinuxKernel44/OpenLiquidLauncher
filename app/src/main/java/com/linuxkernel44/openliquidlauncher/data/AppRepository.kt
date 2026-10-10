package com.linuxkernel44.openliquidlauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.ui.graphics.asAndroidBitmap
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
    /** True once the list comes from PackageManager (not just from the disk cache): safe to prune the layout. */
    private val _synced = MutableStateFlow(false)
    val synced: StateFlow<Boolean> = _synced.asStateFlow()

    // Disk cache: label + icon per app, so a cold start (the system killed us in the background) is instant.
    private val cacheDir = File(context.filesDir, "icon_cache").apply { mkdirs() }
    private val indexFile = File(cacheDir, "index.json")
    private class CacheEntry(val id: String, val key: String, val label: String, val component: ComponentName, val serial: Long, val file: String)

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
        scope.launch {
            val cached = readCache()
            if (cached.isNotEmpty() && !_loaded.value) {
                _apps.value = cached
                _loaded.value = true
            }
            reload()
        }
    }

    private fun versionKey(info: LauncherActivityInfo): String {
        val f = File(info.applicationInfo.sourceDir)
        return "${f.lastModified()}:${f.length()}:${info.label}"
    }

    private fun readCache(): List<AppInfo> = runCatching {
        if (!indexFile.exists()) return emptyList()
        val arr = JSONArray(indexFile.readText())
        val list = ArrayList<AppInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val user = runCatching { userManager.getUserForSerialNumber(o.getLong("serial")) }.getOrNull() ?: continue
            val bmp = BitmapFactory.decodeFile(File(cacheDir, o.getString("file")).path) ?: continue
            val cn = ComponentName.unflattenFromString(o.getString("component")) ?: continue
            val themed = File(cacheDir, "t_" + o.getString("file")).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            list += AppInfo(o.getString("id"), o.getString("label"), cn, user, bmp.asImageBitmap(), themed?.asImageBitmap())
        }
        list
    }.getOrDefault(emptyList())

    private fun cacheKeyOf(id: String): String = id.hashCode().toUInt().toString(16) + ".png"

    private fun writeCache(entries: List<Pair<AppInfo, String>>) {
        runCatching {
            val arr = JSONArray()
            for ((a, key) in entries) {
                val file = cacheKeyOf(a.id)
                arr.put(JSONObject().put("id", a.id).put("key", key).put("label", a.label)
                    .put("component", a.component.flattenToString()).put("serial", userManager.getSerialNumberForUser(a.user)).put("file", file))
            }
            indexFile.writeText(arr.toString())
        }
    }

    private fun saveIcon(a: AppInfo) {
        runCatching { File(cacheDir, cacheKeyOf(a.id)).outputStream().use { a.icon.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) } }
        a.themedIcon?.let { t -> runCatching { File(cacheDir, "t_" + cacheKeyOf(a.id)).outputStream().use { t.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) } } }
    }

    fun byId(id: String): AppInfo? = _apps.value.firstOrNull { it.id == id }

    private var cacheKeys: Map<String, String> = emptyMap()

    fun reload(notifyNew: Boolean = false) {
        scope.launch {
            val old = _apps.value.associateBy { it.id }
            val oldKeys = if (cacheKeys.isEmpty()) readKeys() else cacheKeys
            // Icon decoding dominates cold start with hundreds of apps: spread it over all cores, reuse what is unchanged.
            val infos = userManager.userProfiles.flatMap { user -> launcherApps.getActivityList(null, user) }
            val keyed = ArrayList<Pair<AppInfo, String>>(infos.size)
            val fresh = ArrayList<AppInfo>()
            coroutineScope {
                infos.map { info ->
                    async {
                        val id = idOf(info.componentName, info.user)
                        val key = versionKey(info)
                        val known = old[id]
                        val reuse = known != null && oldKeys[id] == key
                        if (reuse) known!! to key
                        else toAppInfo(info, id).also { synchronized(fresh) { fresh += it } } to key
                    }
                }.awaitAll().let { keyed += it }
            }
            val list = keyed.map { it.first }.sortedBy { it.label.lowercase() }
            val added = if (notifyNew && _loaded.value) list.filter { it.id !in old } else emptyList()
            cacheKeys = keyed.associate { it.first.id to it.second }
            fresh.forEach(::saveIcon)
            if (fresh.isNotEmpty() || list.size != old.size) writeCache(keyed.sortedBy { it.first.label.lowercase() })
            _apps.value = list
            _loaded.value = true
            _synced.value = true
            if (added.isNotEmpty()) main.post { onAppsAdded?.invoke(added) }
        }
    }

    private fun readKeys(): Map<String, String> = runCatching {
        val arr = JSONArray(indexFile.readText())
        (0 until arr.length()).associate { arr.getJSONObject(it).let { o -> o.getString("id") to o.getString("key") } }
    }.getOrDefault(emptyMap())


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

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
    private val indexFile = File(cacheDir, "index3.json") // v3: opaque icons (v2 added the raw unmasked adaptive icon)
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

    private suspend fun readCache(): List<AppInfo> = runCatching {
        if (!indexFile.exists()) return emptyList()
        val arr = JSONArray(indexFile.readText())
        // Decode the PNGs on all cores: with ~200 apps this is the main cost of a cold start.
        coroutineScope {
            (0 until arr.length()).map { i ->
                async {
                    runCatching {
                        val o = arr.getJSONObject(i)
                        val user = userManager.getUserForSerialNumber(o.getLong("serial")) ?: return@async null
                        val bmp = BitmapFactory.decodeFile(File(cacheDir, o.getString("file")).path) ?: return@async null
                        val cn = ComponentName.unflattenFromString(o.getString("component")) ?: return@async null
                        val themed = File(cacheDir, "t_" + o.getString("file")).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
                        val raw = File(cacheDir, "r_" + o.getString("file")).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
                        AppInfo(o.getString("id"), o.getString("label"), cn, user, bmp.asImageBitmap(), themed?.asImageBitmap(), raw?.asImageBitmap())
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
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
        a.rawIcon?.let { r -> runCatching { File(cacheDir, "r_" + cacheKeyOf(a.id)).outputStream().use { r.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) } } }
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
        val raw = runCatching { renderRaw(info) }.getOrNull()
        return AppInfo(
            id = id,
            label = info.label.toString(),
            component = info.componentName,
            user = info.user,
            icon = icon.toBitmap(iconSize).makeOpaque().asImageBitmap(),
            themedIcon = themed?.asImageBitmap(),
            rawIcon = raw?.makeOpaque()?.asImageBitmap(),
        )
    }

    /** The adaptive icon's background + foreground drawn without the system mask, cropped to the visible 72/108 window. */
    private fun renderRaw(info: LauncherActivityInfo): Bitmap? {
        if (info.user != Process.myUserHandle()) return null // keep the work-profile badge of the system icon
        val d = info.getIcon(0) as? AdaptiveIconDrawable ?: return null
        val big = (iconSize * 1.5f).toInt()
        val bmp = createBitmap(big, big)
        val c = Canvas(bmp)
        d.background?.let { it.setBounds(0, 0, big, big); it.draw(c) }
        d.foreground?.let { it.setBounds(0, 0, big, big); it.draw(c) }
        val off = (big - iconSize) / 2
        return Bitmap.createBitmap(bmp, off, off, iconSize, iconSize)
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

/**
 * Icons from some apps have semi-transparent layers, so the wallpaper shows through them. Make the body of the icon fully
 * opaque: every pixel that is mostly solid is composited over the icon's own average colour and set to alpha 255; the
 * soft anti-aliased edge (alpha < 200) is only gently strengthened so the silhouette stays smooth.
 */
fun Bitmap.makeOpaque(): Bitmap {
    val w = width; val h = height
    val px = IntArray(w * h)
    getPixels(px, 0, w, 0, 0, w, h)
    // Base colour: average of the solid pixels (or of the mostly solid ones when none is fully solid).
    var r = 0L; var g = 0L; var b = 0L; var n = 0L
    for (limit in intArrayOf(250, 128)) {
        for (p in px) if ((p ushr 24) >= limit) { r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; n++ }
        if (n > 0) break
    }
    if (n == 0L) return this
    val br = (r / n).toInt(); val bg = (g / n).toInt(); val bb = (b / n).toInt()
    var changed = false
    for (i in px.indices) {
        val p = px[i]
        val a = p ushr 24
        if (a == 255 || a == 0) continue
        changed = true
        if (a >= 200) {
            val cr = (((p shr 16) and 255) * a + br * (255 - a)) / 255
            val cg = (((p shr 8) and 255) * a + bg * (255 - a)) / 255
            val cb = ((p and 255) * a + bb * (255 - a)) / 255
            px[i] = (255 shl 24) or (cr shl 16) or (cg shl 8) or cb
        } else {
            px[i] = ((a * 255 / 200) shl 24) or (p and 0xFFFFFF)
        }
    }
    if (!changed) return this
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

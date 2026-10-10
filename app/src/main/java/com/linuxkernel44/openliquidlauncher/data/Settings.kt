package com.linuxkernel44.openliquidlauncher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WallpaperMode { System, Custom }

data class LauncherSettings(
    val columns: Int = 5,
    val rows: Int = 6,
    val dockSize: Int = 5,
    val iconScale: Float = 1f,
    val showLabels: Boolean = true,
    val themedIcons: Boolean = false,
    val showBadges: Boolean = true,
    val wallpaperMode: WallpaperMode = WallpaperMode.Custom,
    val setSystemWallpaperToo: Boolean = false,
    // Liquid glass tuning
    val glassBlur: Float = 4f,
    val glassRefractionHeight: Float = 14f,
    val glassRefractionAmount: Float = 24f,
    val glassSurfaceAlpha: Float = 0.18f,
    val glassDispersion: Boolean = false,
    // Gestures
    val swipeUpDrawer: Boolean = true,
    val swipeDownNotifications: Boolean = true,
    val doubleTapLock: Boolean = true,
    val pinchEdit: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<LauncherSettings> = _state.asStateFlow()

    val current: LauncherSettings get() = _state.value

    fun update(block: LauncherSettings.() -> LauncherSettings) {
        val new = _state.value.block()
        _state.value = new
        write(new)
    }

    private fun read(): LauncherSettings {
        val d = LauncherSettings()
        return LauncherSettings(
            columns = prefs.getInt("columns", d.columns),
            rows = prefs.getInt("rows", d.rows),
            dockSize = prefs.getInt("dockSize", d.dockSize),
            iconScale = prefs.getFloat("iconScale", d.iconScale),
            showLabels = prefs.getBoolean("showLabels", d.showLabels),
            themedIcons = prefs.getBoolean("themedIcons", d.themedIcons),
            showBadges = prefs.getBoolean("showBadges", d.showBadges),
            wallpaperMode = WallpaperMode.entries.getOrElse(prefs.getInt("wallpaperMode", d.wallpaperMode.ordinal)) { d.wallpaperMode },
            setSystemWallpaperToo = prefs.getBoolean("setSystemWallpaperToo", d.setSystemWallpaperToo),
            glassBlur = prefs.getFloat("glassBlur", d.glassBlur),
            glassRefractionHeight = prefs.getFloat("glassRefractionHeight", d.glassRefractionHeight),
            glassRefractionAmount = prefs.getFloat("glassRefractionAmount", d.glassRefractionAmount),
            glassSurfaceAlpha = prefs.getFloat("glassSurfaceAlpha", d.glassSurfaceAlpha),
            glassDispersion = prefs.getBoolean("glassDispersion", d.glassDispersion),
            swipeUpDrawer = prefs.getBoolean("swipeUpDrawer", d.swipeUpDrawer),
            swipeDownNotifications = prefs.getBoolean("swipeDownNotifications", d.swipeDownNotifications),
            doubleTapLock = prefs.getBoolean("doubleTapLock", d.doubleTapLock),
            pinchEdit = prefs.getBoolean("pinchEdit", d.pinchEdit),
        )
    }

    private fun write(s: LauncherSettings) {
        prefs.edit()
            .putInt("columns", s.columns).putInt("rows", s.rows).putInt("dockSize", s.dockSize)
            .putFloat("iconScale", s.iconScale)
            .putBoolean("showLabels", s.showLabels).putBoolean("themedIcons", s.themedIcons)
            .putBoolean("showBadges", s.showBadges)
            .putInt("wallpaperMode", s.wallpaperMode.ordinal)
            .putBoolean("setSystemWallpaperToo", s.setSystemWallpaperToo)
            .putFloat("glassBlur", s.glassBlur)
            .putFloat("glassRefractionHeight", s.glassRefractionHeight)
            .putFloat("glassRefractionAmount", s.glassRefractionAmount)
            .putFloat("glassSurfaceAlpha", s.glassSurfaceAlpha)
            .putBoolean("glassDispersion", s.glassDispersion)
            .putBoolean("swipeUpDrawer", s.swipeUpDrawer)
            .putBoolean("swipeDownNotifications", s.swipeDownNotifications)
            .putBoolean("doubleTapLock", s.doubleTapLock)
            .putBoolean("pinchEdit", s.pinchEdit)
            .apply()
    }
}

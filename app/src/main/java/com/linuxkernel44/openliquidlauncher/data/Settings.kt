package com.linuxkernel44.openliquidlauncher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

val GLASS_RESOLUTIONS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f)
val GLASS_QUALITY_LABELS = listOf("Minimum", "Low", "Current", "High", "Maximum")

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
    /** 0..1: how "water droplet" small glass elements (folders, dock, buttons, handle, menus) look; 0 = original look. */
    val glassDroplet: Float = 0f,
    /** Launch animation style (see ui/Intro.kt: 0 None, 1 Fade, 2 Zoom, 3 Cascade, 4 Slide) and speed (0 fast, 1 normal, 2 slow). */
    val introStyle: Int = 2,
    val introSpeed: Int = 1,
    /** 0 Minimum · 1 Low · 2 Current (default) · 3 High · 4 Maximum: how rich the glass is. */
    val glassQuality: Int = 2,
    /** Index into [GLASS_RESOLUTIONS]: render scale of the glass (default 100 %). */
    val glassResolution: Int = 3,
    /** 0 = automatic (system decides), -1 = maximum, otherwise a fixed rate in Hz. */
    val refreshRate: Float = 0f,
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
            glassDroplet = prefs.getFloat("glassDroplet", d.glassDroplet).coerceIn(0f, 1f),
            introStyle = prefs.getInt("introStyle", d.introStyle).coerceIn(0, 4),
            introSpeed = prefs.getInt("introSpeed", d.introSpeed).coerceIn(0, 2),
            glassQuality = prefs.getInt("glassQuality", d.glassQuality).coerceIn(0, 4),
            glassResolution = prefs.getInt("glassResolution", d.glassResolution).coerceIn(0, GLASS_RESOLUTIONS.lastIndex),
            refreshRate = prefs.getFloat("refreshRate", d.refreshRate),
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
            .putBoolean("glassDispersion", s.glassDispersion).putFloat("glassDroplet", s.glassDroplet)
            .putInt("introStyle", s.introStyle).putInt("introSpeed", s.introSpeed)
            .putInt("glassQuality", s.glassQuality).putInt("glassResolution", s.glassResolution).putFloat("refreshRate", s.refreshRate)
            .putBoolean("swipeUpDrawer", s.swipeUpDrawer)
            .putBoolean("swipeDownNotifications", s.swipeDownNotifications)
            .putBoolean("doubleTapLock", s.doubleTapLock)
            .putBoolean("pinchEdit", s.pinchEdit)
            .apply()
    }
}

package com.linuxkernel44.openliquidlauncher

import android.app.Application
import com.linuxkernel44.openliquidlauncher.widget.LauncherWidgetHost
import com.linuxkernel44.openliquidlauncher.data.AppRepository
import com.linuxkernel44.openliquidlauncher.data.LayoutStore
import com.linuxkernel44.openliquidlauncher.data.SettingsStore
import com.linuxkernel44.openliquidlauncher.data.WallpaperRepository

class LauncherApp : Application() {
    lateinit var settings: SettingsStore
    lateinit var apps: AppRepository
    lateinit var layout: LayoutStore
    lateinit var wallpaper: WallpaperRepository
    lateinit var widgetHost: LauncherWidgetHost

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("OLLTrace", "process start pid=${android.os.Process.myPid()}")
        settings = SettingsStore(this)
        apps = AppRepository(this)
        layout = LayoutStore(this, settings)
        wallpaper = WallpaperRepository(this, settings)
        widgetHost = LauncherWidgetHost(this, WIDGET_HOST_ID)
        layout.load()
        apps.start()
    }

    companion object {
        const val WIDGET_HOST_ID = 1024
    }
}

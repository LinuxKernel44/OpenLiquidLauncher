package com.linuxkernel44.openliquidlauncher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.linuxkernel44.openliquidlauncher.data.WallpaperMode
import com.linuxkernel44.openliquidlauncher.ui.LauncherScreen
import com.linuxkernel44.openliquidlauncher.ui.LauncherState
import kotlinx.coroutines.launch

class LauncherActivity : ComponentActivity() {
    private lateinit var state: LauncherState
    private val app get() = application as LauncherApp
    private var pendingWidgetId = -1
    private var pendingProvider: AppWidgetProviderInfo? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) lifecycleScope.launch {
            if (app.wallpaper.importCustom(uri)) {
                app.settings.update { copy(wallpaperMode = WallpaperMode.Custom) }
                app.wallpaper.refresh()
            } else toast("Could not load that image")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        state = LauncherState(this, app)
        app.apps.onAppsAdded = state::onAppsAdded
        setContent { LauncherScreen(state, ::pickWallpaper, ::startAddWidget) }
    }

    override fun onStart() { super.onStart(); app.widgetHost.startListening() }
    override fun onStop() { app.widgetHost.stopListening(); super.onStop() }

    override fun onResume() {
        super.onResume()
        refreshWallpaper()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) state.homeSignal++
    }

    fun refreshWallpaper() { lifecycleScope.launch { app.wallpaper.refresh() } }

    fun pickWallpaper() = pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---- widgets: allocate -> bind (ask the user if needed) -> configure -> place ----
    private fun startAddWidget(info: AppWidgetProviderInfo) {
        val id = app.widgetHost.allocateAppWidgetId()
        pendingWidgetId = id; pendingProvider = info
        if (AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(id, info.provider)) {
            configureOrAdd(id, info)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_BIND)
        }
    }

    private fun configureOrAdd(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            runCatching { app.widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null) }
                .onFailure { state.addWidget(id, info) }
        } else state.addWidget(id, info)
    }

    @Deprecated("Widget bind/configure flows still use request codes")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val id = pendingWidgetId
        val info = pendingProvider
        if (requestCode != REQ_BIND && requestCode != REQ_CONFIGURE) return
        pendingWidgetId = -1; pendingProvider = null
        if (resultCode != Activity.RESULT_OK || info == null || id < 0) {
            if (id >= 0) app.widgetHost.deleteAppWidgetId(id)
            return
        }
        if (requestCode == REQ_BIND) { pendingWidgetId = id; pendingProvider = info; configureOrAdd(id, info) }
        else state.addWidget(id, info)
    }

    companion object {
        private const val REQ_BIND = 9001
        private const val REQ_CONFIGURE = 9002
    }
}

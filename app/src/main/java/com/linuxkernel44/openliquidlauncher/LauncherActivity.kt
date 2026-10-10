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
import com.linuxkernel44.openliquidlauncher.data.ImportException
import com.linuxkernel44.openliquidlauncher.data.ImportPlan
import com.linuxkernel44.openliquidlauncher.data.PendingWidget
import com.linuxkernel44.openliquidlauncher.data.ShizukuBridge
import com.linuxkernel44.openliquidlauncher.data.WallpaperMode
import com.linuxkernel44.openliquidlauncher.ui.ImportRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

    private val pickDb = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.importConfirm = ImportRequest.FromFile(uri)
    }

    private val importQueue = ArrayDeque<PendingWidget>()
    private var importTarget: PendingWidget? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        state = LauncherState(this, app)
        app.apps.onAppsAdded = state::onAppsAdded
        setContent { LauncherScreen(state, ::pickWallpaper, ::startAddWidget) }
        handleDebugIntent(intent)
    }

    /** Debug builds only: `--es importDb <path>` triggers the file import without the document picker. */
    private fun handleDebugIntent(i: Intent?) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        i?.getStringExtra("importDb")?.let { state.importConfirm = ImportRequest.FromFile(android.net.Uri.fromFile(File(it))) }
        if (i?.getBooleanExtra("importDumpsys", false) == true) runImport(ImportRequest.FromLauncher(state.importer.findSources(), shizuku = i.getBooleanExtra("shizuku", false)))
    }

    override fun onStart() { super.onStart(); app.widgetHost.startListening() }
    override fun onStop() { app.widgetHost.stopListening(); super.onStop() }

    override fun onResume() {
        super.onResume()
        refreshWallpaper()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) state.homeSignal++
    }

    fun refreshWallpaper() { lifecycleScope.launch { app.wallpaper.refresh() } }

    fun pickWallpaper() = pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    // ---- import the layout of another launcher (e.g. OnePlus / OxygenOS) ----
    fun beginImportFromLauncher() {
        val sources = runCatching { state.importer.findSources() }.getOrDefault(emptyList())
        if (sources.isEmpty()) state.importMessage = "No other launcher was found on this device."
        else state.importConfirm = ImportRequest.FromLauncher(sources)
    }

    fun beginImportWithShizuku() {
        when {
            !ShizukuBridge.isInstalled(this) -> state.importMessage = "Shizuku isn't installed. Get it from github.com/RikkaApps/Shizuku, start it, then try again."
            !ShizukuBridge.isRunning() -> state.importMessage = "Shizuku isn't running. Start it from the Shizuku app, then try again."
            else -> {
                val sources = runCatching { state.importer.findSources() }.getOrDefault(emptyList())
                if (sources.isEmpty()) state.importMessage = "No other launcher was found on this device."
                else state.importConfirm = ImportRequest.FromLauncher(sources, shizuku = true)
            }
        }
    }

    fun pickLauncherDb() = pickDb.launch(arrayOf("*/*"))

    fun runImport(req: ImportRequest) {
        state.importConfirm = null
        state.importBusy = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (req) {
                        is ImportRequest.FromLauncher -> planFromSources(req.sources, req.shizuku)
                        is ImportRequest.FromFile -> {
                            val tmp = File(cacheDir, "import.tmp")
                            contentResolver.openInputStream(req.uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                                ?: throw ImportException("Could not open that file.")
                            try {
                                val head = tmp.inputStream().use { it.readNBytes(15).decodeToString() }
                                // A launcher.db (SQLite) or a text dump of the launcher (adb shell dumpsys activity ...).
                                if (head.startsWith("SQLite format 3")) state.importer.importFromDatabase(tmp)
                                else state.importer.importFromDump(tmp.readText())
                            } finally { tmp.delete() }
                        }
                    }
                }
            }
            state.importBusy = false
            result.onSuccess(::applyImport).onFailure { state.importMessage = it.message ?: "Import failed." }
        }
    }

    /** Per launcher: its content provider first, then the `dumpsys` route (works on OxygenOS, needs the DUMP grant). */
    private suspend fun planFromSources(sources: List<com.linuxkernel44.openliquidlauncher.data.LauncherSource>, shizukuOnly: Boolean): ImportPlan {
        val errors = ArrayList<String>()
        // Shizuku (shell rights) is used when asked for, or automatically if it already runs and we hold its permission.
        var useShizuku = shizukuOnly || ShizukuBridge.hasPermission()
        if (shizukuOnly) {
            if (!ShizukuBridge.isRunning()) throw ImportException("Shizuku isn't running. Start it from the Shizuku app, then try again.")
            if (!ShizukuBridge.requestPermission()) throw ImportException("Shizuku permission was denied. Allow OpenLiquidLauncher in the Shizuku app and try again.")
        }
        for (src in sources) {
            if (!shizukuOnly) try { return withContext(Dispatchers.IO) { state.importer.importFromProvider(src) } } catch (e: ImportException) { errors += e.message.orEmpty() }
            val comp = state.importer.homeActivityOf(src.packageName) ?: continue
            try {
                var text = withContext(Dispatchers.IO) { state.importer.dumpLauncher(comp, useShizuku) }
                if (!state.importer.hasLayout(text)) {
                    // The launcher isn't alive: open it briefly so there is a window to dump, then come back.
                    startActivity(Intent().setComponent(comp).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    kotlinx.coroutines.delay(2500)
                    text = withContext(Dispatchers.IO) { state.importer.dumpLauncher(comp, useShizuku) }
                    startActivity(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
                return withContext(Dispatchers.IO) { state.importer.importFromDump(text) }
            } catch (e: ImportException) { errors += "${src.label}: ${e.message}" }
        }
        throw ImportException(errors.distinct().joinToString("\n\n"))
    }

    private fun applyImport(plan: ImportPlan) {
        state.layout.widgetItems().forEach { app.widgetHost.deleteAppWidgetId(it.widgetId) }
        app.settings.update { copy(columns = plan.columns, rows = plan.rows, dockSize = plan.dockSize) }
        state.layout.replaceAll(plan.placed, plan.dock)
        state.layout.normalize(state.apps.apps.value.mapTo(HashSet()) { it.id })
        state.showSettings = false
        state.importMessage = buildString {
            append("Imported ${plan.apps} apps and ${plan.folders} folders on ${state.layout.pages} page(s).")
            if (plan.skipped > 0) append("\n${plan.skipped} item(s) couldn't be imported (not installed or unsupported).")
            if (plan.partialFolders > 0) append("\n${plan.partialFolders} folder(s) hold more apps than the launcher shows in their preview; only the visible ones were imported.")
            if (plan.widgets.isNotEmpty()) append("\n${plan.widgets.size} widget(s) will now ask for your confirmation.")
        }
        importQueue.clear(); importQueue += plan.widgets
        // Widgets are processed once the user dismisses the summary.
    }

    fun continueWidgetImport() { processImportQueue() }

    private fun processImportQueue() {
        val next = importQueue.removeFirstOrNull() ?: return
        val info = AppWidgetManager.getInstance(this).installedProviders.firstOrNull { it.provider == next.provider }
        if (info == null) { processImportQueue(); return }
        importTarget = next
        startAddWidget(info)
    }

    private fun finishWidget(id: Int, info: AppWidgetProviderInfo) {
        state.addWidget(id, info, importTarget)
        importTarget = null
        processImportQueue()
    }

    private fun abandonWidget(id: Int) {
        if (id >= 0) app.widgetHost.deleteAppWidgetId(id)
        importTarget = null
        processImportQueue()
    }

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
                .onFailure { finishWidget(id, info) }
        } else finishWidget(id, info)
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
            abandonWidget(id)
            return
        }
        if (requestCode == REQ_BIND) { pendingWidgetId = id; pendingProvider = info; configureOrAdd(id, info) }
        else finishWidget(id, info)
    }

    companion object {
        private const val REQ_BIND = 9001
        private const val REQ_CONFIGURE = 9002
    }
}

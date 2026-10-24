package com.linuxkernel44.openliquidlauncher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.linuxkernel44.openliquidlauncher.service.KeepAliveService
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.linuxkernel44.openliquidlauncher.R
import com.linuxkernel44.openliquidlauncher.data.ImportException
import com.linuxkernel44.openliquidlauncher.data.ImportPlan
import com.linuxkernel44.openliquidlauncher.data.PendingWidget
import com.linuxkernel44.openliquidlauncher.data.ShizukuBridge
import com.linuxkernel44.openliquidlauncher.diag.Diag
import com.linuxkernel44.openliquidlauncher.ui.INTRO_DURATIONS_MS
import com.linuxkernel44.openliquidlauncher.ui.ImportRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.linuxkernel44.openliquidlauncher.ui.LauncherScreen
import com.linuxkernel44.openliquidlauncher.ui.LauncherState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LauncherActivity : ComponentActivity() {
    private lateinit var state: LauncherState
    private val app get() = application as LauncherApp
    private var pendingWidgetId = -1
    private var pendingProvider: AppWidgetProviderInfo? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val bmp = app.wallpaper.decodeForCrop(uri)
            if (bmp != null) state.wallpaperCropSource = bmp else toast(getString(R.string.toast_could_not_load_image))
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
        // Show the wallpaper right away instead of a black window while Compose composes the first frame.
        runCatching {
            val f = File(filesDir, "wallpaper.jpg")
            if (f.exists()) {
                val bmp = android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
                window.setBackgroundDrawable(android.graphics.drawable.BitmapDrawable(resources, bmp))
            }
        }
        state = LauncherState(this, app)
        app.apps.onAppsAdded = state::onAppsAdded
        setContent { LauncherScreen(state, ::pickWallpaper, ::startAddWidget) }
        handleDebugIntent(intent)
        handleAppIconLaunch(intent)
        initRefreshRates()
        lifecycleScope.launch { app.settings.state.map { it.refreshRate }.distinctUntilChanged().collect(::applyRefreshRate) }
        // The service is foreground-only (not boot-persisted), so re-arm it (and its watchdog) every time the process starts.
        if (app.settings.current.keepAliveService || Diag.enabled) { KeepAliveService.start(this); KeepAliveService.scheduleWatchdog(this) }
    }

    private val requestNotifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) KeepAliveService.start(this)
    }

    /** Settings -> System -> "Keep launcher running": starts the foreground service and, if needed, asks to
     * be exempted from battery optimizations (OxygenOS and similar OEM skins otherwise still kill it). Also
     * arms a periodic watchdog alarm that restarts the service (and the process with it) if it still gets
     * killed despite that. */
    fun enableKeepAlive() {
        val needsNotifPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsNotifPermission) requestNotifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) else KeepAliveService.start(this)
        KeepAliveService.scheduleWatchdog(this)
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (!BuildConfig.PLAY && pm?.isIgnoringBatteryOptimizations(packageName) != true) {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))) }
        }
    }

    fun disableKeepAlive() {
        if (Diag.enabled) return // the diagnostic build's log capture lives in that service
        KeepAliveService.cancelWatchdog(this)
        KeepAliveService.stop(this)
    }

    /** Display modes of the screen's current resolution: their distinct refresh rates (Hz). */
    private fun displayModes(): List<android.view.Display.Mode> {
        val d = getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return emptyList()
        val cur = d.mode
        return d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
    }

    private fun initRefreshRates() {
        state.refreshRates = displayModes().map { Math.round(it.refreshRate) }.distinct().sorted()
    }

    /** 0 = automatic (the system picks), -1 = highest available, otherwise the closest mode to that rate. */
    private fun applyRefreshRate(rate: Float) {
        val modes = displayModes()
        val id = when {
            rate == 0f || modes.isEmpty() -> 0
            rate < 0f -> modes.maxByOrNull { it.refreshRate }?.modeId ?: 0
            else -> modes.minByOrNull { kotlin.math.abs(it.refreshRate - rate) }?.modeId ?: 0
        }
        window.attributes = window.attributes.also { it.preferredDisplayModeId = id }
    }

    fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
    }

    /** Opened from the app icon (not as the home screen): show the settings, and explain how to make it the default launcher. */
    private fun handleAppIconLaunch(i: Intent?) {
        if (i == null || !i.hasCategory(Intent.CATEGORY_LAUNCHER) || i.hasCategory(Intent.CATEGORY_HOME)) return
        state.showSettings = true
        if (!isDefaultHome()) toast(getString(R.string.toast_set_default_hint))
    }

    /** Debug builds only: `--es importDb <path>` triggers the file import without the document picker. */
    var debugManyApps by androidx.compose.runtime.mutableStateOf(false)

    private fun handleDebugIntent(i: Intent?) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (i?.getBooleanExtra("debugManyApps", false) == true) debugManyApps = true
        i?.getStringExtra("importDb")?.let { state.importConfirm = ImportRequest.FromFile(android.net.Uri.fromFile(File(it))) }
        if (i?.getBooleanExtra("importDumpsys", false) == true) runImport(ImportRequest.FromLauncher(state.importer.findSources(), shizuku = i.getBooleanExtra("shizuku", false)))
    }

    override fun onStart() {
        super.onStart()
        trace("onStart")
        // Widgets re-deliver their RemoteViews as soon as we listen: let the first frames of the return animation
        // go out before that work lands on the main thread.
        lifecycleScope.launch {
            kotlinx.coroutines.delay(300)
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) app.widgetHost.startListening()
        }
    }

    override fun onRestart() { super.onRestart(); trace("onRestart") }

    override fun onStop() {
        app.widgetHost.stopListening()
        trace("onStop")
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        trace("onResume")
        Diag.onResumed(this)
        refreshWallpaper() // no-op unless the wallpaper actually changed
        // Only the cold-start animation hides the home elements (introT starts at 0). If it never ran or got stuck
        // (interrupted lifecycle, frame clock paused at the wrong moment), don't leave a bare wallpaper: anything still
        // hidden once the animation had time to finish is shown. Same for a drawer left half-closed by a cancelled animation.
        lifecycleScope.launch {
            kotlinx.coroutines.delay(INTRO_DURATIONS_MS[app.settings.current.introSpeed] + 1200L)
            if (state.introT < 1f) state.introT = 1f
            val d = state.drawerProgress
            if (!state.drawerTarget && !d.isRunning && d.value > 0f) d.snapTo(0f)
            trace("resume+settled")
        }
        // First frame drawn after resuming: shows in logcat how long the window takes to produce pixels.
        val t0 = android.os.SystemClock.uptimeMillis()
        android.view.Choreographer.getInstance().postFrameCallback { trace("firstFrame +${android.os.SystemClock.uptimeMillis() - t0}ms") }
    }

    /** Lifecycle breadcrumbs (tag `OLLTrace`): with `adb logcat -s OLLTrace` they show what the launcher saw when the home screen came up bare. */
    private fun trace(event: String) {
        if (!::state.isInitialized) return
        android.util.Log.i("OLLTrace", "$event introT=${state.introT} drawer=${state.drawerProgress.value}/${state.drawerTarget} focus=${hasWindowFocus()} state=${lifecycle.currentState}")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); trace("focus=$hasFocus") }

    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); trace("trimMemory=$level") }

    override fun onPause() {
        trace("onPause")
        // Leaving (Home, an app, the shade...): a gesture in progress can't finish, so cancel it cleanly.
        state.cancelInteractions()
        currentFocus?.clearFocus()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
        handleAppIconLaunch(intent)
        trace("onNewIntent ${intent.action}")
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            state.homeSignal++
            watchForStuckRecents()
        }
    }

    // ---- OxygenOS: a second Home gesture during the ~0.6-0.9 s recents animation leaves its (empty) RecentsActivity on top ----
    private val homeIntentTimes = ArrayList<Long>()
    private var unstickAttempts = 0
    private var lastUnstickAt = 0L
    private val unstick = Runnable {
        val shown = lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED && hasWindowFocus()
        trace("unstick check shown=$shown")
        val now = android.os.SystemClock.uptimeMillis()
        if (shown) unstickAttempts = 0
        // Not stuck if the user did something since the Home swipes: touched this screen or started an app from it.
        else if (!isFinishing && unstickAttempts < 3 && now - state.lastLaunchAt > 3000 && lastTouchAt < (homeIntentTimes.firstOrNull() ?: 0L)) {
            // With Shizuku we can ask the system who is on top: only the empty recents screen counts as stuck, so an app the user
            // opened by another route (recents, notification...) is never pulled away. Without it, the checks above have to do.
            if (ShizukuBridge.hasPermission()) Thread {
                val top = runCatching { ShizukuBridge.run("sh", "-c", "dumpsys activity activities | grep -m1 topResumedActivity") }.getOrDefault("")
                runOnUiThread { if (top.contains("RecentsActivity")) sendHomeAgain() else trace("unstick skipped, top: ${top.trim().take(120)}") }
            }.start() else sendHomeAgain()
        }
    }

    private fun sendHomeAgain() {
        unstickAttempts++
        lastUnstickAt = android.os.SystemClock.uptimeMillis()
        // Same as pressing Home once more, which is what the user has to do by hand.
        runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        trace("unstick: sent Home again")
    }

    private var lastTouchAt = 0L
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        lastTouchAt = android.os.SystemClock.uptimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    /** Called when the user starts an app from the launcher: whatever is on top then is not a stuck recents screen. */
    fun cancelUnstick() { window.decorView.removeCallbacks(unstick) }

    /**
     * The system's gesture handler (OxygenOS' quickstep launcher animates every Home swipe for a third-party launcher) breaks
     * when a second Home swipe starts while the first animation still runs: it ends up on its own empty recents screen, which
     * shows only the wallpaper, and our launcher is never brought back. That second swipe reaches us as a Home intent
     * more than 300 ms after the first (the two intents of a single swipe come 50 ms apart). If, shortly after such a pair, we are
     * still not the focused screen, send Home again ourselves.
     */
    private fun watchForStuckRecents() {
        val now = android.os.SystemClock.uptimeMillis()
        homeIntentTimes.removeAll { now - it > 3000 }
        homeIntentTimes += now
        window.decorView.removeCallbacks(unstick)
        // Our own corrective Home is re-checked sooner: when the first one lands while we are still shown as resumed, it changes nothing.
        if (now - homeIntentTimes.first() > 300) window.decorView.postDelayed(unstick, if (now - lastUnstickAt < 500) 1000L else 1800L)
    }

    fun refreshWallpaper() { lifecycleScope.launch { app.wallpaper.refresh() } }

    fun pickWallpaper() = pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    // ---- import the layout of another launcher (e.g. OnePlus / OxygenOS) ----
    fun beginImportFromLauncher() {
        val sources = runCatching { state.importer.findSources() }.getOrDefault(emptyList())
        if (sources.isEmpty()) state.importMessage = getString(R.string.import_no_launcher_found)
        else state.importConfirm = ImportRequest.FromLauncher(sources)
    }

    fun beginImportWithShizuku() {
        when {
            !ShizukuBridge.isInstalled(this) -> state.importMessage = getString(R.string.import_shizuku_not_installed)
            !ShizukuBridge.isRunning() -> state.importMessage = getString(R.string.import_shizuku_not_running)
            else -> {
                val sources = runCatching { state.importer.findSources() }.getOrDefault(emptyList())
                if (sources.isEmpty()) state.importMessage = getString(R.string.import_no_launcher_found)
                else state.importConfirm = ImportRequest.FromLauncher(sources, shizuku = true)
            }
        }
    }

    fun pickLauncherDb() = pickDb.launch(arrayOf("*/*"))

    fun runImport(req: ImportRequest) {
        state.importConfirm = null
        state.importBusy = true
        lifecycleScope.launch {
            // The app list loads asynchronously; matching icons against an empty list would wipe the layout.
            while (!state.apps.loaded.value || state.apps.apps.value.isEmpty()) kotlinx.coroutines.delay(100)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (req) {
                        is ImportRequest.FromLauncher -> planFromSources(req.sources, req.shizuku)
                        is ImportRequest.FromFile -> {
                            val tmp = File(cacheDir, "import.tmp")
                            contentResolver.openInputStream(req.uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                                ?: throw ImportException(getString(R.string.import_could_not_open_file))
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
            result.onSuccess(::applyImport).onFailure { state.importMessage = it.message ?: getString(R.string.import_failed_generic) }
        }
    }

    /** Per launcher: its content provider first, then the `dumpsys` route (works on OxygenOS, needs the DUMP grant). */
    private suspend fun planFromSources(sources: List<com.linuxkernel44.openliquidlauncher.data.LauncherSource>, shizukuOnly: Boolean): ImportPlan {
        val errors = ArrayList<String>()
        // Shizuku (shell rights) is used when asked for, or automatically if it already runs and we hold its permission.
        var useShizuku = shizukuOnly || ShizukuBridge.hasPermission()
        if (shizukuOnly) {
            if (!ShizukuBridge.isRunning()) throw ImportException(getString(R.string.import_shizuku_not_running))
            if (!ShizukuBridge.requestPermission()) throw ImportException(getString(R.string.import_shizuku_permission_denied))
        }
        for (src in sources) {
            if (!shizukuOnly) try { return withContext(Dispatchers.IO) { state.importer.importFromProvider(src) } } catch (e: ImportException) { errors += e.message.orEmpty() }
            val comp = state.importer.homeActivityOf(src.packageName) ?: continue
            try {
                var text = withContext(Dispatchers.IO) { state.importer.dumpLauncher(comp, useShizuku) }
                var stockShown = false
                if (!state.importer.hasLayout(text)) {
                    // The launcher isn't alive: open it briefly so there is a window to dump.
                    startActivity(Intent().setComponent(comp).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); stockShown = true
                    kotlinx.coroutines.delay(2500)
                    text = withContext(Dispatchers.IO) { state.importer.dumpLauncher(comp, useShizuku) }
                }
                var plan = withContext(Dispatchers.IO) { state.importer.importFromDump(text) }
                if (useShizuku && plan.partial.isNotEmpty()) {
                    // Big folders: open each one in the stock launcher (it must be on screen) to read all its apps.
                    if (!stockShown) { startActivity(Intent().setComponent(comp).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); stockShown = true; kotlinx.coroutines.delay(1500) }
                    plan = withContext(Dispatchers.IO) {
                        state.importer.completeFolders(plan, { ShizukuBridge.run(*it) }, { state.importer.dumpLauncher(comp, true) })
                    }
                }
                if (stockShown) startActivity(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                return plan
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
            append(getString(R.string.import_summary, plan.apps, plan.folders, state.layout.pages))
            if (plan.skipped > 0) append(getString(R.string.import_summary_skipped, plan.skipped))
            if (plan.partialFolders > 0) append(getString(R.string.import_summary_partial_folders, plan.partialFolders))
            if (plan.widgets.isNotEmpty()) append(getString(R.string.import_summary_widgets, plan.widgets.size))
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

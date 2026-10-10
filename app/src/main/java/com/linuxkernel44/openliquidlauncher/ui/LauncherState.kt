package com.linuxkernel44.openliquidlauncher.ui

import android.app.ActivityOptions
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect as AndroidRect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.linuxkernel44.openliquidlauncher.LauncherActivity
import com.linuxkernel44.openliquidlauncher.LauncherApp
import com.linuxkernel44.openliquidlauncher.data.AppInfo
import com.linuxkernel44.openliquidlauncher.data.AppItem
import com.linuxkernel44.openliquidlauncher.data.FolderItem
import com.linuxkernel44.openliquidlauncher.data.Item
import com.linuxkernel44.openliquidlauncher.data.LauncherImporter
import com.linuxkernel44.openliquidlauncher.data.LauncherSource
import com.linuxkernel44.openliquidlauncher.data.PendingWidget
import com.linuxkernel44.openliquidlauncher.data.WidgetItem
import com.linuxkernel44.openliquidlauncher.service.GestureAccessibilityService
import kotlin.math.ceil

/** What is being dragged. [item] is an existing home item; otherwise [app] is a fresh copy (drawer/folder). */
class DragPayload(
    val item: Item? = null,
    val app: AppInfo? = null,
    val fromFolderId: Long? = null,
) {
    val isApp get() = app != null || item is AppItem
    val isFolder get() = item is FolderItem
    val isWidget get() = item is WidgetItem
    val itemId: Long get() = item?.id ?: -1L
}

sealed interface DropTarget {
    data class Cell(val page: Int, val x: Int, val y: Int, val spanX: Int = 1, val spanY: Int = 1, val mergeWith: Long? = null) : DropTarget
    data class Slot(val index: Int, val mergeWith: Long? = null) : DropTarget
    data object Remove : DropTarget
    data object Uninstall : DropTarget
}

class MenuState(val payload: DragPayload, val anchor: Rect)

sealed interface ImportRequest {
    data class FromLauncher(val sources: List<LauncherSource>) : ImportRequest
    data class FromFile(val uri: Uri) : ImportRequest
}

/** Central UI controller: overlays, drag & drop, launching. Persisted data lives in [LauncherApp]. */
class LauncherState(val activity: LauncherActivity, val app: LauncherApp) {
    val settings get() = app.settings
    val layout get() = app.layout
    val apps get() = app.apps

    private val launcherApps = activity.getSystemService(LauncherApps::class.java)

    // ---- overlays ----
    val drawerProgress = Animatable(0f)
    var drawerTarget by mutableStateOf(false)
    var openFolderId by mutableStateOf<Long?>(null)
    var showSettings by mutableStateOf(false)
    var showWidgetPicker by mutableStateOf(false)
    var editMode by mutableStateOf(false)
    var menu by mutableStateOf<MenuState?>(null)
    var importConfirm by mutableStateOf<ImportRequest?>(null)
    var importMessage by mutableStateOf<String?>(null)
    var importBusy by mutableStateOf(false)
    val importer by lazy { LauncherImporter(activity, apps, layout) }

    val anyOverlay get() = drawerTarget || openFolderId != null || showSettings || showWidgetPicker || editMode || menu != null

    var pagerState: PagerState? = null
    var homeSignal by mutableIntStateOf(0)

    suspend fun openDrawer() { drawerTarget = true; drawerProgress.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
    suspend fun closeDrawer() { drawerTarget = false; drawerProgress.animateTo(0f, tween(320, easing = FastOutSlowInEasing)) }

    /** Back / home press. Returns true if something was dismissed. */
    suspend fun dismissOverlays(): Boolean {
        when {
            menu != null -> menu = null
            showSettings -> showSettings = false
            showWidgetPicker -> showWidgetPicker = false
            openFolderId != null -> openFolderId = null
            drawerTarget -> closeDrawer()
            editMode -> editMode = false
            else -> return false
        }
        return true
    }

    // ---- geometry reported by the UI (root coordinates) ----
    var workspaceRect by mutableStateOf(Rect.Zero)
    var dockRect by mutableStateOf(Rect.Zero)
    var removeRect by mutableStateOf(Rect.Zero)
    var uninstallRect by mutableStateOf(Rect.Zero)

    /** Offset of the window on screen, to convert raw touch coordinates of embedded views. */
    fun screenToRoot(screen: Offset): Offset {
        val loc = IntArray(2)
        activity.window.decorView.getLocationOnScreen(loc)
        return Offset(screen.x - loc[0], screen.y - loc[1])
    }

    // ---- drag & drop ----
    var dragPayload by mutableStateOf<DragPayload?>(null)
    var dragPointer by mutableStateOf(Offset.Zero)
    var dropTarget by mutableStateOf<DropTarget?>(null)
    private var pendingPayload: DragPayload? = null
    private var pressStart = Offset.Zero
    val dragging get() = dragPayload != null
    var dragFromOverlay by mutableStateOf(false)

    fun beginPress(payload: DragPayload, anchor: Rect, pointer: Offset, showMenu: Boolean = true) {
        activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        pendingPayload = payload
        pressStart = pointer
        if (showMenu) menu = MenuState(payload, anchor)
    }

    fun dragMove(pointer: Offset, slopPx: Float) {
        if (dragPayload == null) {
            val p = pendingPayload ?: return
            if ((pointer - pressStart).getDistance() < slopPx) return
            menu = null
            dragFromOverlay = drawerTarget || openFolderId != null
            dragPayload = p
        }
        dragPointer = pointer
        dropTarget = computeTarget(pointer)
    }

    fun dragEnd() {
        val payload = dragPayload
        if (payload != null) {
            drop(payload, dropTarget)
            dragPayload = null
            dropTarget = null
        }
        pendingPayload = null
    }

    fun dragCancel() {
        dragPayload = null; dropTarget = null; pendingPayload = null
    }

    private fun computeTarget(p: Offset): DropTarget? {
        val payload = dragPayload ?: return null
        if (removeRect.contains(p)) return DropTarget.Remove
        if (uninstallRect.contains(p) && payload.isApp) return DropTarget.Uninstall
        val cols = layout.columns
        val rows = layout.rows
        if (dockRect.contains(p) && !payload.isWidget) {
            val n = settings.current.dockSize
            val slotW = dockRect.width / n
            val i = ((p.x - dockRect.left) / slotW).toInt().coerceIn(0, n - 1)
            val occ = layout.dock.getOrNull(i)
            return when {
                occ == null || occ.id == payload.itemId -> DropTarget.Slot(i)
                payload.isApp && (occ is AppItem || occ is FolderItem) -> DropTarget.Slot(i, mergeWith = occ.id)
                else -> null
            }
        }
        if (workspaceRect.contains(p)) {
            val page = pagerState?.currentPage ?: 0
            val cw = workspaceRect.width / cols
            val ch = workspaceRect.height / rows
            val cx = ((p.x - workspaceRect.left) / cw).toInt().coerceIn(0, cols - 1)
            val cy = ((p.y - workspaceRect.top) / ch).toInt().coerceIn(0, rows - 1)
            val w = payload.item as? WidgetItem
            if (w != null) {
                val sx = w.spanX.coerceAtMost(cols); val sy = w.spanY.coerceAtMost(rows)
                val x = (cx - sx / 2).coerceIn(0, cols - sx)
                val y = (cy - sy / 2).coerceIn(0, rows - sy)
                return if (layout.isFree(page, x, y, sx, sy, payload.itemId)) DropTarget.Cell(page, x, y, sx, sy) else null
            }
            val occ = layout.placedAt(page, cx, cy, payload.itemId)
            return when {
                occ == null -> DropTarget.Cell(page, cx, cy)
                payload.isApp && (occ.item is AppItem || occ.item is FolderItem) -> DropTarget.Cell(page, cx, cy, mergeWith = occ.item.id)
                else -> null
            }
        }
        return null
    }

    private fun drop(payload: DragPayload, target: DropTarget?) {
        val newItem: Item? = payload.item ?: payload.app?.let { AppItem(layout.newId(), it.id) }
        when (target) {
            null -> return
            DropTarget.Remove -> {
                payload.item?.let { removeItem(it) }
                payload.fromFolderId?.let { fid -> payload.app?.let { a -> (layout.findItem(fid) as? FolderItem)?.let { layout.removeFromFolder(it, a.id) } } }
            }
            DropTarget.Uninstall -> (payload.app ?: (payload.item as? AppItem)?.let { apps.byId(it.appId) })?.let(::uninstall)
            is DropTarget.Cell -> {
                newItem ?: return
                removeFromSourceFolder(payload)
                if (target.mergeWith != null) (newItem as? AppItem)?.let { layout.mergeInto(it, target.mergeWith) }
                else layout.placeOnWorkspace(newItem, target.page, target.x, target.y)
            }
            is DropTarget.Slot -> {
                newItem ?: return
                removeFromSourceFolder(payload)
                if (target.mergeWith != null) (newItem as? AppItem)?.let { layout.mergeInto(it, target.mergeWith) }
                else layout.placeInDock(newItem, target.index)
            }
        }
        if (payload.fromFolderId != null) openFolderId = null
        layout.normalize(apps.apps.value.mapTo(HashSet()) { it.id })
    }

    private fun removeFromSourceFolder(payload: DragPayload) {
        val fid = payload.fromFolderId ?: return
        val a = payload.app ?: return
        (layout.findItem(fid) as? FolderItem)?.let { layout.removeFromFolder(it, a.id) }
    }

    private fun removeItem(item: Item) {
        if (item is WidgetItem) app.widgetHost.deleteAppWidgetId(item.widgetId)
        layout.remove(item.id)
        layout.normalize(null)
    }

    fun removeFromHome(item: Item) { menu = null; removeItem(item) }

    // ---- launching ----
    fun launch(info: AppInfo, bounds: Rect?) {
        runCatching {
            val b = bounds?.let { AndroidRect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }
            val opts = b?.let {
                ActivityOptions.makeScaleUpAnimation(activity.window.decorView, it.left, it.top, it.width(), it.height())
            }
            launcherApps.startMainActivity(info.component, info.user, b, opts?.toBundle())
        }
    }

    fun appInfo(info: AppInfo, bounds: Rect?) {
        runCatching {
            launcherApps.startAppDetailsActivity(info.component, info.user, null, null)
        }
    }

    fun uninstall(info: AppInfo) {
        menu = null
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${info.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun shortcutsFor(info: AppInfo): List<ShortcutInfo> = runCatching {
        val q = LauncherApps.ShortcutQuery()
            .setPackage(info.packageName)
            .setActivity(info.component)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
            )
        launcherApps.getShortcuts(q, info.user).orEmpty().filter { it.isEnabled }.take(4)
    }.getOrDefault(emptyList())

    fun startShortcut(s: ShortcutInfo) {
        menu = null
        runCatching { launcherApps.startShortcut(s, null, null) }
    }

    fun shortcutIcon(s: ShortcutInfo) = runCatching { launcherApps.getShortcutIconDrawable(s, 0) }.getOrNull()

    // ---- gestures ----
    fun expandNotifications() {
        val viaReflection = runCatching {
            val sbm = activity.getSystemService("statusbar")
            sbm.javaClass.getMethod("expandNotificationsPanel").invoke(sbm)
        }.isSuccess
        if (!viaReflection && !GestureAccessibilityService.openNotifications()) {
            activity.toast("Enable \"OpenLiquidLauncher gestures\" in Accessibility settings")
            openAccessibilitySettings()
        }
    }

    fun lockScreen() {
        if (!GestureAccessibilityService.lockScreen()) {
            activity.toast("Enable \"OpenLiquidLauncher gestures\" in Accessibility settings")
            openAccessibilitySettings()
        }
    }

    fun openAccessibilitySettings() = activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun openNotificationAccessSettings() = activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun openAllFilesAccess() {
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openDefaultHomeSettings() = runCatching {
        activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- folders ----
    fun renameFolder(id: Long, name: String) {
        (layout.findItem(id) as? FolderItem)?.let { layout.replaceItem(it.copy(name = name.ifBlank { "Folder" })) }
    }

    // ---- widgets ----
    fun spanFor(info: AppWidgetProviderInfo): Pair<Int, Int> {
        val cols = layout.columns; val rows = layout.rows
        val cw = workspaceRect.width / cols; val ch = workspaceRect.height / rows
        if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            return info.targetCellWidth.coerceIn(1, cols) to info.targetCellHeight.coerceIn(1, rows)
        }
        val sx = if (cw > 0) ceil(info.minWidth / cw).toInt() else 2
        val sy = if (ch > 0) ceil(info.minHeight / ch).toInt() else 2
        return sx.coerceIn(1, cols) to sy.coerceIn(1, rows)
    }

    fun addWidget(widgetId: Int, info: AppWidgetProviderInfo, target: PendingWidget? = null) {
        val (sx, sy) = if (target != null) target.spanX.coerceIn(1, layout.columns) to target.spanY.coerceIn(1, layout.rows) else spanFor(info)
        val item = WidgetItem(layout.newId(), widgetId, sx, sy)
        // Imported widgets go back to the exact cell they had, if it is free.
        if (target != null && layout.isFree(target.page, target.x, target.y, sx, sy)) {
            layout.placeOnWorkspace(item, target.page, target.x, target.y); return
        }
        val page = target?.page ?: (pagerState?.currentPage ?: 0)
        var placed = false
        outer@ for (y in 0..layout.rows - sy) for (x in 0..layout.columns - sx) {
            if (layout.isFree(page, x, y, sx, sy)) { layout.placeOnWorkspace(item, page, x, y); placed = true; break@outer }
        }
        if (!placed) placed = layout.addToFirstFree(item, sx, sy)
        if (!placed) { app.widgetHost.deleteAppWidgetId(widgetId); activity.toast("No space for this widget") }
    }

    // ---- first run ----
    fun ensureInitialLayout() {
        val validIds = apps.apps.value.mapTo(HashSet()) { it.id }
        layout.ensureDockSize()
        if (layout.restored) { layout.normalize(validIds); return }
        val all = apps.apps.value
        val dockPkgs = defaultDockPackages()
        val dockApps = dockPkgs.mapNotNull { pkg -> all.firstOrNull { it.packageName == pkg } }.distinctBy { it.id }
            .take(settings.current.dockSize)
        dockApps.forEachIndexed { i, a -> layout.dock[i] = AppItem(layout.newId(), a.id) }
        val dockIds = dockApps.map { it.id }.toSet()
        val cols = layout.columns
        all.filter { it.id !in dockIds }.take(cols * 3).forEachIndexed { i, a ->
            layout.placeOnWorkspace(AppItem(layout.newId(), a.id), 0, i % cols, i / cols)
        }
        layout.markInitialized()
        layout.save()
    }

    private fun defaultDockPackages(): List<String> {
        val pm = activity.packageManager
        fun resolve(i: Intent) = pm.resolveActivity(i, 0)?.activityInfo?.packageName?.takeIf { it != "android" }
        return listOfNotNull(
            resolve(Intent(Intent.ACTION_DIAL)),
            resolve(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)),
            resolve(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)),
            resolve(Intent(MediaStore.ACTION_IMAGE_CAPTURE)),
            resolve(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)),
            resolve(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS)),
            resolve(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)),
        )
    }

    fun onAppsAdded(added: List<AppInfo>) {
        val known = layout.allAppIdsOnHome()
        added.filter { it.id !in known }.forEach { layout.addToFirstFree(AppItem(layout.newId(), it.id)) }
    }
}

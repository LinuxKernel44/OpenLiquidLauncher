package com.linuxkernel44.openliquidlauncher.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.Backdrop
import com.linuxkernel44.openliquidlauncher.data.AppInfo
import com.linuxkernel44.openliquidlauncher.data.AppItem
import com.linuxkernel44.openliquidlauncher.data.FolderItem
import com.linuxkernel44.openliquidlauncher.data.LauncherSettings
import com.linuxkernel44.openliquidlauncher.data.WidgetItem
import com.linuxkernel44.openliquidlauncher.data.toBitmap
import kotlin.math.roundToInt

// ---- drag layer & drop zones ------------------------------------------------------------------

@Composable
fun DragLayer(state: LauncherState, settings: LauncherSettings, backdrop: Backdrop, style: GlassStyle) {
    val payload = state.dragPayload ?: return
    val density = LocalDensity.current
    val half = with(density) { 36.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset((state.dragPointer.x - half).roundToInt(), (state.dragPointer.y - half * 1.5f).roundToInt()) }
            .scale(1.18f)
            .alpha(0.95f),
    ) { DragPreview(payload, state, settings, backdrop, style) }
}

@Composable
fun DropZones(state: LauncherState, backdrop: Backdrop, style: GlassStyle) {
    val payload = state.dragPayload
    val visible by animateFloatAsState(if (payload != null) 1f else 0f, label = "zones")
    if (visible <= 0.01f) return
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 10.dp).alpha(visible),
        horizontalArrangement = Arrangement.Center,
    ) {
        Zone("Remove", state.dropTarget == DropTarget.Remove, backdrop, style, Modifier.onGloballyPositioned { state.removeRect = it.boundsInRoot() })
        if (payload?.isApp == true) {
            Box(Modifier.width(14.dp))
            Zone("Uninstall", state.dropTarget == DropTarget.Uninstall, backdrop, style, Modifier.onGloballyPositioned { state.uninstallRect = it.boundsInRoot() })
        }
    }
}

@Composable
private fun Zone(label: String, active: Boolean, backdrop: Backdrop, style: GlassStyle, modifier: Modifier) {
    Box(
        modifier.scale(if (active) 1.08f else 1f)
            .liquidGlass(backdrop, style, RoundedCornerShape(50), surfaceAlpha = style.surfaceAlpha + if (active) 0.25f else 0.08f, tint = if (active) Color(0x66FF3B5C) else Color.Unspecified)
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) { GText(label, size = 14f, weight = FontWeight.SemiBold) }
}

// ---- context menu -----------------------------------------------------------------------------

@Composable
fun ItemMenu(state: LauncherState, menu: MenuState, backdrop: Backdrop, style: GlassStyle) {
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val payload = menu.payload
    val app: AppInfo? = payload.app ?: (payload.item as? AppItem)?.let { state.apps.byId(it.appId) }
    val shortcuts = remember(app) { app?.let(state::shortcutsFor).orEmpty() }
    val screenW = state.workspaceRect.right.let { if (it <= 0f) 1080f else it }
    val screenH = with(density) { 2400.dp.toPx() }

    Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { state.menu = null }) {
        val widthPx = with(density) { 232.dp.toPx() }
        val x = (menu.anchor.center.x - widthPx / 2).coerceIn(with(density) { 12.dp.toPx() }, (screenW - widthPx - with(density) { 12.dp.toPx() }).coerceAtLeast(0f))
        val below = menu.anchor.bottom + size.height + with(density) { 80.dp.toPx() } < state.dockRect.top.let { if (it <= 0f) screenH else it + with(density) { 40.dp.toPx() } }
        val y = if (below) menu.anchor.bottom + with(density) { 8.dp.toPx() } else (menu.anchor.top - size.height - with(density) { 8.dp.toPx() }).coerceAtLeast(with(density) { 40.dp.toPx() })
        Column(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(232.dp)
                .onSizeChanged { size = it }
                .liquidGlass(backdrop, style, RoundedCornerShape(26.dp), surfaceAlpha = style.surfaceAlpha + 0.1f)
                .padding(vertical = 8.dp)
                .clickable(interactionSource = null, indication = null) { },
        ) {
            shortcuts.forEach { s ->
                val icon = remember(s) { state.shortcutIcon(s)?.toBitmapSafe()?.asImageBitmap() }
                MenuRow((s.shortLabel ?: s.longLabel ?: "").toString(), icon) { state.startShortcut(s) }
            }
            if (app != null) MenuRow("App info", null) { state.menu = null; state.appInfo(app, menu.anchor) }
            when {
                payload.item != null -> MenuRow(
                    when (payload.item) { is WidgetItem -> "Remove widget"; is FolderItem -> "Remove folder"; else -> "Remove from Home" }, null,
                ) { state.removeFromHome(payload.item) }
                app != null && payload.fromFolderId == null -> MenuRow("Add to Home", null) {
                    state.menu = null
                    state.layout.addToFirstFree(AppItem(state.layout.newId(), app.id))
                    state.activity.toast("Added to Home")
                }
            }
            if (app != null) MenuRow("Uninstall", null, destructive = true) { state.uninstall(app) }
        }
    }
}

@Composable
private fun MenuRow(label: String, icon: ImageBitmap?, destructive: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Image(androidx.compose.ui.graphics.painter.BitmapPainter(icon), null, Modifier.size(22.dp)); Box(Modifier.width(12.dp)) }
        GText(label, size = 15f, color = if (destructive) Color(0xFFFF8A9B) else Color.White, align = androidx.compose.ui.text.style.TextAlign.Start)
    }
}

private fun Drawable.toBitmapSafe() = runCatching { toBitmap(96) }.getOrNull()

// ---- folder -----------------------------------------------------------------------------------

@Composable
fun FolderOverlay(state: LauncherState, folderId: Long, settings: LauncherSettings, badges: Map<String, Int>, backdrop: Backdrop, style: GlassStyle, fade: Float) {
    val folder = state.layout.findItem(folderId) as? FolderItem
    if (folder == null) { state.openFolderId = null; return }
    var name by remember(folderId) { mutableStateOf(folder.name) }
    val appear by animateFloatAsState(1f, label = "folder")
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f * fade))
            .clickable(interactionSource = null, indication = null) { state.renameFolder(folderId, name); state.openFolderId = null },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 26.dp).fillMaxWidth().scale(0.9f + 0.1f * appear).alpha(fade)
                .liquidGlass(backdrop, style, RoundedCornerShape(38.dp), surfaceAlpha = style.surfaceAlpha + 0.1f)
                .clickable(interactionSource = null, indication = null) { }
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                textStyle = GlassText.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            )
            val cols = 4
            val apps = folder.appIds.mapNotNull { state.apps.byId(it) }
            apps.chunked(cols).forEach { rowApps ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    rowApps.forEach { a ->
                        val hidden = state.dragPayload?.app?.id == a.id && state.dragPayload?.fromFolderId == folderId
                        Box(Modifier.weight(1f).alpha(if (hidden) 0f else 1f), contentAlignment = Alignment.Center) {
                            AppCell(
                                a, settings, badges[a.packageName] ?: 0,
                                Modifier.itemGestures(state, { DragPayload(app = a, fromFolderId = folderId) }, onTap = { r -> state.renameFolder(folderId, name); state.openFolderId = null; state.launch(a, r) }),
                                iconSize = 52.dp,
                            )
                        }
                    }
                    repeat(cols - rowApps.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ---- edit bar ---------------------------------------------------------------------------------

@Composable
fun EditBar(state: LauncherState, backdrop: Backdrop, style: GlassStyle, onWallpaper: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassButton("Widgets", backdrop, style) { state.showWidgetPicker = true }
        GlassButton("Wallpaper", backdrop, style, onClick = onWallpaper)
        GlassButton("Settings", backdrop, style) { state.showSettings = true }
        GlassButton("Done", backdrop, style) { state.editMode = false }
    }
}

// ---- widget picker ----------------------------------------------------------------------------

private class ProviderRow(val info: AppWidgetProviderInfo, val label: String, val appLabel: String, val preview: ImageBitmap?, val span: Pair<Int, Int>)

@Composable
fun WidgetPicker(state: LauncherState, backdrop: Backdrop, style: GlassStyle, onPick: (AppWidgetProviderInfo) -> Unit) {
    val ctx = LocalContext.current
    val rows = remember {
        AppWidgetManager.getInstance(ctx).installedProviders.map { info ->
            val pm = ctx.packageManager
            val appLabel = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() }.getOrDefault(info.provider.packageName)
            val preview = runCatching { info.loadPreviewImage(ctx, 0)?.toBitmapSafe()?.asImageBitmap() }.getOrNull()
            ProviderRow(info, info.loadLabel(pm), appLabel, preview, state.spanFor(info))
        }.sortedWith(compareBy({ it.appLabel.lowercase() }, { it.label.lowercase() }))
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)).clickable(interactionSource = null, indication = null) { state.showWidgetPicker = false }) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.72f).bleed(top = false)
                .liquidGlass(backdrop, style.copy(refractionHeight = minOf(style.refractionHeight, 5f), blur = style.blur + 12f), RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp), surfaceAlpha = style.surfaceAlpha + 0.12f)
                .clickable(interactionSource = null, indication = null) { },
        ) {
            GText("Widgets", Modifier.padding(start = 68.dp, top = 20.dp, bottom = 20.dp), size = 20f, weight = FontWeight.Bold)
            LazyColumn(Modifier.weight(1f).padding(horizontal = 60.dp)) {
                items(rows) { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { state.showWidgetPicker = false; onPick(r.info) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(84.dp).background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                            if (r.preview != null) Image(androidx.compose.ui.graphics.painter.BitmapPainter(r.preview), null, Modifier.size(76.dp))
                        }
                        Column(Modifier.padding(start = 14.dp)) {
                            GText(r.label, size = 15f, weight = FontWeight.SemiBold, align = androidx.compose.ui.text.style.TextAlign.Start)
                            GText("${r.appLabel} · ${r.span.first}×${r.span.second}", size = 12f, color = Color.White.copy(alpha = 0.7f), align = androidx.compose.ui.text.style.TextAlign.Start)
                        }
                    }
                }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(56.dp))
        }
    }
}

// ---- import dialogs ---------------------------------------------------------------------------

@Composable
fun ImportDialogs(state: LauncherState, backdrop: Backdrop, style: GlassStyle) {
    val confirm = state.importConfirm
    val message = state.importMessage
    if (confirm == null && message == null && !state.importBusy) return
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable(interactionSource = null, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 28.dp).fillMaxWidth()
                .liquidGlass(backdrop, style.copy(refractionHeight = minOf(style.refractionHeight, 8f)), RoundedCornerShape(34.dp), surfaceAlpha = style.surfaceAlpha + 0.10f, tint = Color.Black.copy(alpha = 0.25f))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                state.importBusy -> GText("Importing…", size = 18f, weight = FontWeight.SemiBold)
                confirm != null -> {
                    GText("Import home layout", size = 20f, weight = FontWeight.Bold)
                    val what = when (confirm) {
                        is ImportRequest.FromLauncher -> "from " + confirm.sources.joinToString(", ") { it.label }
                        is ImportRequest.FromFile -> "from the selected launcher.db"
                    }
                    GText("This replaces your current home screen and dock with the layout $what.", size = 14f, maxLines = 6)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton("Cancel", backdrop, style) { state.importConfirm = null }
                        GlassButton("Import", backdrop, style) { state.activity.runImport(confirm) }
                    }
                }
                message != null -> {
                    GText("Import", size = 20f, weight = FontWeight.Bold)
                    GText(message, size = 13f, maxLines = 12, align = androidx.compose.ui.text.style.TextAlign.Start)
                    GlassButton("OK", backdrop, style) { state.importMessage = null; state.activity.continueWidgetImport() }
                }
            }
        }
    }
}

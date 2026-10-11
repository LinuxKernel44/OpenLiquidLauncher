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
import androidx.compose.ui.graphics.graphicsLayer
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
            .liquidGlass(backdrop, style, style.round(50, small = true), surfaceAlpha = style.surfaceAlpha + if (active) 0.25f else 0.08f, tint = if (active) Color(0x66FF3B5C) else Color.Unspecified, small = true)
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
                // A card of rows, not a small button: roundDp (like Settings sections) instead of the percent-based,
                // droplet-driven shape, which turns a tall narrow menu into a circle/blob. small = false for the same reason.
                .liquidGlass(backdrop, style, style.roundDp(18.dp), surfaceAlpha = style.surfaceAlpha + 0.1f)
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
    val e = state.folderProgress.value
    val grows = state.folderGrows
    var screen by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var panel by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Box(
        Modifier.fillMaxSize().onSizeChanged { screen = it }.background(Color.Black.copy(alpha = 0.28f * fade * e))
            .clickable(interactionSource = null, indication = null) { state.renameFolder(folderId, name); state.closeFolder() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 26.dp).fillMaxWidth().onSizeChanged { panel = it }
                .graphicsLayer {
                    val o = state.folderOrigin
                    if (grows && panel.width > 0 && o != androidx.compose.ui.geometry.Rect.Zero) {
                        // Grow out of the folder icon: start the size and centre of the icon, end at the panel.
                        val s0x = (o.width / panel.width).coerceIn(0.05f, 1f)
                        val s0y = (o.height / panel.height).coerceIn(0.05f, 1f)
                        scaleX = s0x + (1f - s0x) * e
                        scaleY = s0y + (1f - s0y) * e
                        translationX = (o.center.x - screen.width / 2f) * (1f - e)
                        translationY = (o.center.y - screen.height / 2f) * (1f - e)
                        alpha = (e * 2.5f).coerceAtMost(1f) * fade
                    } else {
                        alpha = e * fade
                        val sc = 0.94f + 0.06f * e
                        scaleX = sc; scaleY = sc
                    }
                }
                .liquidGlass(backdrop, style, style.roundDp(38.dp, style.folderRoundness), surfaceAlpha = style.surfaceAlpha + 0.1f)
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
                                Modifier.itemGestures(state, { DragPayload(app = a, fromFolderId = folderId) }, onTap = { r -> state.renameFolder(folderId, name); state.closeFolder(); state.launch(a, r) }),
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

private enum class EditIconKind { Widgets, Wallpaper, Settings, Done }

/** Small line icons drawn on a canvas (no icon library needed). */
@Composable
private fun EditIcon(kind: EditIconKind, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(24.dp)) {
        val w = size.width
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = w * 0.085f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
        val c = Color.White
        fun rr(x: Float, y: Float, s: Float) = drawRoundRect(
            c, androidx.compose.ui.geometry.Offset(x * w, y * w), androidx.compose.ui.geometry.Size(s * w, s * w),
            androidx.compose.ui.geometry.CornerRadius(0.07f * w), style = stroke,
        )
        when (kind) {
            EditIconKind.Widgets -> { rr(0.1f, 0.1f, 0.36f); rr(0.54f, 0.1f, 0.36f); rr(0.1f, 0.54f, 0.36f); rr(0.54f, 0.54f, 0.36f) }
            EditIconKind.Wallpaper -> {
                drawRoundRect(c, androidx.compose.ui.geometry.Offset(0.1f * w, 0.16f * w), androidx.compose.ui.geometry.Size(0.8f * w, 0.68f * w), androidx.compose.ui.geometry.CornerRadius(0.1f * w), style = stroke)
                drawCircle(c, 0.07f * w, androidx.compose.ui.geometry.Offset(0.34f * w, 0.38f * w), style = stroke)
                val m = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0.14f * w, 0.78f * w); lineTo(0.42f * w, 0.52f * w); lineTo(0.58f * w, 0.66f * w); lineTo(0.7f * w, 0.55f * w); lineTo(0.88f * w, 0.74f * w)
                }
                drawPath(m, c, style = stroke)
            }
            EditIconKind.Settings -> {
                for ((y, k) in listOf(0.24f to 0.32f, 0.5f to 0.66f, 0.76f to 0.42f)) {
                    drawLine(c, androidx.compose.ui.geometry.Offset(0.12f * w, y * w), androidx.compose.ui.geometry.Offset(0.88f * w, y * w), stroke.width, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawCircle(Color(0xFF3A3F55), 0.1f * w, androidx.compose.ui.geometry.Offset(k * w, y * w))
                    drawCircle(c, 0.1f * w, androidx.compose.ui.geometry.Offset(k * w, y * w), style = stroke)
                }
            }
            EditIconKind.Done -> {
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(0.18f * w, 0.54f * w); lineTo(0.42f * w, 0.76f * w); lineTo(0.84f * w, 0.26f * w) }
                drawPath(p, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.11f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.EditButton(
    label: String, kind: EditIconKind, backdrop: Backdrop, style: GlassStyle, onClick: () -> Unit,
) {
    Column(
        Modifier.weight(1f)
            .liquidGlass(backdrop, style.copy(refractionHeight = minOf(style.refractionHeight, 10f)), style.round(39, small = true), surfaceAlpha = style.surfaceAlpha + 0.06f, small = true)
            .pressScale(onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        EditIcon(kind)
        GText(label, size = 10.5f, weight = FontWeight.SemiBold)
    }
}

/** Four equal buttons that always fit the screen width, whatever the font size or density. */
@Composable
fun EditBar(state: LauncherState, backdrop: Backdrop, style: GlassStyle, onWallpaper: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EditButton("Widgets", EditIconKind.Widgets, backdrop, style) { state.showWidgetPicker = true }
        EditButton("Wallpaper", EditIconKind.Wallpaper, backdrop, style, onWallpaper)
        EditButton("Settings", EditIconKind.Settings, backdrop, style) { state.showSettings = true }
        EditButton("Done", EditIconKind.Done, backdrop, style) { state.editMode = false }
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
                .liquidGlass(backdrop, style.copy(refractionHeight = minOf(style.refractionHeight, 5f), blur = style.blur * 4f), (38.dp * 2f * style.roundness).coerceAtLeast(2.dp).let { RoundedCornerShape(topStart = it, topEnd = it) }, surfaceAlpha = style.surfaceAlpha + 0.12f)
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
                .liquidGlass(backdrop, style.copy(refractionHeight = minOf(style.refractionHeight, 8f)), style.roundDp(34.dp), surfaceAlpha = style.surfaceAlpha + 0.10f, tint = Color.Black.copy(alpha = 0.25f))
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

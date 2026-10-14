package com.linuxkernel44.openliquidlauncher.ui

import android.appwidget.AppWidgetManager
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalViewConfiguration
import com.kyant.backdrop.Backdrop
import com.linuxkernel44.openliquidlauncher.data.AppInfo
import com.linuxkernel44.openliquidlauncher.data.AppItem
import com.linuxkernel44.openliquidlauncher.data.FolderItem
import com.linuxkernel44.openliquidlauncher.data.Item
import com.linuxkernel44.openliquidlauncher.data.LauncherSettings
import com.linuxkernel44.openliquidlauncher.data.WidgetItem
import com.linuxkernel44.openliquidlauncher.widget.DragAwareHostView
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.withTimeoutOrNull

/** Shape of app icons for the "App icon roundness" setting: from a slightly rounded square (0.01) to a circle (1). */
fun appIconShape(roundness: Float) = RoundedCornerShape(percent = (10 + 40 * roundness).toInt().coerceIn(10, 50))

/** iOS-style up/down bounce for the icon whose context menu is currently open. */
@Composable
fun Modifier.menuBounce(active: Boolean): Modifier {
    if (!active) return this
    val amplitude = with(LocalDensity.current) { 5.dp.toPx() }
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "menuBounce")
    val t by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "menuBounceT",
    )
    return this.graphicsLayer { translationY = t * amplitude }
}

/**
 * Renders an app icon: the system's own masked icon by default, our own mask when "App icon roundness" is on (adaptive
 * icons only), or the monochrome "themed" look when enabled.
 */
@Composable
fun AppIconImage(app: AppInfo, settings: LauncherSettings, size: Dp, modifier: Modifier = Modifier, quality: FilterQuality = FilterQuality.High) {
    val themed = app.themedIcon
    val raw = app.rawIcon
    val custom = settings.appIconRoundness > 0f
    if (settings.themedIcons && themed != null) {
        val shape = if (custom) appIconShape(settings.appIconRoundness) else RoundedCornerShape(size * 0.3f)
        Box(modifier.size(size).clip(shape).background(Color(0xFF2E3340)), contentAlignment = Alignment.Center) {
            Image(BitmapPainter(themed, filterQuality = quality), null, Modifier.size(size * 0.86f), colorFilter = ColorFilter.tint(Color(0xFFDDE3FF)))
        }
    } else if (custom && raw != null) {
        Image(BitmapPainter(raw, filterQuality = quality), app.label, modifier.size(size).clip(appIconShape(settings.appIconRoundness)))
    } else {
        Image(BitmapPainter(app.icon, filterQuality = quality), app.label, modifier.size(size))
    }
}

@Composable
fun BadgeDot(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size(if (count > 1) 20.dp else 14.dp).clip(CircleShape).background(Color(0xFFFF3B5C)),
        contentAlignment = Alignment.Center,
    ) { if (count > 1) GText(if (count > 99) "99+" else count.toString(), size = 10f, weight = androidx.compose.ui.text.font.FontWeight.Bold) }
}

@Composable
fun AppCell(
    app: AppInfo,
    settings: LauncherSettings,
    badge: Int,
    modifier: Modifier = Modifier,
    iconSize: Dp = 56.dp,
    showLabel: Boolean = settings.showLabels,
    selected: Boolean = false,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            AppIconImage(app, settings, iconSize * settings.iconScale)
            if (settings.showBadges && badge > 0) BadgeDot(badge, Modifier.align(Alignment.TopEnd))
            if (selected) Box(Modifier.align(Alignment.TopStart).size(20.dp).clip(CircleShape).background(Color(0xFF34C759)), contentAlignment = Alignment.Center) { GText("✓", size = 12f, weight = androidx.compose.ui.text.font.FontWeight.Bold) }
        }
        if (showLabel) GText(app.label, Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp), size = 11.5f)
    }
}

/** Folder icon: a small glass tile with up to 9 mini icons. */
@Composable
fun FolderCell(
    folder: FolderItem,
    apps: (String) -> AppInfo?,
    settings: LauncherSettings,
    backdrop: Backdrop,
    style: GlassStyle,
    modifier: Modifier = Modifier,
    iconSize: Dp = 56.dp,
    showLabel: Boolean = settings.showLabels,
) {
    val size = iconSize * settings.iconScale
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).liquidGlass(backdrop, style.copy(refractionHeight = 8f, refractionAmount = 12f), style.round(30, small = true, r = style.folderRoundness, dropletShape = false), surfaceAlpha = style.surfaceAlpha + 0.08f, small = true, innerShadowCentered = true)
                .padding(size * 0.1f),
        ) {
            val mini = size * 0.24f
            val gap = size * 0.04f
            folder.appIds.take(9).forEachIndexed { i, id ->
                val a = apps(id) ?: return@forEachIndexed
                AppIconImage(a, settings, mini, Modifier.offset((mini + gap) * (i % 3), (mini + gap) * (i / 3)), FilterQuality.Medium)
            }
        }
        if (showLabel) GText(folder.name, Modifier.padding(top = 4.dp), size = 11.5f)
    }
}

/**
 * One gesture handler for tap + long-press + drag: a tap calls [onTap]; holding shows the context
 * menu; moving after holding starts a drag that is reported in root coordinates.
 */
@Composable
fun Modifier.itemGestures(
    state: LauncherState,
    payload: () -> DragPayload,
    onTap: (Rect) -> Unit,
    enabled: Boolean = true,
): Modifier {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentPayload by rememberUpdatedState(payload)
    val currentTap by rememberUpdatedState(onTap)
    val slopPx = with(LocalDensity.current) { 10.dp.toPx() }
    val vc = LocalViewConfiguration.current
    return this
        .onGloballyPositioned { coords = it }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                down.consume() // keep empty-space gestures (edit mode, double tap) from firing on icons
                // 0 = tap, 1 = cancelled, null = long press
                val result = withTimeoutOrNull(vc.longPressTimeoutMillis) {
                    var r = 1
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (c.isConsumed) break
                        if (!c.pressed) { r = 0; c.consume(); break }
                        if ((c.position - down.position).getDistance() > vc.touchSlop) break
                    }
                    r
                }
                val c0 = coords ?: return@awaitEachGesture
                when (result) {
                    0 -> currentTap(c0.boundsInRoot())
                    1 -> Unit
                    else -> {
                        state.beginPress(currentPayload(), c0.boundsInRoot(), c0.localToRoot(down.position))
                        var finished = false
                        try {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: run { state.dragEnd(); break }
                                c.consume()
                                if (!c.pressed) { state.dragEnd(); break }
                                state.dragMove(coords?.localToRoot(c.position) ?: Offset.Zero, slopPx)
                            }
                            finished = true
                        } finally {
                            // This node left the composition (or the handler was torn down) mid-gesture: nobody will
                            // ever see the finger lift, so don't leave a drag or its menu stuck on screen.
                            if (!finished) state.dragCancel()
                        }
                    }
                }
            }
        }
}

@Composable
fun WidgetCell(item: WidgetItem, state: LauncherState, backdrop: Backdrop, style: GlassStyle, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val info = remember(item.widgetId) { AppWidgetManager.getInstance(ctx).getAppWidgetInfo(item.widgetId) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .liquidGlass(backdrop, style, style.roundDp(26.dp), surfaceAlpha = style.surfaceAlpha * 0.6f)
            .clip(style.roundDp(26.dp)),
    ) {
        if (info == null) return@Box
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = maxWidth.value.toInt(); val h = maxHeight.value.toInt()
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    state.app.widgetHost.createView(c, item.widgetId, info).also { v ->
                        val v2 = v as DragAwareHostView
                        val slop = 10f * c.resources.displayMetrics.density
                        v2.onLongPress = { x, y ->
                            val screen = state.screenToRoot(Offset(x, y))
                            state.beginPress(DragPayload(item = item), Rect(origin, androidx.compose.ui.geometry.Size(v2.width.toFloat(), v2.height.toFloat())), screen)
                        }
                        v2.onDragMove = { x, y -> state.dragMove(state.screenToRoot(Offset(x, y)), slop) }
                        v2.onDragEnd = { state.dragEnd() }
                    }
                },
                update = { v -> runCatching { v.updateAppWidgetSize(android.os.Bundle(), w, h, w, h) } },
            )
        }
    }
}

private fun LayoutCoordinates.positionInRoot() = localToRoot(Offset.Zero)

/** Icon-only visual of an item, used for the drag preview and drop shadows. */
@Composable
fun DragPreview(payload: DragPayload, state: LauncherState, settings: LauncherSettings, backdrop: Backdrop, style: GlassStyle) {
    val app = payload.app ?: (payload.item as? AppItem)?.let { state.apps.byId(it.appId) }
    val folder = payload.item as? FolderItem
    when {
        app != null -> AppIconImage(app, settings, 64.dp * settings.iconScale)
        folder != null -> FolderCell(folder, state.apps::byId, settings, backdrop, style, iconSize = 64.dp)
        payload.item is WidgetItem -> Box(Modifier.size(96.dp).liquidGlass(backdrop, style, RoundedCornerShape(24.dp)))
    }
}


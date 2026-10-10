package com.linuxkernel44.openliquidlauncher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.linuxkernel44.openliquidlauncher.data.AppItem
import com.linuxkernel44.openliquidlauncher.data.FolderItem
import com.linuxkernel44.openliquidlauncher.data.Item
import com.linuxkernel44.openliquidlauncher.data.LauncherSettings
import com.linuxkernel44.openliquidlauncher.data.WidgetItem
import kotlinx.coroutines.delay

/** Renders a placed [Item] (app, folder or widget) at its cell with all gestures attached. */
@Composable
fun HomeItemView(
    item: Item,
    state: LauncherState,
    settings: LauncherSettings,
    badges: Map<String, Int>,
    backdrop: Backdrop,
    style: GlassStyle,
    modifier: Modifier = Modifier,
    showLabel: Boolean = settings.showLabels,
    iconSize: Dp = 56.dp,
) {
    val hidden = state.dragPayload?.item?.id == item.id
    val m = modifier.alpha(if (hidden) 0f else 1f)
    when (item) {
        is AppItem -> {
            val app = state.apps.byId(item.appId) ?: return
            AppCell(
                app, settings, badges[app.packageName] ?: 0,
                m.itemGestures(state, { DragPayload(item = item) }, onTap = { state.launch(app, it) }),
                iconSize = iconSize,
                showLabel = showLabel,
            )
        }
        is FolderItem -> FolderCell(
            item, state.apps::byId, settings, backdrop, style,
            m.itemGestures(state, { DragPayload(item = item) }, onTap = { state.openFolderId = item.id }),
            iconSize = iconSize,
            showLabel = showLabel,
        )
        is WidgetItem -> WidgetCell(item, state, backdrop, style, m)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Workspace(
    state: LauncherState,
    pagerState: PagerState,
    settings: LauncherSettings,
    badges: Map<String, Int>,
    backdrop: Backdrop,
    style: GlassStyle,
    onEmptyLongPress: () -> Unit,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = state.layout
    val cols = settings.columns
    val rows = settings.rows
    val current by rememberUpdatedState(pagerState.currentPage)

    // Edge hover while dragging flips pages (and creates a new trailing page).
    val payloadActive = state.dragPayload != null
    val ptr = state.dragPointer
    val rect = state.workspaceRect
    val edge = 36.dp
    val edgePx = with(LocalDensity.current) { edge.toPx() }
    val side = when {
        !payloadActive || rect == androidx.compose.ui.geometry.Rect.Zero -> 0
        ptr.x < rect.left + edgePx -> -1
        ptr.x > rect.right - edgePx -> 1
        else -> 0
    }
    LaunchedEffect(side, payloadActive) {
        if (side == 0) return@LaunchedEffect
        while (true) {
            delay(650)
            val target = current + side
            if (target < 0) break
            if (target >= layout.pages) layout.addPage()
            pagerState.animateScrollToPage(target)
        }
    }

    val sideMargin = 6.dp
    val sideMarginPx = with(LocalDensity.current) { sideMargin.toPx() }
    Box(
        modifier
            // Grid area = the pager minus the side margin. The pager itself is full width so pages slide to the screen edge.
            .onGloballyPositioned { c -> c.boundsInRoot().let { state.workspaceRect = androidx.compose.ui.geometry.Rect(it.left + sideMarginPx, it.top, it.right - sideMarginPx, it.bottom) } },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cw = (maxWidth - sideMargin * 2) / cols
            val ch = maxHeight / rows
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // Neighbours only (cheap resume); while dragging, keep every page so the dragged item's page stays composed.
                beyondViewportPageCount = if (state.dragPayload != null) layout.pages else 1,
                key = { it },
            ) { page ->
                Box(
                    Modifier.fillMaxSize().pointerInput(settings.doubleTapLock) {
                        detectTapGestures(
                            onDoubleTap = { if (settings.doubleTapLock) onDoubleTap() },
                            onLongPress = { onEmptyLongPress() },
                        )
                    },
                ) {
                    layout.placed.filter { it.page == page }.forEach { p ->
                        androidx.compose.runtime.key(p.item.id) {
                            HomeItemView(
                                p.item, state, settings, badges, backdrop, style,
                                Modifier
                                    .offset(sideMargin + cw * p.x, ch * p.y)
                                    .size(cw * p.spanX, ch * p.spanY)
                                    .padding(if (p.item is WidgetItem) 4.dp else 2.dp),
                                // Never taller than the cell: icon + label must fit, whatever the number of rows.
                                iconSize = (ch - 4.dp - (if (settings.showLabels) 22.dp else 0.dp)).coerceIn(32.dp, 56.dp),
                            )
                        }
                    }
                    // Drop highlight
                    val t = state.dropTarget
                    if (t is DropTarget.Cell && t.page == page) {
                        Box(
                            Modifier
                                .offset(sideMargin + cw * t.x, ch * t.y)
                                .size(cw * t.spanX, ch * t.spanY)
                                .padding(4.dp)
                                .background(Color.White.copy(alpha = if (t.mergeWith != null) 0.30f else if (t.push) 0.10f else 0.16f), RoundedCornerShape(22.dp)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val a by animateFloatAsState(if (i == current) 1f else 0.38f, label = "dot")
            Box(Modifier.size(if (i == current) 8.dp else 6.dp).background(Color.White.copy(alpha = a), CircleShape))
        }
    }
}

@Composable
fun Dock(
    state: LauncherState,
    settings: LauncherSettings,
    badges: Map<String, Int>,
    backdrop: Backdrop,
    style: GlassStyle,
    modifier: Modifier = Modifier,
) {
    val n = settings.dockSize
    Box(
        modifier
            .fillMaxWidth()
            .height(92.dp)
            .padding(horizontal = 10.dp)
            .liquidGlass(backdrop, style, RoundedCornerShape(34.dp), surfaceAlpha = style.surfaceAlpha + 0.04f)
            .onGloballyPositioned { state.dockRect = it.boundsInRoot() },
    ) {
        Row(Modifier.fillMaxSize()) {
            for (i in 0 until n) {
                val item = state.layout.dock.getOrNull(i)
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    val t = state.dropTarget
                    if (t is DropTarget.Slot && t.index == i) {
                        Box(Modifier.size(64.dp).background(Color.White.copy(alpha = if (t.mergeWith != null) 0.3f else 0.16f), RoundedCornerShape(22.dp)))
                    }
                    if (item != null) {
                        androidx.compose.runtime.key(item.id) {
                            HomeItemView(item, state, settings, badges, backdrop, style, Modifier.padding(4.dp), showLabel = false)
                        }
                    }
                }
            }
        }
    }
}

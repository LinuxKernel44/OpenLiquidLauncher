package com.linuxkernel44.openliquidlauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.linuxkernel44.openliquidlauncher.service.BadgeListenerService
import kotlinx.coroutines.launch

@Composable
fun LauncherScreen(state: LauncherState, onPickWallpaper: () -> Unit, onPickWidget: (android.appwidget.AppWidgetProviderInfo) -> Unit) {
    val settings by state.settings.state.collectAsState()
    val style = remember(settings) { GlassStyle.from(settings) }
    val wallpaper by state.app.wallpaper.bitmap.collectAsState()
    val badges by BadgeListenerService.counts.collectAsState()
    val loaded by state.apps.loaded.collectAsState()
    val backdrop = rememberLayerBackdrop()
    // Wallpaper + home icons: overlays that sit on top of the home screen (folders, menus...) blur this, not only the wallpaper.
    val homeBackdrop = rememberLayerBackdrop()
    val scope = rememberCoroutineScope()
    val layout = state.layout
    val pagerState = rememberPagerState { layout.pages }
    state.pagerState = pagerState

    val synced by state.apps.synced.collectAsState()
    val appList by state.apps.apps.collectAsState()
    LaunchedEffect(loaded) { if (loaded) state.ensureInitialLayout() }
    // Apps uninstalled (or hidden) while we were away: drop them from the home screen as soon as the real list is known.
    LaunchedEffect(synced, appList) { if (synced) state.layout.normalize(appList.mapTo(HashSet()) { it.id }) }
    LaunchedEffect(settings.wallpaperMode) { state.app.wallpaper.refresh() }
    LaunchedEffect(state.homeSignal) {
        if (state.homeSignal > 0) {
            state.dismissAll()
            pagerState.animateScrollToPage(0)
        }
    }
    // After dropping something dragged out of the drawer, close it.
    LaunchedEffect(state.dragPayload) {
        if (state.dragPayload == null && state.dragFromOverlay) {
            state.dragFromOverlay = false
            if (state.drawerTarget) state.closeDrawer()
        }
    }
    // Dragging an app out of the drawer: let the drawer fade away (it stays composed to keep receiving the gesture)
    // so the home screen, which is hidden while the drawer is open, becomes visible as a drop target.
    LaunchedEffect(state.dragPayload != null && state.dragFromOverlay && state.drawerTarget) {
        if (state.dragPayload != null && state.dragFromOverlay && state.drawerTarget) {
            state.drawerProgress.animateTo(0f, androidx.compose.animation.core.tween(220))
        }
    }
    BackHandler(enabled = state.anyOverlay) { scope.launch { state.dismissOverlays() } }

    val overlayFade by animateFloatAsState(if (state.dragFromOverlay && state.dragPayload != null) 0f else 1f, label = "overlayFade")
    val p = state.drawerProgress.value
    val density = LocalDensity.current
    val screenH = with(density) { 800.dp.toPx() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(settings.pinchEdit, state.anyOverlay) {
                if (!settings.pinchEdit || state.anyOverlay) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var initial = 0f
                    var fired = false
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.size >= 2 && !fired) {
                            val d = (pressed[0].position - pressed[1].position).getDistance()
                            if (initial == 0f) initial = d else if (d < initial * 0.7f) { fired = true; state.editMode = true }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            },
    ) {
        // Everything below the overlays is recorded into homeBackdrop. It bleeds 24dp off-screen so blur has content at the edges.
        Box(Modifier.fillMaxSize().bleed(24.dp).layerBackdrop(homeBackdrop)) {
            val wp = wallpaper
            if (wp != null) {
                Image(wp, null, Modifier.fillMaxSize().layerBackdrop(backdrop), contentScale = ContentScale.Crop)
            } else Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color(0xFF0B1030)))
            Box(Modifier.fillMaxSize().padding(24.dp)) {
        // ---- home ----
        // Home items resolve apps through AppRepository (not snapshot state), so wait until it is loaded.
        if (loaded) Column(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = (1f - p * 1.4f).coerceIn(0f, 1f) }
                .windowInsetsPadding(WindowInsets.statusBars)
                .pointerInput(settings.swipeUpDrawer, settings.swipeDownNotifications) {
                    var dy = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dy = 0f },
                        onDragEnd = {
                            if (state.anyOverlay && !state.drawerTarget || state.dragging) return@detectVerticalDragGestures
                            if (dy < 0 && settings.swipeUpDrawer) scope.launch { if (state.drawerProgress.value > 0.22f) state.openDrawer() else state.closeDrawer() }
                            else if (dy > 90.dp.toPx() && settings.swipeDownNotifications && !state.drawerTarget) state.expandNotifications()
                            else if (state.drawerProgress.value > 0f && !state.drawerTarget) scope.launch { state.closeDrawer() }
                        },
                        onDragCancel = { scope.launch { if (!state.drawerTarget) state.closeDrawer() } },
                    ) { change, amount ->
                        if (state.anyOverlay || state.dragging) return@detectVerticalDragGestures
                        dy += amount
                        if (dy < 0 && settings.swipeUpDrawer) {
                            scope.launch { state.drawerProgress.snapTo((-dy / (screenH * 0.7f)).coerceIn(0f, 1f)) }
                        }
                        change.consume()
                    }
                },
        ) {
            Workspace(
                state, pagerState, settings, badges, backdrop, style,
                onEmptyLongPress = { state.editMode = true },
                onDoubleTap = { state.lockScreen() },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 62.dp, bottom = 6.dp),
            )
            // Fixed height whether it shows the page dots or the edit bar, so the grid never changes size in edit mode.
            Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                if (state.editMode) EditBar(state, backdrop, style, onPickWallpaper)
                else if (layout.pages > 1) PageDots(layout.pages, pagerState.currentPage)
            }
            Dock(state, settings, badges, backdrop, style, Modifier.padding(bottom = 6.dp))
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
            }
        }

        // ---- overlays ----
        Box(
            Modifier.fillMaxSize().then(
                if (!state.drawerTarget) Modifier else Modifier.pointerInput(Unit) {
                    var dy = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dy = 0f },
                        onDragEnd = { scope.launch { if (state.drawerProgress.value < 0.75f) state.closeDrawer() else state.openDrawer() } },
                        onDragCancel = { scope.launch { state.openDrawer() } },
                    ) { change, amount ->
                        if (state.dragging) return@detectVerticalDragGestures
                        dy += amount
                        if (dy > 0) scope.launch { state.drawerProgress.snapTo((1f - dy / (screenH * 0.8f)).coerceIn(0f, 1f)) }
                        change.consume()
                    }
                },
            ),
        ) {
            AppDrawer(state, settings, badges, backdrop, style, overlayFade)
        }

        state.openFolderId?.let { FolderOverlay(state, it, settings, badges, homeBackdrop, style, overlayFade) }
        if (state.showWidgetPicker) WidgetPicker(state, homeBackdrop, style) { onPickWidget(it) }
        if (state.showSettings) SettingsScreen(state, settings, homeBackdrop, style, onPickWallpaper)
        DropZones(state, backdrop, style)
        ImportDialogs(state, homeBackdrop, style)
        state.menu?.let { ItemMenu(state, it, homeBackdrop, style) }
        DragLayer(state, settings, backdrop, style)

        if (!loaded) Box(Modifier.fillMaxSize().alpha(0.0f))
    }
}

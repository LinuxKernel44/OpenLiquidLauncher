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
    androidx.compose.runtime.SideEffect { com.kyant.backdrop.BackdropRendering.scale = com.linuxkernel44.openliquidlauncher.data.GLASS_RESOLUTIONS[settings.glassResolution] }
    val wallpaper by state.app.wallpaper.bitmap.collectAsState()
    val badges by BadgeListenerService.counts.collectAsState()
    val loaded by state.apps.loaded.collectAsState()
    val backdrop = rememberLayerBackdrop()
    // Wallpaper + home icons: overlays that sit on top of the home screen (folders, menus...) blur this, not only the wallpaper.
    val homeBackdrop = rememberLayerBackdrop()
    val scope = rememberCoroutineScope()
    state.uiScope = scope
    val layout = state.layout
    val pagerState = rememberPagerState { layout.pages }
    state.pagerState = pagerState

    val synced by state.apps.synced.collectAsState()
    val appList by state.apps.apps.collectAsState()
    LaunchedEffect(loaded) {
        if (loaded) state.ensureInitialLayout()
        // First-ever launch (not just first process start): guide the user through setup once.
        if (loaded && !state.settings.current.onboardingCompleted) state.showOnboarding = true
    }
    // First composition of this activity (cold start): play the launch animation right away. It must not wait for
    // AppRepository to finish loading: until the animation runs every home element is transparent, so waiting
    // would leave only the wallpaper on screen for as long as the icons take to decode after the process was killed.
    LaunchedEffect(Unit) {
        if (!state.introPlayed) { state.introPlayed = true; state.playIntro() }
    }
    LaunchedEffect(state.introRequest) {
        if (state.introRequest == 0) return@LaunchedEffect
        val st = state.settings.current
        if (st.introStyle == INTRO_NONE) { state.introT = 1f; return@LaunchedEffect }
        state.introT = 0f
        val ms = if (state.introQuick) minOf(INTRO_DURATIONS_MS[st.introSpeed], INTRO_QUICK_MS) else INTRO_DURATIONS_MS[st.introSpeed]
        state.introQuick = false
        androidx.compose.animation.core.animate(
            0f, 1f, animationSpec = androidx.compose.animation.core.tween(ms, easing = androidx.compose.animation.core.LinearEasing),
        ) { v, _ -> state.introT = v }
        state.introT = 1f
    }
    LaunchedEffect(state.previewRequest) {
        if (state.previewRequest == 0) return@LaunchedEffect
        state.previewing = true
        kotlinx.coroutines.delay(350)          // let the settings page fade away first
        state.playIntro()
        kotlinx.coroutines.delay(INTRO_DURATIONS_MS[state.settings.current.introSpeed] + 700L)
        state.previewing = false
    }
    // Let the icons reach the screen first, then switch the liquid glass shaders on.
    LaunchedEffect(Unit) {
        if (!GlassGate.ready) {
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            kotlinx.coroutines.delay(150)
            GlassGate.ready = true
        }
    }
    // Apps uninstalled (or hidden) while we were away: drop them from the home screen as soon as the real list is known.
    LaunchedEffect(synced, appList) { if (synced) state.layout.normalize(appList.mapTo(HashSet()) { it.id }) }
    LaunchedEffect(Unit) { state.app.wallpaper.refresh() }
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
    // Back never closes the launcher (the system would then recreate it: flicker + replayed animation).
    // Close what is open; else go back to the first page; else do nothing.
    BackHandler(enabled = true) {
        scope.launch {
            if (!state.dismissOverlays() && pagerState.currentPage != 0) pagerState.animateScrollToPage(0)
        }
    }

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
        // Render right away even before AppRepository finishes loading: icon lookups are null-safe and just show a
        // blank cell until the app resolves, so a cold start (process killed in the background) shows the grid
        // immediately instead of only the wallpaper while icons decode.
        Column(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    alpha = (1f - p * 1.4f).coerceIn(0f, 1f)
                    // Maximum quality: the home screen steps back a little while a folder grows out of its icon.
                    if (state.folderGrows && state.openFolderId != null) { val sc = 1f - 0.06f * state.folderProgress.value; scaleX = sc; scaleY = sc }
                }
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
            Box(Modifier.fillMaxWidth().height(60.dp).introEffect(state, dock = true), contentAlignment = Alignment.Center) {
                if (state.editMode) EditBar(state, backdrop, style, onPickWallpaper)
                else if (layout.pages > 1) PageDots(layout.pages, pagerState.currentPage)
            }
            Dock(state, settings, badges, backdrop, style, Modifier.padding(bottom = 6.dp).introEffect(state, dock = true))
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
        if (state.showSettings) Box(Modifier.graphicsLayer { alpha = if (state.previewing) 0f else 1f }) { SettingsScreen(state, settings, backdrop, style, onPickWallpaper) } // wallpaper only: home icons would clutter a full-screen page
        if (state.showLicenses) LicensesScreen(state, backdrop, style)
        if (state.showOnboarding) OnboardingScreen(state, backdrop, style, onPickWallpaper)
        state.wallpaperCropSource?.let { bmp ->
            WallpaperCropScreen(bmp, onConfirm = { state.confirmWallpaperCrop(it) }, onCancel = { state.wallpaperCropSource = null })
        }
        DropZones(state, backdrop, style)
        ImportDialogs(state, homeBackdrop, style)
        state.menu?.let { ItemMenu(state, it, homeBackdrop, style) }
        DragLayer(state, settings, backdrop, style)

        if (!loaded) Box(Modifier.fillMaxSize().alpha(0.0f))
    }
}

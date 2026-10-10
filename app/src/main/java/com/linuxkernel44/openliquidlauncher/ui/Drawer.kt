package com.linuxkernel44.openliquidlauncher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.linuxkernel44.openliquidlauncher.data.AppInfo
import com.linuxkernel44.openliquidlauncher.data.LauncherSettings

/** Paged horizontal app drawer rendered as a full-screen liquid glass sheet with search. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawer(
    state: LauncherState,
    settings: LauncherSettings,
    badges: Map<String, Int>,
    backdrop: Backdrop,
    style: GlassStyle,
    fade: Float,
) {
    val progress = state.drawerProgress.value
    if (progress <= 0.001f && !state.drawerTarget) return

    val all by state.apps.apps.collectAsState()
    var query by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val filtered = remember(all, query) {
        // Debug builds only: --ez debugManyApps repeats the list to test scrolling with a long list.
        val base = if (state.activity.debugManyApps) (0 until 8).flatMap { all } else all
        if (query.isBlank()) base else base.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }
    LaunchedEffect(state.drawerTarget) { if (!state.drawerTarget) { query = ""; focus.clearFocus() } }

    val cols = settings.columns
    val rows = settings.rows
    val perPage = cols * rows
    val pageCount = ((filtered.size + perPage - 1) / perPage).coerceAtLeast(1)
    val pager = rememberPagerState { pageCount }
    LaunchedEffect(query) { pager.scrollToPage(0) }

    Box(
        Modifier
            .fillMaxSize()
            .bleed()
            .liquidGlass(
                backdrop, style.copy(refractionHeight = 0f, blur = style.blur * 6f), // proportional to the Blur setting; no lens: it warps the edges of a full-screen sheet
                // Square corners: rounded ones leave sharp wallpaper showing around the sheet.
                RoundedCornerShape(0.dp),
                surfaceAlpha = style.surfaceAlpha * 0.9f + 0.06f,
                edges = false,
                layerBlock = {
                    translationY = (1f - progress) * size.height * 0.92f
                    alpha = (progress * 1.6f).coerceIn(0f, 1f) * fade
                },
            ),
    ) {
        Column(
            Modifier.fillMaxSize().padding(48.dp)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars).union(WindowInsets.ime)),
        ) {
            // With the keyboard open the grid area shrinks. Instead of squeezing the cells (which hides the names), keep
            // the normal cell size and let the grid scroll.
            val imeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
            var baseCellH by remember { mutableStateOf(0.dp) }
            // The grid (icons + labels) is recorded here so the scroll handle's glass can bend what is under it.
            val gridBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
            val handleBackdrop = com.kyant.backdrop.backdrops.rememberCombinedBackdrop(backdrop, gridBackdrop)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
                val cw = maxWidth / cols
                val ch = maxHeight / rows
                LaunchedEffect(ch, imeOpen) { if (!imeOpen) baseCellH = ch }
                if (imeOpen) {
                    val cellH = if (baseCellH > 0.dp) baseCellH else 96.dp
                    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(cols),
                        state = gridState,
                        modifier = Modifier.fillMaxSize().com_kyant_layerBackdrop(gridBackdrop),
                    ) {
                        items(filtered.size, key = { "${it}_${filtered[it].id}" }) { i ->
                            DrawerCell(filtered[i], state, settings, badges, Modifier.fillMaxWidth().height(cellH).padding(2.dp))
                        }
                    }
                    GlassScrollHandle(gridState, cols, filtered.map { it.label }, handleBackdrop, style, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                } else {
                    HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                        Box(Modifier.fillMaxSize()) {
                            filtered.drop(page * perPage).take(perPage).forEachIndexed { i, app ->
                                DrawerCell(app, state, settings, badges, Modifier.offset(cw * (i % cols), ch * (i / cols)).size(cw, ch).padding(2.dp))
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) GText("No apps found", Modifier.align(Alignment.Center), size = 16f)
            }
            if (pageCount > 1 && !imeOpen) PageDots(pageCount, pager.currentPage, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
            val requester = remember { FocusRequester() }
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 14.dp, top = 4.dp).height(52.dp)
                    .liquidGlass(backdrop, style, RoundedCornerShape(50), surfaceAlpha = style.surfaceAlpha + 0.08f)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = query, onValueChange = { query = it },
                    singleLine = true,
                    textStyle = GlassTextFieldStyle,
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { filtered.firstOrNull()?.let { state.launch(it, null) } }),
                    modifier = Modifier.fillMaxWidth().focusRequester(requester),
                    decorationBox = { inner ->
                        if (query.isEmpty()) GText("Search apps", color = Color.White.copy(alpha = 0.65f), size = 16f, align = androidx.compose.ui.text.style.TextAlign.Start)
                        inner()
                    },
                )
            }
        }
    }
}

@Composable
private fun DrawerCell(app: AppInfo, state: LauncherState, settings: LauncherSettings, badges: Map<String, Int>, modifier: Modifier) {
    AppCell(
        app, settings, badges[app.packageName] ?: 0,
        modifier
            .graphicsLayer { val sc = if (app.id in state.stackIds) 0.9f else 1f; scaleX = sc; scaleY = sc }
            .itemGestures(state, { DragPayload(app = app) }, onTap = { r ->
                if (state.holdPayload != null) state.toggleStack(app.id) else state.launch(app, r)
            }),
        selected = app.id in state.stackIds,
    )
}


/**
 * A liquid glass scroll handle for the grid: shows the position while scrolling, fades out when idle, and can be dragged
 * to jump through the list (with a bubble showing the first letter of the app under the grid's top edge).
 */
@Composable
fun GlassScrollHandle(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    columns: Int,
    labels: List<String>,
    backdrop: Backdrop,
    style: GlassStyle,
    modifier: Modifier = Modifier,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val scrolling = gridState.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) visible = true else { kotlinx.coroutines.delay(900); visible = false }
    }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (visible) 1f else 0f, label = "handleAlpha")
    val totalRows = (labels.size + columns - 1) / columns
    val fitRows = run {
        val info = gridState.layoutInfo
        val rowH = info.visibleItemsInfo.firstOrNull()?.size?.height?.coerceAtLeast(1) ?: 1
        (info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding) / rowH
    }
    // Never leave composition while the handle is being dragged: that would cut the gesture.
    if ((totalRows <= fitRows || alpha <= 0.01f) && !dragging) return

    // Pixel-accurate scroll position (row index + offset inside the row), so the handle glides instead of stepping per row.
    fun rowPx() = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.height?.toFloat()?.coerceAtLeast(1f) ?: 1f
    fun maxScroll(): Float {
        val info = gridState.layoutInfo
        val viewport = (info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding).toFloat()
        return (totalRows * rowPx() - viewport).coerceAtLeast(1f)
    }
    fun scrollPos() = (gridState.firstVisibleItemIndex / columns) * rowPx() + gridState.firstVisibleItemScrollOffset
    val gridProgress by remember(gridState, totalRows) {
        androidx.compose.runtime.derivedStateOf { (scrollPos() / maxScroll()).coerceIn(0f, 1f) }
    }
    // While dragging, the handle follows the finger exactly; the grid is driven from it.
    var dragProgress by remember { mutableStateOf(0f) }
    val progress = if (dragging) dragProgress else gridProgress

    val thumbH = 84.dp
    var trackPx by remember { mutableStateOf(1f) }
    val thumbPx = with(density) { thumbH.toPx() }
    val letter = labels.getOrNull((gridState.firstVisibleItemIndex).coerceIn(0, (labels.size - 1).coerceAtLeast(0)))?.firstOrNull()?.uppercaseChar()?.toString().orEmpty()
    val widthAnim by androidx.compose.animation.core.animateDpAsState(if (dragging) 22.dp else 14.dp, label = "handleW")

    fun dragTo(y: Float) {
        val f = ((y - thumbPx / 2) / (trackPx - thumbPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
        dragProgress = f
        // Scroll by the exact pixel difference (no per-row snapping); dispatchRawDelta is immediate and non-suspending.
        gridState.dispatchRawDelta(f * maxScroll() - scrollPos())
    }

    val dragToState = androidx.compose.runtime.rememberUpdatedState<(Float) -> Unit>({ y -> dragTo(y) })
    val gridProgressState = androidx.compose.runtime.rememberUpdatedState(gridProgress)

    Box(
        modifier
            .width(52.dp)
            .padding(vertical = 6.dp)
            .androidx_onSizeChanged { trackPx = it.height.toFloat() }
            .pointerInput(Unit) {
                // Keyed on Unit and reading the latest callbacks: the handle must not restart its gesture while scrolling.
                try {
                    detectVerticalDragGestures(
                        onDragStart = { dragProgress = gridProgressState.value; dragging = true; dragToState.value(it.y) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ -> change.consume(); dragToState.value(change.position.y) }
                } finally {
                    dragging = false
                }
            },
    ) {
        val y = with(density) { ((trackPx - thumbPx).coerceAtLeast(0f) * progress).toInt() }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { androidx.compose.ui.unit.IntOffset(0, ((trackPx - thumbPx).coerceAtLeast(0f) * (if (dragging) dragProgress else gridProgress)).toInt()) }
                .padding(end = 4.dp)
                .graphicsLayer { this.alpha = alpha }
                .size(widthAnim, thumbH)
                .liquidGlass(
                    // The refraction zone must stay narrower than half the pill: otherwise the two edges meet and leave a seam in the middle.
                    backdrop, style.copy(refractionHeight = (widthAnim.value / 2f - 2.5f).coerceAtLeast(1f), refractionAmount = 14f, blur = style.blur * 0.375f),
                    RoundedCornerShape(50), surfaceAlpha = 0.14f,
                ),
        )
        if (dragging && letter.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { androidx.compose.ui.unit.IntOffset(-with(density) { 52.dp.roundToPx() }, y + with(density) { ((thumbH - 72.dp) / 2).roundToPx() }) }
                    .size(72.dp)
                    .liquidGlass(backdrop, style.copy(refractionHeight = 14f, refractionAmount = 26f, blur = style.blur * 0.5f), CircleShape, surfaceAlpha = 0.14f),
                contentAlignment = Alignment.Center,
            ) { GText(letter, size = 28f, weight = androidx.compose.ui.text.font.FontWeight.Bold) }
        }
    }
}

private fun Modifier.com_kyant_layerBackdrop(b: com.kyant.backdrop.backdrops.LayerBackdrop) = this.layerBackdrop(b)

private fun Modifier.androidx_onSizeChanged(f: (androidx.compose.ui.unit.IntSize) -> Unit) = this.onSizeChanged(f)

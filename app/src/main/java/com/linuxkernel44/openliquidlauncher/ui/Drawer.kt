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
        if (query.isBlank()) all else all.filter { it.label.contains(query.trim(), ignoreCase = true) }
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
                backdrop, style.copy(refractionHeight = 0f, blur = style.blur + 20f), // no lens: it warps the edges of a full-screen sheet
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
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
                val cw = maxWidth / cols
                val ch = maxHeight / rows
                HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                    Box(Modifier.fillMaxSize()) {
                        filtered.drop(page * perPage).take(perPage).forEachIndexed { i, app ->
                            DrawerCell(app, state, settings, badges, Modifier.offset(cw * (i % cols), ch * (i / cols)).size(cw, ch).padding(2.dp))
                        }
                    }
                }
                if (filtered.isEmpty()) GText("No apps found", Modifier.align(Alignment.Center), size = 16f)
            }
            if (pageCount > 1) PageDots(pageCount, pager.currentPage, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
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

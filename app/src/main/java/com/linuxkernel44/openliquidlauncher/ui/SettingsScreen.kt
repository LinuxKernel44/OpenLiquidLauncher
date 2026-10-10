package com.linuxkernel44.openliquidlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.linuxkernel44.openliquidlauncher.data.LauncherSettings
import com.linuxkernel44.openliquidlauncher.data.WallpaperMode
import com.linuxkernel44.openliquidlauncher.service.BadgeListenerService
import com.linuxkernel44.openliquidlauncher.service.GestureAccessibilityService
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    state: LauncherState,
    settings: LauncherSettings,
    backdrop: Backdrop,
    style: GlassStyle,
    onPickWallpaper: () -> Unit,
) {
    val store = state.settings
    val ctx = state.activity
    // Re-read permission-like states whenever the screen recomposes.
    val notifOn = BadgeListenerService.isEnabled(ctx)
    val a11yOn = GestureAccessibilityService.isEnabled(ctx)

    Box(
        Modifier.fillMaxSize()
            .bleed()
            .liquidGlass(backdrop, style.copy(refractionHeight = 0f, blur = style.blur + 16f), RoundedCornerShape(0.dp), surfaceAlpha = style.surfaceAlpha + 0.14f, edges = false)
            .clickable(interactionSource = null, indication = null) { },
    ) {
        Column(
            Modifier.fillMaxSize().padding(48.dp).windowInsetsPadding(WindowInsets.statusBars).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
                .padding(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                GText("Settings", size = 28f, weight = FontWeight.Bold, align = TextAlign.Start, modifier = Modifier.weight(1f))
                GlassButton("Done", backdrop, style) { state.showSettings = false }
            }

            Section("Liquid glass", backdrop, style) {
                SliderRow("Blur", settings.glassBlur, 0f, 30f, "%.0f") { store.update { copy(glassBlur = it) } }
                SliderRow("Refraction height", settings.glassRefractionHeight, 0f, 40f, "%.0f") { store.update { copy(glassRefractionHeight = it) } }
                SliderRow("Refraction strength", settings.glassRefractionAmount, 0f, 60f, "%.0f") { store.update { copy(glassRefractionAmount = it) } }
                SliderRow("Surface opacity", settings.glassSurfaceAlpha, 0f, 0.6f, "%.2f") { store.update { copy(glassSurfaceAlpha = it) } }
                SwitchRow("Chromatic dispersion", settings.glassDispersion) { store.update { copy(glassDispersion = it) } }
                ButtonRow("Reset glass") { store.update { val d = LauncherSettings(); copy(glassBlur = d.glassBlur, glassRefractionHeight = d.glassRefractionHeight, glassRefractionAmount = d.glassRefractionAmount, glassSurfaceAlpha = d.glassSurfaceAlpha, glassDispersion = d.glassDispersion) } }
            }

            Section("Wallpaper", backdrop, style) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Segment("Custom image", settings.wallpaperMode == WallpaperMode.Custom) { store.update { copy(wallpaperMode = WallpaperMode.Custom) }; state.activity.refreshWallpaper() }
                    Segment("System wallpaper", settings.wallpaperMode == WallpaperMode.System) { store.update { copy(wallpaperMode = WallpaperMode.System) }; state.activity.refreshWallpaper() }
                }
                ButtonRow("Choose image…", onPickWallpaper)
                SwitchRow("Also set as system wallpaper", settings.setSystemWallpaperToo) { store.update { copy(setSystemWallpaperToo = it) } }
                if (settings.wallpaperMode == WallpaperMode.System) {
                    Hint("Reading the system wallpaper needs \"All files access\" on Android 13+.")
                    ButtonRow("Grant file access") { state.openAllFilesAccess() }
                }
            }

            Section("Home screen", backdrop, style) {
                StepperRow("Columns", settings.columns, 4, 6) { store.update { copy(columns = it) }; state.layout.reflow() }
                StepperRow("Rows", settings.rows, 4, 7) { store.update { copy(rows = it) }; state.layout.reflow() }
                StepperRow("Dock icons", settings.dockSize, 3, 6) { store.update { copy(dockSize = it) }; state.layout.ensureDockSize() }
                SliderRow("Icon size", settings.iconScale, 0.7f, 1.3f, "%.2f") { store.update { copy(iconScale = it) } }
                SwitchRow("Show labels", settings.showLabels) { store.update { copy(showLabels = it) } }
                SwitchRow("Themed icons (Android 13+)", settings.themedIcons) { store.update { copy(themedIcons = it) } }
                SwitchRow("Notification badges", settings.showBadges) { store.update { copy(showBadges = it) } }
                if (settings.showBadges && !notifOn) {
                    Hint("Badges need notification access.")
                    ButtonRow("Grant notification access") { state.openNotificationAccessSettings() }
                }
            }

            Section("Gestures", backdrop, style) {
                SwitchRow("Swipe up: app drawer", settings.swipeUpDrawer) { store.update { copy(swipeUpDrawer = it) } }
                SwitchRow("Swipe down: notifications", settings.swipeDownNotifications) { store.update { copy(swipeDownNotifications = it) } }
                SwitchRow("Double tap: lock screen", settings.doubleTapLock) { store.update { copy(doubleTapLock = it) } }
                SwitchRow("Pinch: edit home", settings.pinchEdit) { store.update { copy(pinchEdit = it) } }
                if (!a11yOn) {
                    Hint("Lock screen and notifications use an accessibility service that reads no content.")
                    ButtonRow("Enable gesture service") { state.openAccessibilitySettings() }
                }
            }

            Section("Import", backdrop, style) {
                Hint("Bring your home screen over from another launcher, like the stock OnePlus (OxygenOS) one.")
                ButtonRow("Import from my current launcher") { state.activity.beginImportFromLauncher() }
                ButtonRow("Import from launcher.db file…") { state.activity.pickLauncherDb() }
            }

            Section("System", backdrop, style) {
                ButtonRow("Set as default launcher") { state.openDefaultHomeSettings() }
                Hint("OpenLiquidLauncher 0.2.1 · Apache-2.0 · liquid glass by Kyant0/AndroidLiquidGlass")
            }
        }
    }
}

@Composable
private fun Section(title: String, backdrop: Backdrop, style: GlassStyle, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().liquidGlass(backdrop, style.copy(refractionHeight = 8f, refractionAmount = 14f, blur = 10f), RoundedCornerShape(28.dp), surfaceAlpha = 0.12f, tint = Color.Black.copy(alpha = 0.20f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GText(title, size = 13f, weight = FontWeight.Bold, color = Color.White.copy(alpha = 0.7f), align = TextAlign.Start)
        content()
    }
}

@Composable
private fun Hint(text: String) = GText(text, size = 12f, color = Color.White.copy(alpha = 0.65f), maxLines = 3, align = TextAlign.Start)

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null) { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        GText(label, Modifier.weight(1f), size = 15f, align = TextAlign.Start)
        Box(Modifier.width(52.dp).height(30.dp).background(if (value) Color(0xFF34C759) else Color.White.copy(alpha = 0.25f), CircleShape).padding(3.dp)) {
            Box(Modifier.offset(x = if (value) 22.dp else 0.dp).size(24.dp).background(Color.White, CircleShape))
        }
    }
}

@Composable
private fun StepperRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GText(label, Modifier.weight(1f), size = 15f, align = TextAlign.Start)
        StepBtn("−") { if (value > min) onChange(value - 1) }
        GText(value.toString(), Modifier.width(36.dp), size = 16f, weight = FontWeight.Bold)
        StepBtn("+") { if (value < max) onChange(value + 1) }
    }
}

@Composable
private fun StepBtn(text: String, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).background(Color.White.copy(alpha = 0.2f), CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { GText(text, size = 18f, weight = FontWeight.Bold) }
}

@Composable
private fun ButtonRow(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(50)).clickable(onClick = onClick).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        GText(label, size = 15f, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).background(if (selected) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(50)).clickable(onClick = onClick).padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { GText(label, size = 14f, weight = if (selected) FontWeight.Bold else FontWeight.Medium) }
}

@Composable
private fun SliderRow(label: String, value: Float, min: Float, max: Float, fmt: String, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            GText(label, Modifier.weight(1f), size = 15f, align = TextAlign.Start)
            GText(fmt.format(value), size = 14f, color = Color.White.copy(alpha = 0.75f))
        }
        GlassSlider(value, min, max, onChange)
    }
}

@Composable
fun GlassSlider(value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    val current by rememberUpdatedState(onChange)
    var widthPx by remember { mutableIntStateOf(1) }
    val thumb = 26.dp
    val thumbPx = with(LocalDensity.current) { thumb.toPx() }
    val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
    fun set(x: Float) {
        val f = ((x - thumbPx / 2) / (widthPx - thumbPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
        current(min + f * (max - min))
    }
    Box(
        Modifier.fillMaxWidth().height(36.dp).onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) { detectTapGestures { set(it.x) } }
            .pointerInput(Unit) { detectHorizontalDragGestures(onDragStart = { set(it.x) }) { change, _ -> set(change.position.x); change.consume() } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).background(Color.White.copy(alpha = 0.25f), CircleShape))
        Box(Modifier.offset { IntOffset(((widthPx - thumbPx) * frac).roundToInt(), 0) }.size(thumb).background(Color.White, CircleShape))
    }
}

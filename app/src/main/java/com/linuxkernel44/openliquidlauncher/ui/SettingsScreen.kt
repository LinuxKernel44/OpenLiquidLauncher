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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.linuxkernel44.openliquidlauncher.R
import com.linuxkernel44.openliquidlauncher.data.GLASS_RESOLUTIONS
import com.linuxkernel44.openliquidlauncher.data.LauncherSettings
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
    val qualityLabels = stringArrayResource(R.array.settings_quality_labels).toList()
    val introStyleLabels = stringArrayResource(R.array.settings_intro_style_labels).toList()
    val introSpeedLabels = stringArrayResource(R.array.settings_intro_speed_labels).toList()

    Box(
        Modifier.fillMaxSize()
            .bleed()
            .liquidGlass(backdrop, style.copy(refractionHeight = 0f, blur = style.blur * 5f), RoundedCornerShape(0.dp), surfaceAlpha = style.surfaceAlpha + 0.10f, tint = Color.Black.copy(alpha = 0.18f), edges = false)
            .clickable(interactionSource = null, indication = null) { },
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 66.dp).padding(top = 48.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GText(stringResource(R.string.settings_title), size = 28f, weight = FontWeight.Bold, align = TextAlign.Start, modifier = Modifier.weight(1f))
                GlassButton(stringResource(R.string.common_done), backdrop, style) { state.applySettingsAndRestart() }
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 66.dp).padding(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {

            Section(stringResource(R.string.settings_section_launch_animation), backdrop, style) {
                Hint(stringResource(R.string.settings_launch_animation_hint))
                StepRow(stringResource(R.string.settings_style_label), settings.introStyle, introStyleLabels, defaultIndex = 2) { store.update { copy(introStyle = it) } }
                StepRow(stringResource(R.string.settings_speed_label), settings.introSpeed, introSpeedLabels, defaultIndex = 1) { store.update { copy(introSpeed = it) } }
                ButtonRow(stringResource(R.string.settings_preview_button)) { state.previewRequest++ }
            }

            Section(stringResource(R.string.settings_section_performance), backdrop, style) {
                Hint(stringResource(R.string.settings_performance_hint))
                StepRow(stringResource(R.string.settings_effects_quality), settings.glassQuality, qualityLabels, defaultIndex = 2) { store.update { copy(glassQuality = it) } }
                StepRow(
                    stringResource(R.string.settings_effects_resolution), settings.glassResolution,
                    GLASS_RESOLUTIONS.map { "${(it * 100).toInt()}%" }, defaultIndex = 3,
                ) { store.update { copy(glassResolution = it) } }
                RefreshRateRow(settings.refreshRate, state.refreshRates) { store.update { copy(refreshRate = it) } }
                ButtonRow(stringResource(R.string.settings_reset_performance)) { store.update { copy(glassQuality = 2, glassResolution = 3, refreshRate = 0f) } }
            }

            Section(stringResource(R.string.settings_section_liquid_glass), backdrop, style) {
                SliderRow(stringResource(R.string.settings_roundness), settings.glassRoundness * 100f, 0f, 100f, "%.0f", default = LauncherSettings().glassRoundness * 100f) { store.update { copy(glassRoundness = it / 100f) } }
                Hint(stringResource(R.string.settings_roundness_hint))
                SliderRow(stringResource(R.string.settings_folder_roundness), settings.folderRoundness * 100f, 0f, 100f, "%.0f", default = LauncherSettings().folderRoundness * 100f) { store.update { copy(folderRoundness = it / 100f) } }
                Hint(stringResource(R.string.settings_folder_roundness_hint))
                SliderRow(stringResource(R.string.settings_droplet_look), settings.glassDroplet * 100f, 0f, 100f, "%.0f", default = LauncherSettings().glassDroplet * 100f) { store.update { copy(glassDroplet = it / 100f) } }
                Hint(stringResource(R.string.settings_droplet_look_hint))
                SliderRow(stringResource(R.string.settings_blur), settings.glassBlur, 0f, 30f, "%.1f", default = LauncherSettings().glassBlur) { store.update { copy(glassBlur = it) } }
                SliderRow(stringResource(R.string.settings_refraction_height), settings.glassRefractionHeight, 0f, 40f, "%.0f", default = LauncherSettings().glassRefractionHeight) { store.update { copy(glassRefractionHeight = it) } }
                SliderRow(stringResource(R.string.settings_refraction_strength), settings.glassRefractionAmount, 0f, 60f, "%.0f", default = LauncherSettings().glassRefractionAmount) { store.update { copy(glassRefractionAmount = it) } }
                SliderRow(stringResource(R.string.settings_surface_opacity), settings.glassSurfaceAlpha, 0f, 0.6f, "%.2f", default = LauncherSettings().glassSurfaceAlpha) { store.update { copy(glassSurfaceAlpha = it) } }
                SwitchRow(stringResource(R.string.settings_chromatic_dispersion), settings.glassDispersion) { store.update { copy(glassDispersion = it) } }
                ButtonRow(stringResource(R.string.settings_reset_glass)) { store.update { val d = LauncherSettings(); copy(glassBlur = d.glassBlur, glassRefractionHeight = d.glassRefractionHeight, glassRefractionAmount = d.glassRefractionAmount, glassSurfaceAlpha = d.glassSurfaceAlpha, glassDispersion = d.glassDispersion, glassDroplet = d.glassDroplet, glassRoundness = d.glassRoundness, folderRoundness = d.folderRoundness) } }
            }

            Section(stringResource(R.string.settings_section_wallpaper), backdrop, style) {
                ButtonRow(stringResource(R.string.settings_choose_image), onPickWallpaper)
                SwitchRow(stringResource(R.string.settings_set_system_wallpaper), settings.setSystemWallpaperToo) { store.update { copy(setSystemWallpaperToo = it) } }
                Hint(stringResource(R.string.settings_wallpaper_hint))
            }

            Section(stringResource(R.string.settings_section_home_screen), backdrop, style) {
                val systemLabel = stringResource(R.string.settings_app_icon_roundness_system)
                SliderRow(
                    stringResource(R.string.settings_app_icon_roundness), settings.appIconRoundness * 100f, 0f, 100f, "%.0f", default = 0f,
                    display = { if (it < 1f) systemLabel else "%.0f".format(it) },
                ) { store.update { copy(appIconRoundness = it / 100f) } }
                Hint(stringResource(R.string.settings_app_icon_roundness_hint))
                StepperRow(stringResource(R.string.settings_columns), settings.columns, 4, 6) { store.update { copy(columns = it) }; state.layout.reflow() }
                StepperRow(stringResource(R.string.settings_rows), settings.rows, 4, 7) { store.update { copy(rows = it) }; state.layout.reflow() }
                StepperRow(stringResource(R.string.settings_dock_icons), settings.dockSize, 3, 6) { store.update { copy(dockSize = it) }; state.layout.ensureDockSize() }
                SliderRow(stringResource(R.string.settings_icon_size), settings.iconScale, 0.7f, 1.3f, "%.2f", default = LauncherSettings().iconScale) { store.update { copy(iconScale = it) } }
                SwitchRow(stringResource(R.string.settings_show_labels), settings.showLabels) { store.update { copy(showLabels = it) } }
                SwitchRow(stringResource(R.string.settings_themed_icons), settings.themedIcons) { store.update { copy(themedIcons = it) } }
                SwitchRow(stringResource(R.string.settings_auto_add_new_apps), settings.autoAddNewApps) { store.update { copy(autoAddNewApps = it) } }
                Hint(stringResource(R.string.settings_auto_add_hint))
                SwitchRow(stringResource(R.string.settings_notification_badges), settings.showBadges) { store.update { copy(showBadges = it) } }
                if (settings.showBadges && !notifOn) {
                    Hint(stringResource(R.string.settings_badges_need_access_hint))
                    ButtonRow(stringResource(R.string.settings_grant_notification_access)) { state.openNotificationAccessSettings() }
                }
            }

            Section(stringResource(R.string.settings_section_gestures), backdrop, style) {
                SwitchRow(stringResource(R.string.settings_swipe_up), settings.swipeUpDrawer) { store.update { copy(swipeUpDrawer = it) } }
                SwitchRow(stringResource(R.string.settings_swipe_down), settings.swipeDownNotifications) { store.update { copy(swipeDownNotifications = it) } }
                SwitchRow(stringResource(R.string.settings_double_tap_lock), settings.doubleTapLock) { store.update { copy(doubleTapLock = it) } }
                SwitchRow(stringResource(R.string.settings_pinch_edit), settings.pinchEdit) { store.update { copy(pinchEdit = it) } }
                if (!a11yOn) {
                    Hint(stringResource(R.string.settings_gesture_service_hint))
                    ButtonRow(stringResource(R.string.settings_enable_gesture_service)) { state.openAccessibilitySettings() }
                }
            }

            Section(stringResource(R.string.settings_section_import), backdrop, style) {
                Hint(stringResource(R.string.settings_import_hint))
                ButtonRow(stringResource(R.string.settings_import_current)) { state.activity.beginImportFromLauncher() }
                ButtonRow(stringResource(R.string.settings_import_shizuku)) { state.activity.beginImportWithShizuku() }
                ButtonRow(stringResource(R.string.settings_import_file)) { state.activity.pickLauncherDb() }
            }

            Section(stringResource(R.string.settings_section_system), backdrop, style) {
                ButtonRow(stringResource(R.string.settings_set_default_launcher)) { state.openDefaultHomeSettings() }
                ButtonRow(stringResource(R.string.settings_open_source_licenses)) { state.showLicenses = true }
                ButtonRow(stringResource(R.string.settings_replay_onboarding)) { state.showOnboarding = true }
                Hint(stringResource(R.string.settings_footer))
            }
            }
        }
    }
}

@Composable
internal fun Section(title: String, backdrop: Backdrop, style: GlassStyle, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().liquidGlass(backdrop, style.copy(refractionHeight = 8f, refractionAmount = 14f, blur = style.blur * 2.5f), style.roundDp(28.dp), surfaceAlpha = 0.12f, tint = Color.Black.copy(alpha = 0.20f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GText(title, size = 13f, weight = FontWeight.Bold, color = Color.White.copy(alpha = 0.7f), align = TextAlign.Start)
        content()
    }
}

@Composable
internal fun Hint(text: String) = GText(text, size = 12f, color = Color.White.copy(alpha = 0.65f), maxLines = 3, align = TextAlign.Start)

@Composable
internal fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
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
internal fun ButtonRow(label: String, onClick: () -> Unit) {
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
private fun SliderRow(label: String, value: Float, min: Float, max: Float, fmt: String, default: Float, display: ((Float) -> String)? = null, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            GText(label, Modifier.weight(1f), size = 15f, align = TextAlign.Start)
            GText(display?.invoke(value) ?: fmt.format(value), size = 14f, color = Color.White.copy(alpha = 0.75f))
        }
        GlassSlider(value, min, max, onChange, default)
    }
}

@Composable
/** Double tap resets to [default] (when given). */
fun GlassSlider(value: Float, min: Float, max: Float, onChange: (Float) -> Unit, default: Float? = null) {
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
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { default?.let { d -> current(d) } }, onTap = { set(it.x) }) }
            .pointerInput(Unit) { detectHorizontalDragGestures(onDragStart = { set(it.x) }) { change, _ -> set(change.position.x); change.consume() } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).background(Color.White.copy(alpha = 0.25f), CircleShape))
        Box(Modifier.offset { IntOffset(((widthPx - thumbPx) * frac).roundToInt(), 0) }.size(thumb).background(Color.White, CircleShape))
    }
}


/** A slider that snaps to labelled steps; the default step is marked with a small tick under the track. */
@Composable
private fun StepRow(label: String, index: Int, labels: List<String>, defaultIndex: Int, onChange: (Int) -> Unit) {
    val current by rememberUpdatedState(onChange)
    // The gesture handlers below live as long as the composable: they must read the *latest* index, not the first one.
    val latestIndex by rememberUpdatedState(index)
    var widthPx by remember { mutableIntStateOf(1) }
    val thumb = 26.dp
    val thumbPx = with(LocalDensity.current) { thumb.toPx() }
    val last = (labels.size - 1).coerceAtLeast(1)
    fun at(x: Float) {
        val f = ((x - thumbPx / 2) / (widthPx - thumbPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val i = (f * last).roundToInt()
        if (i != latestIndex) current(i)
    }
    Column {
        Row(Modifier.fillMaxWidth()) {
            GText(label, Modifier.weight(1f), size = 15f, align = TextAlign.Start)
            GText(
                if (index == defaultIndex) stringResource(R.string.common_default_suffix, labels[index.coerceIn(0, last)]) else labels[index.coerceIn(0, last)],
                size = 14f, color = Color.White.copy(alpha = 0.8f),
            )
        }
        Box(
            Modifier.fillMaxWidth().height(36.dp).onSizeChanged { widthPx = it.width }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { current(defaultIndex) }, onTap = { at(it.x) }) }
                .pointerInput(Unit) { detectHorizontalDragGestures(onDragStart = { at(it.x) }) { change, _ -> at(change.position.x); change.consume() } },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(6.dp).background(Color.White.copy(alpha = 0.25f), CircleShape))
            for (i in 0..last) {
                val x = ((widthPx - thumbPx) * i / last + thumbPx / 2).roundToInt()
                Box(Modifier.offset { IntOffset(x - 2, 0) }.size(4.dp, if (i == defaultIndex) 14.dp else 8.dp).background(Color.White.copy(alpha = if (i == defaultIndex) 0.9f else 0.45f), CircleShape))
            }
            Box(Modifier.offset { IntOffset(((widthPx - thumbPx) * index / last).roundToInt(), 0) }.size(thumb).background(Color.White, CircleShape))
        }
    }
}

@Composable
private fun RefreshRateRow(value: Float, rates: List<Int>, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GText(stringResource(R.string.settings_refresh_rate), size = 15f, align = TextAlign.Start, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Segment(stringResource(R.string.settings_refresh_auto), value == 0f) { onChange(0f) }
            if (rates.size > 1) Segment(stringResource(R.string.settings_refresh_max), value == -1f) { onChange(-1f) }
            rates.forEach { hz -> Segment("$hz", value == hz.toFloat()) { onChange(hz.toFloat()) } }
        }
        Hint(stringResource(R.string.settings_refresh_rate_hint))
    }
}

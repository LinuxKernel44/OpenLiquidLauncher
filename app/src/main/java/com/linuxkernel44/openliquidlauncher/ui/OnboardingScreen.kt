package com.linuxkernel44.openliquidlauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kyant.backdrop.Backdrop
import com.linuxkernel44.openliquidlauncher.R
import com.linuxkernel44.openliquidlauncher.service.BadgeListenerService
import com.linuxkernel44.openliquidlauncher.service.GestureAccessibilityService

private const val PAGE_WELCOME = 0
private const val PAGE_WALLPAPER = 1
private const val PAGE_DEFAULT = 2
private const val PAGE_PERMISSIONS = 3
private const val PAGE_IMPORT = 4
private const val PAGE_DONE = 5

@Composable
fun OnboardingScreen(
    state: LauncherState,
    backdrop: Backdrop,
    style: GlassStyle,
    onPickWallpaper: () -> Unit,
) {
    var page by remember { mutableIntStateOf(PAGE_WELCOME) }
    // Re-read live state (default launcher / permissions) whenever the user comes back from a system settings screen.
    var resumeTick by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val isDefault = remember(resumeTick) { state.activity.isDefaultHome() }
    val notifOn = remember(resumeTick) { BadgeListenerService.isEnabled(state.activity) }
    val a11yOn = remember(resumeTick) { GestureAccessibilityService.isEnabled(state.activity) }

    fun finish() {
        state.settings.update { copy(onboardingCompleted = true) }
        state.showOnboarding = false
    }
    fun next() { if (page < PAGE_DONE) page++ else finish() }

    BackHandler(enabled = true) { if (page > PAGE_WELCOME) page-- }

    Box(
        Modifier.fillMaxSize()
            .bleed()
            .liquidGlass(backdrop, style.copy(refractionHeight = 0f, blur = style.blur * 5f), RoundedCornerShape(0.dp), surfaceAlpha = style.surfaceAlpha + 0.10f, tint = Color.Black.copy(alpha = 0.18f), edges = false)
            .clickable(interactionSource = null, indication = null) { },
    ) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars)).padding(horizontal = 66.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                when (page) {
                    PAGE_WELCOME -> OnboardingStep(stringResource(R.string.onboarding_welcome_title), stringResource(R.string.onboarding_welcome_body))
                    PAGE_WALLPAPER -> OnboardingStep(stringResource(R.string.onboarding_wallpaper_title), stringResource(R.string.onboarding_wallpaper_body)) {
                        ButtonRow(stringResource(R.string.settings_choose_image), onPickWallpaper)
                    }
                    PAGE_DEFAULT -> OnboardingStep(
                        stringResource(R.string.onboarding_default_title),
                        if (isDefault) stringResource(R.string.onboarding_default_done) else stringResource(R.string.onboarding_default_body),
                    ) {
                        if (!isDefault) ButtonRow(stringResource(R.string.settings_set_default_launcher)) { state.openDefaultHomeSettings() }
                    }
                    PAGE_PERMISSIONS -> OnboardingStep(stringResource(R.string.onboarding_permissions_title), stringResource(R.string.onboarding_permissions_body)) {
                        Section(stringResource(R.string.settings_notification_badges), backdrop, style) {
                            Hint(stringResource(R.string.settings_badges_need_access_hint))
                            if (!notifOn) ButtonRow(stringResource(R.string.settings_grant_notification_access)) { state.openNotificationAccessSettings() }
                        }
                        Section(stringResource(R.string.onboarding_gestures_label), backdrop, style) {
                            Hint(stringResource(R.string.onboarding_gestures_body))
                            if (!a11yOn) ButtonRow(stringResource(R.string.settings_enable_gesture_service)) { state.openAccessibilitySettings() }
                        }
                    }
                    PAGE_IMPORT -> OnboardingStep(stringResource(R.string.onboarding_import_title), stringResource(R.string.onboarding_import_body)) {
                        ButtonRow(stringResource(R.string.onboarding_import_button)) { state.activity.beginImportFromLauncher() }
                    }
                    PAGE_DONE -> OnboardingStep(stringResource(R.string.onboarding_done_title), stringResource(R.string.onboarding_done_body))
                }
            }

            Column(Modifier.padding(bottom = 64.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PageDots(PAGE_DONE + 1, page, Modifier.fillMaxWidth().padding(bottom = 4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (page != PAGE_DONE) {
                        Box(
                            Modifier.weight(1f).liquidGlass(backdrop, style, style.round(50, small = true), surfaceAlpha = style.surfaceAlpha + 0.06f, small = true)
                                .clickable { finish() }.padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) { GText(stringResource(R.string.common_skip), size = 15f, weight = FontWeight.SemiBold) }
                    }
                    Box(
                        Modifier.weight(1f).liquidGlass(backdrop, style, style.round(50, small = true), surfaceAlpha = style.surfaceAlpha + 0.22f, small = true)
                            .clickable { next() }.padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        GText(
                            when (page) {
                                PAGE_WELCOME -> stringResource(R.string.onboarding_get_started)
                                PAGE_DONE -> stringResource(R.string.onboarding_finish)
                                else -> stringResource(R.string.common_next)
                            },
                            size = 15f, weight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingStep(title: String, body: String, content: @Composable (androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GText(title, size = 26f, weight = FontWeight.Bold, align = TextAlign.Start, maxLines = 2, modifier = Modifier.fillMaxWidth())
        GText(body, size = 15f, color = Color.White.copy(alpha = 0.8f), align = TextAlign.Start, maxLines = 6, modifier = Modifier.fillMaxWidth())
        if (content != null) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

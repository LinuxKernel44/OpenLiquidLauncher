package com.linuxkernel44.openliquidlauncher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop

/** One third-party dependency credited in NOTICE, kept in sync with it. */
private data class ThirdPartyLicense(val name: String, val license: String, val notes: String, val url: String)

private val THIRD_PARTY_LICENSES = listOf(
    ThirdPartyLicense(
        "AndroidLiquidGlass", "Apache-2.0",
        "By Kyant0. Vendored and modified (added a render-resolution scale option) in com/kyant/backdrop.",
        "github.com/Kyant0/AndroidLiquidGlass",
    ),
    ThirdPartyLicense("Shapes", "Apache-2.0", "By Kyant0. iOS-like shapes for Compose.", "github.com/Kyant0/Shapes"),
    ThirdPartyLicense("Shizuku API / Provider", "MIT", "By Rikka (RikkaApps). Powers the Shizuku-assisted import.", "github.com/RikkaApps/Shizuku-API"),
    ThirdPartyLicense("AndroidX", "Apache-2.0", "Core & Activity Compose libraries.", "developer.android.com/jetpack/androidx"),
    ThirdPartyLicense("JetBrains Compose Multiplatform", "Apache-2.0", "Foundation, UI, Material ripple.", "github.com/JetBrains/compose-multiplatform"),
    ThirdPartyLicense("JetBrains Annotations", "Apache-2.0", "", "github.com/JetBrains/java-annotations"),
)

@Composable
fun LicensesScreen(
    state: LauncherState,
    backdrop: Backdrop,
    style: GlassStyle,
) {
    Box(
        Modifier.fillMaxSize()
            .bleed()
            .liquidGlass(backdrop, style.copy(refractionHeight = 0f, blur = style.blur * 5f), RoundedCornerShape(0.dp), surfaceAlpha = style.surfaceAlpha + 0.10f, tint = Color.Black.copy(alpha = 0.18f), edges = false)
            .clickable(interactionSource = null, indication = null) { },
    ) {
        Column(
            Modifier.fillMaxSize().padding(48.dp).windowInsetsPadding(WindowInsets.statusBars).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
                .padding(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                GText("Open source licenses", size = 24f, weight = FontWeight.Bold, align = TextAlign.Start, modifier = Modifier.weight(1f))
                GlassButton("Done", backdrop, style) { state.showLicenses = false }
            }

            Section("OpenLiquidLauncher", backdrop, style) {
                Hint("Licensed under the Apache License, Version 2.0. Full source: github.com/LinuxKernel44/OpenLiquidLauncher")
            }

            THIRD_PARTY_LICENSES.forEach { lib ->
                Section(lib.name, backdrop, style) {
                    GText(lib.license, size = 13f, weight = FontWeight.SemiBold, align = TextAlign.Start)
                    if (lib.notes.isNotEmpty()) Hint(lib.notes)
                    Hint(lib.url)
                }
            }
        }
    }
}

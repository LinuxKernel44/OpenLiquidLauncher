package com.linuxkernel44.openliquidlauncher.diag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.linuxkernel44.openliquidlauncher.ui.ButtonRow
import com.linuxkernel44.openliquidlauncher.ui.GText
import com.linuxkernel44.openliquidlauncher.ui.GlassStyle
import com.linuxkernel44.openliquidlauncher.ui.Hint
import com.linuxkernel44.openliquidlauncher.ui.Section
import com.linuxkernel44.openliquidlauncher.ui.StepBtn
import com.linuxkernel44.openliquidlauncher.ui.SwitchRow
import kotlinx.coroutines.delay

/** Settings section of the diagnostic build. English only on purpose: it is a temporary test aid, not a shipped feature. */
@Composable
fun DiagSettingsSection(backdrop: Backdrop, style: GlassStyle) {
    val c = LocalContext.current
    var capture by remember { mutableStateOf(Diag.captureEnabled(c)) }
    var cap by remember { mutableIntStateOf(Diag.capGb(c)) }
    var used by remember { mutableLongStateOf(0L) }
    var files by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(Diag.status) }
    LaunchedEffect(Unit) {
        while (true) {
            used = Diag.usedBytes(c); files = Diag.fileCount(c); status = Diag.status
            delay(2000)
        }
    }
    Section("DIAGNOSTICS (test build)", backdrop, style) {
        SwitchRow("Capture system logs", capture) { capture = it; Diag.setCaptureEnabled(c, it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GText("Storage cap", Modifier.weight(1f), size = 15f, align = TextAlign.Start)
            StepBtn("−") { if (cap > 5) { cap -= 5; Diag.setCapGb(c, cap) } else if (cap > 1) { cap = 1; Diag.setCapGb(c, cap) } }
            GText("$cap GB", Modifier.width(72.dp), size = 16f, weight = FontWeight.Bold)
            StepBtn("+") { cap = if (cap < 5) 5 else cap + 5; Diag.setCapGb(c, cap) }
        }
        Hint("Status: $status\nCaptured: ${"%.1f".format(used / 1048576.0)} MB in $files files. Stops by itself at the cap or below 5 GB free.")
        ButtonRow("Mark bug now") { Diag.mark(c) }
        ButtonRow("Clear captured logs") { Diag.clear(c) }
    }
}

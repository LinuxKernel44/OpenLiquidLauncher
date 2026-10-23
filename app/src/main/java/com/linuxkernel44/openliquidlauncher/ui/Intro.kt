package com.linuxkernel44.openliquidlauncher.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

const val INTRO_NONE = 0
const val INTRO_FADE = 1
const val INTRO_ZOOM = 2
const val INTRO_CASCADE = 3
const val INTRO_SLIDE = 4

val INTRO_DURATIONS_MS = intArrayOf(260, 420, 700)

private fun easeOutCubic(x: Float): Float { val i = 1f - x; return 1f - i * i * i }

/** Back-ease-out: overshoots a little before settling, which reads as a spring. */
private fun backOut(x: Float): Float { val i = x - 1f; return 1f + 2.70158f * i * i * i + 1.70158f * i * i }

/**
 * The launch animation: while [LauncherState.introT] goes 0 -> 1 the element fades/zooms/slides in. Items start one after
 * the other (cascade by cell, slide by row, dock last). Read in the layer block only, so it never recomposes.
 */
fun Modifier.introEffect(state: LauncherState, cell: Int = 0, row: Int = 0, dock: Boolean = false): Modifier = graphicsLayer {
    val style = state.settings.current.introStyle
    val t = state.introT
    if (style == INTRO_NONE || t >= 1f) return@graphicsLayer
    val cols = state.layout.columns
    val rows = state.layout.rows
    val delay = when (style) {
        INTRO_CASCADE -> if (dock) 0.55f else 0.5f * (cell / maxOf(1f, cols * rows - 1f))
        INTRO_SLIDE -> if (dock) 0.4f else 0.3f * (row / maxOf(1f, rows - 1f))
        else -> if (dock) 0.15f else 0f
    }
    val p = ((t - delay) / (1f - delay)).coerceIn(0f, 1f)
    when (style) {
        INTRO_FADE -> alpha = p
        INTRO_ZOOM -> {
            val e = easeOutCubic(p)
            alpha = e; scaleX = 0.8f + 0.2f * e; scaleY = scaleX
        }
        INTRO_CASCADE -> {
            val e = easeOutCubic(p)
            alpha = e; scaleX = 0.6f + 0.4f * e; scaleY = scaleX
            translationY = (1f - e) * 14.dp.toPx()
        }
        INTRO_SLIDE -> {
            alpha = (p * 1.6f).coerceAtMost(1f)
            translationY = (1f - backOut(p)) * 56.dp.toPx()
        }
    }
}
